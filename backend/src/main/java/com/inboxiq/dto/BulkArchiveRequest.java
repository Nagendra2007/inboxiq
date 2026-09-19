package com.inboxiq.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Taking a selection out of the inbox, or putting it back — here and in Gmail. */
public record BulkArchiveRequest(
        @NotEmpty(message = "Select at least one email")
        @Size(max = 100, message = "Act on at most 100 emails at a time")
        List<UUID> ids,
        boolean archived
) {}
