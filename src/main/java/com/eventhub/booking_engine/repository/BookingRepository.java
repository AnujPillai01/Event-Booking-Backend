package com.eventhub.booking_engine.repository;

import com.eventhub.booking_engine.schema.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    // Used for Idempotency lookups
    Optional<Booking> findByBookingReference(String bookingReference);
}