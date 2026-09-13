package com.inboxiq.dto;

import java.time.Instant;
import java.util.UUID;

/** Lightweight view used for inbox list rendering — no body content. */
public record EmailSummaryDto(
        UUID id,
        String sender,
        String subject,
        String snippet,
        Instant receivedAt,
        boolean read,
        boolean hasAttachments,
        EmailAnalysisDto analysis // may be null if analysis hasn't run yet
) {}
