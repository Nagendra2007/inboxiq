package com.inboxiq.gmail;

import java.time.Instant;
import java.util.List;

/** Structured, already-decoded view of a raw Gmail API {@code Message}. */
public record ParsedGmailMessage(
        String messageId,
        String threadId,
        String sender,
        String toRecipients,
        String ccRecipients,
        String subject,
        String snippet,
        String bodyText,
        String bodyHtmlRaw,
        Instant receivedAt,
        boolean unread,
        List<AttachmentMeta> attachments
) {
    public record AttachmentMeta(String filename, String mimeType, Long sizeBytes) {}
}
