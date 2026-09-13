package com.inboxiq.gmail;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a raw Gmail API {@link Message} (whose MIME structure is an
 * arbitrarily nested tree of {@link MessagePart}s) into a flat,
 * easy-to-persist {@link ParsedGmailMessage}.
 *
 * Handles: single-part plain-text or HTML messages, multipart/alternative
 * (plain + HTML versions of the same content — we keep both), and
 * multipart/mixed with attachments (recorded as metadata only — filename,
 * mime type, size — we never fetch attachment bytes here; see README
 * "Email ingestion" section on why attachment bodies are fetched on demand,
 * not during sync).
 */
@Component
public class GmailMessageParser {

    private static final String LABEL_UNREAD = "UNREAD";

    public ParsedGmailMessage parse(Message message) {
        MessagePart payload = message.getPayload();
        Map<String, String> headers = payload != null ? extractHeaders(payload) : Map.of();

        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<ParsedGmailMessage.AttachmentMeta> attachments = new ArrayList<>();
        if (payload != null) {
            collectParts(payload, text, html, attachments);
        }

        Instant receivedAt = message.getInternalDate() != null
                ? Instant.ofEpochMilli(message.getInternalDate())
                : null;
        boolean unread = message.getLabelIds() != null && message.getLabelIds().contains(LABEL_UNREAD);

        return new ParsedGmailMessage(
                message.getId(),
                message.getThreadId(),
                headers.get("From"),
                headers.get("To"),
                headers.get("Cc"),
                headers.get("Subject"),
                message.getSnippet(),
                text.isEmpty() ? null : text.toString(),
                html.isEmpty() ? null : html.toString(),
                receivedAt,
                unread,
                attachments
        );
    }

    private Map<String, String> extractHeaders(MessagePart part) {
        Map<String, String> map = new LinkedHashMap<>();
        if (part.getHeaders() != null) {
            for (MessagePartHeader header : part.getHeaders()) {
                // A header can legally repeat; the first occurrence is what
                // every mail client treats as authoritative.
                map.putIfAbsent(header.getName(), header.getValue());
            }
        }
        return map;
    }

    private void collectParts(MessagePart part, StringBuilder text, StringBuilder html,
                               List<ParsedGmailMessage.AttachmentMeta> attachments) {
        if (part == null) return;

        boolean looksLikeAttachment = part.getFilename() != null && !part.getFilename().isBlank()
                && part.getBody() != null && part.getBody().getAttachmentId() != null;
        if (looksLikeAttachment) {
            Integer size = part.getBody().getSize();
            attachments.add(new ParsedGmailMessage.AttachmentMeta(
                    part.getFilename(), part.getMimeType(), size != null ? size.longValue() : null));
            return;
        }

        if (part.getParts() != null && !part.getParts().isEmpty()) {
            for (MessagePart child : part.getParts()) {
                collectParts(child, text, html, attachments);
            }
            return;
        }

        if (part.getBody() == null || part.getBody().getData() == null) return;
        String decoded = decodeBase64Url(part.getBody().getData());
        String mimeType = part.getMimeType();

        if (mimeType != null && mimeType.equalsIgnoreCase("text/plain")) {
            text.append(decoded);
        } else if (mimeType != null && mimeType.equalsIgnoreCase("text/html")) {
            html.append(decoded);
        }
    }

    private String decodeBase64Url(String data) {
        byte[] bytes = Base64.getUrlDecoder().decode(data);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
