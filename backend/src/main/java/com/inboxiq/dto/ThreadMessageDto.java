package com.inboxiq.dto;

import java.time.Instant;

/** One message within a Gmail thread, fetched live from Gmail (not persisted). */
public record ThreadMessageDto(
        String messageId,
        String sender,
        String subject,
        String snippet,
        String bodyText,
        Instant receivedAt,
        boolean unread
) {}
