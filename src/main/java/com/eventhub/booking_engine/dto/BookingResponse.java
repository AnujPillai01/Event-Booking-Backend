package com.eventhub.booking_engine.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BookingResponse(
        String bookingReference,
        Long userId,
        Long showId,
        Long showSeatId,
        BigDecimal amountPaid,
        LocalDateTime createdAt
) {}