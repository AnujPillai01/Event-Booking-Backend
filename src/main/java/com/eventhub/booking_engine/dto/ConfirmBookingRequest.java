package com.eventhub.booking_engine.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ConfirmBookingRequest(
        @NotNull(message = "userId is required")
        @Positive(message = "userId must be positive")
        Long userId,

        @NotNull(message = "showId is required")
        @Positive(message = "showId must be positive")
        Long showId,

        @NotNull(message = "seatId is required")
        @Positive(message = "seatId must be positive")
        Long seatId
) {}