package com.inboxiq.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The emails a "delete selected" asks to remove. Capped so one request can't
 * turn into an unbounded run of Gmail calls; the frontend sends larger
 * selections in batches.
 */
public record BulkDeleteRequest(
        @NotEmpty(message = "Select at least one email to delete")
        @Size(max = 100, message = "Delete at most 100 emails at a time")
        List<UUID> ids
) {}
