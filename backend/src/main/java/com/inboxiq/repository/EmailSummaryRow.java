package com.inboxiq.repository;

import com.inboxiq.entity.EmailAnalysis;

import java.time.Instant;
import java.util.UUID;

/**
 * Exactly the columns the inbox list needs, read in one query together with
 * the email's analysis.
 *
 * Loading {@link com.inboxiq.entity.EmailMessage} entities instead would pull
 * every message's full text and HTML body across the wire — megabytes per
 * page, for a list that shows a one-line snippet — and then fire a second
 * query per row for the analysis, because the inverse side of a one-to-one
 * can't be proxied. See {@code EmailRepository#findSummaries}.
 */
public record EmailSummaryRow(
        UUID id,
        String sender,
        String subject,
        String snippet,
        Instant receivedAt,
        boolean read,
        boolean hasAttachments,
        EmailAnalysis analysis
) {}
