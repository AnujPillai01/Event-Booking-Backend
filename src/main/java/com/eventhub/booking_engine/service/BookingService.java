package com.eventhub.booking_engine.service;

import com.eventhub.booking_engine.exception.ResourceNotFoundException;
import com.eventhub.booking_engine.exception.SeatLockedException;
import com.eventhub.booking_engine.repository.BookingRepository;
import com.eventhub.booking_engine.repository.ShowSeatRepository;
import com.eventhub.booking_engine.schema.Booking;
import com.eventhub.booking_engine.schema.SeatStatus;
import com.eventhub.booking_engine.schema.ShowSeat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final ShowSeatRepository showSeatRepository;
    private final BookingRepository bookingRepository;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    private static final long HOLD_TTL_MINUTES = 5;

    /**
     * PHASE 1: Acquire a distributed lock and place a temporary 5-minute TTL hold in Redis.
     */
    public String lockSeat(Long showId, Long seatId, Long userId) {
        String lockKey = "lock:show:" + showId + ":seat:" + seatId;
        String holdKey = "hold:show:" + showId + ":seat:" + seatId;

        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean isLocked = lock.tryLock(2, 10, TimeUnit.SECONDS);
            if (!isLocked) {
                throw new SeatLockedException("High traffic on this seat. Please try again.");
            }

            RBucket<String> holdBucket = redissonClient.getBucket(holdKey, StringCodec.INSTANCE);
            String currentHolder = holdBucket.get();

            if (currentHolder != null && !currentHolder.equals(String.valueOf(userId))) {
                throw new SeatLockedException("Seat is currently held by another user.");
            }

            ShowSeat seat = showSeatRepository.findByIdAndShowId(seatId, showId)
                    .orElseThrow(() -> new ResourceNotFoundException("Seat not found"));

            if (seat.getStatus() == SeatStatus.BOOKED) {
                throw new SeatLockedException("Seat is already permanently booked.");
            }

            holdBucket.set(String.valueOf(userId), Duration.ofMinutes(HOLD_TTL_MINUTES));

            log.info("User {} locked seat {} for show {}", userId, seatId, showId);
            return "Seat temporarily locked for 5 minutes.";

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock acquisition interrupted", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * PHASE 2: Verify hold, commit to MySQL inside an ACID transaction, and update Redis after commit.
     */
    public Booking confirmBooking(Long showId, Long seatId, Long userId, String idempotencyKey) {
        String idemCacheKey = "idem:booking:" + idempotencyKey;
        String idemLockKey = "lock:idem:" + idempotencyKey;

        RLock idemLock = redissonClient.getLock(idemLockKey);

        try {
            if (!idemLock.tryLock(5, 15, TimeUnit.SECONDS)) {
                throw new SeatLockedException("Duplicate booking request currently in progress.");
            }

            RBucket<String> idemBucket = redissonClient.getBucket(idemCacheKey, StringCodec.INSTANCE);
            String existingBookingRef = idemBucket.get();
            if (existingBookingRef != null) {
                return bookingRepository.findByBookingReference(existingBookingRef)
                        .orElseThrow(() -> new ResourceNotFoundException("Booking reference not found"));
            }

            String holdKey = "hold:show:" + showId + ":seat:" + seatId;
            RBucket<String> holdBucket = redissonClient.getBucket(holdKey, StringCodec.INSTANCE);
            String currentHolder = holdBucket.get();

            if (currentHolder == null || !currentHolder.equals(String.valueOf(userId))) {
                throw new SeatLockedException("Seat hold has expired or belongs to another user.");
            }

            // Execute DB changes inside an explicit transaction
            return transactionTemplate.execute(status -> {
                ShowSeat seat = showSeatRepository.findByIdAndShowId(seatId, showId)
                        .orElseThrow(() -> new ResourceNotFoundException("Seat not found"));

                if (seat.getStatus() == SeatStatus.BOOKED) {
                    throw new SeatLockedException("Seat is already booked.");
                }

                seat.setStatus(SeatStatus.BOOKED);
                showSeatRepository.save(seat);

                Booking booking = Booking.builder()
                        .bookingReference(UUID.randomUUID().toString())
                        .userId(userId)
                        .showId(showId)
                        .showSeatId(seat.getId())
                        .amountPaid(seat.getPrice())
                        .createdAt(LocalDateTime.now())
                        .build();

                bookingRepository.save(booking);

                // Only update Redis AFTER MySQL successfully commits
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        holdBucket.delete();
                        idemBucket.set(booking.getBookingReference(), Duration.ofHours(24));
                        log.info("Booking confirmed & Redis synced. Ref: {}", booking.getBookingReference());
                    }
                });

                return booking;
            });

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Idempotency lock interrupted", e);
        } finally {
            if (idemLock.isHeldByCurrentThread()) {
                idemLock.unlock();
            }
        }
    }
}