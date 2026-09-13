package com.inboxiq.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateActionItemRequest(
        @NotNull(message = "completed is required.")
        Boolean completed
) {}
