package com.inboxiq.dto;

import java.time.Instant;

/** Persisted Gmail sync state for the signed-in user's mailbox, plus whether a pass is running now. */
public record SyncStatusDto(
        boolean syncing,
        boolean initialSyncCompleted,
        Instant lastSyncAt,
        String lastError,
        boolean reauthRequired
) {}
