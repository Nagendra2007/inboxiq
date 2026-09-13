package com.inboxiq.gmail;

/**
 * Everything needed to send a reply through Gmail while keeping it correctly
 * threaded in the recipient's client.
 */
public record OutgoingReply(
        String toAddress,
        String subject,
        String bodyText,
        String inReplyToMessageId,   // RFC 822 Message-ID header of the email being replied to, or null
        String references,          // RFC 822 References header chain, or null
        String gmailThreadId        // Gmail's own thread id, so the sent message joins the same thread
) {}
