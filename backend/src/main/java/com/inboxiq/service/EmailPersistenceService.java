package com.inboxiq.service;

import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.gmail.HtmlSanitizer;
import com.inboxiq.gmail.ParsedGmailMessage;
import com.inboxiq.repository.EmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Persists one Gmail message per call, in its own independent transaction
 * (REQUIRES_NEW). This is a distinct bean-level method (not a self-invocation
 * of a method on {@link EmailSyncService}) so that Spring's transaction proxy
 * actually applies — the same reasoning as {@code EmailAnalysisService#analyzeAsync}.
 *
 * Two things fall out of running each insert in its own transaction:
 *  - A duplicate-key conflict on one message never poisons the outer sync
 *    transaction, which spans the whole inbox page/batch.
 *  - Two overlapping sync requests for the same account (a double-clicked
 *    Sync button, a retried request after a slow response, etc.) race safely
 *    on the database's own unique constraint on
 *    (mail_account_id, provider_message_id) instead of one of them crashing
 *    the whole request with an unhandled DataIntegrityViolationException.
 */
@Service
public class EmailPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(EmailPersistenceService.class);

    private final EmailRepository emailRepository;
    private final HtmlSanitizer htmlSanitizer;
    private final ObjectMapper objectMapper;

    public EmailPersistenceService(EmailRepository emailRepository, HtmlSanitizer htmlSanitizer, ObjectMapper objectMapper) {
        this.emailRepository = emailRepository;
        this.htmlSanitizer = htmlSanitizer;
        this.objectMapper = objectMapper;
    }

    /** Returns the saved message, or null if it already existed — whether found up front or discovered via a race. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EmailMessage storeIfNew(MailAccount account, ParsedGmailMessage parsed) {
        if (emailRepository.findByMailAccountIdAndProviderMessageId(account.getId(), parsed.messageId()).isPresent()) {
            return null;
        }

        EmailMessage email = new EmailMessage();
        email.setMailAccount(account);
        email.setProviderMessageId(parsed.messageId());
        email.setThreadId(parsed.threadId());
        email.setSender(parsed.sender());
        email.setRecipient(parsed.toRecipients());
        email.setCcRecipient(parsed.ccRecipients());
        email.setSubject(parsed.subject());
        email.setSnippet(parsed.snippet());
        email.setBodyText(parsed.bodyText());
        email.setBodyHtml(parsed.bodyHtmlRaw() != null ? htmlSanitizer.sanitize(parsed.bodyHtmlRaw()) : null);
        email.setReceivedAt(parsed.receivedAt());
        email.setRead(!parsed.unread());
        email.setHasAttachments(!parsed.attachments().isEmpty());
        email.setAttachmentsMetadataJson(toAttachmentsJson(parsed.attachments()));

        try {
            return emailRepository.saveAndFlush(email);
        } catch (DataIntegrityViolationException e) {
            log.debug("Skipping message {} for account {}: already stored by a concurrent sync",
                    parsed.messageId(), account.getId());
            return null;
        }
    }

    private String toAttachmentsJson(List<ParsedGmailMessage.AttachmentMeta> attachments) {
        try {
            List<Map<String, Object>> simplified = attachments.stream()
                    .map(a -> Map.<String, Object>of(
                            "filename", a.filename() == null ? "" : a.filename(),
                            "mimeType", a.mimeType() == null ? "" : a.mimeType(),
                            "sizeBytes", a.sizeBytes() == null ? 0L : a.sizeBytes()))
                    .toList();
            return objectMapper.writeValueAsString(simplified);
        } catch (Exception e) {
            return "[]";
        }
    }
}
