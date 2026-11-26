package com.eventhub.booking_engine.controller;

import com.eventhub.booking_engine.dto.BookingResponse;
import com.eventhub.booking_engine.dto.ConfirmBookingRequest;
import com.eventhub.booking_engine.dto.LockSeatRequest;
import com.eventhub.booking_engine.schema.Booking;
import com.eventhub.booking_engine.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /**
     * Step 1: Temporarily lock a seat in Redis for 5 minutes.
     */
    @PostMapping("/shows/{showId}/seats/{seatId}/lock")
    public ResponseEntity<Map<String, String>> lockSeat(
            @PathVariable Long showId,
            @PathVariable Long seatId,
            @RequestBody @Valid LockSeatRequest request) {

        String message = bookingService.lockSeat(showId, seatId, request.userId());
        return ResponseEntity.ok(Map.of("message", message));
    }

    /**
     * Step 2: Confirm the booking using an Idempotency-Key header.
     */
    @PostMapping("/confirm")
    public ResponseEntity<BookingResponse> confirmBooking(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid ConfirmBookingRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }

        Booking booking = bookingService.confirmBooking(
                request.showId(),
                request.seatId(),
                request.userId(),
                idempotencyKey.trim()
        );

        BookingResponse response = new BookingResponse(
                booking.getBookingReference(),
                booking.getUserId(),
                booking.getShowId(),
                booking.getShowSeatId(),
                booking.getAmountPaid(),
                booking.getCreatedAt()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}