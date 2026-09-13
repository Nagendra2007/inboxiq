package com.inboxiq.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full view for the email detail pane, including sanitized body and extracted action items. */
public record EmailDetailDto(
        UUID id,
        String sender,
        String recipient,
        String ccRecipient,
        String subject,
        String snippet,
        String bodyText,
        String bodyHtml, // already sanitized — see HtmlSanitizer
        Instant receivedAt,
        boolean read,
        boolean hasAttachments,
        String threadId,
        EmailAnalysisDto analysis,
        List<ActionItemDto> actionItems
) {}
