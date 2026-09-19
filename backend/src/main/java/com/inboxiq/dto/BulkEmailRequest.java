package com.inboxiq.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The emails a bulk action applies to. Capped so one request can't turn into
 * an unbounded run of Gmail calls; the frontend sends larger selections in
 * batches.
 */
public record BulkEmailRequest(
        @NotEmpty(message = "Select at least one email")
        @Size(max = 100, message = "Act on at most 100 emails at a time")
        List<UUID> ids
) {}
