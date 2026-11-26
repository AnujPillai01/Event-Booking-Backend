package com.eventhub.booking_engine.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LockSeatRequest(
        @NotNull(message = "userId is required")
        @Positive(message = "userId must be positive")
        Long userId
) {}