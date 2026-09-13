package com.inboxiq.dto;

import java.time.Instant;
import java.util.UUID;

/** {@code emailId} is null for a from-scratch compose draft (no source email). */
public record GeneratedReplyDto(
        UUID id,
        UUID emailId,
        String userPrompt,
        String content,
        String toneAdjustment,
        boolean sent,
        Instant sentAt
) {}
