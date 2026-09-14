package com.inboxiq.service;

import com.inboxiq.entity.AnalysisStatus;
import com.inboxiq.entity.EmailAnalysis;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.gmail.HtmlSanitizer;
import com.inboxiq.gmail.ParsedGmailMessage;
import com.inboxiq.repository.EmailAnalysisRepository;
import com.inboxiq.repository.EmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists one Gmail message per call, in its own independent transaction
 * (REQUIRES_NEW). This is a distinct bean-level method (not a self-invocation
 * of a method on {@link EmailSyncService}) so that Spring's transaction proxy
 * actually applies.
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
    private final EmailAnalysisRepository emailAnalysisRepository;
    private final HtmlSanitizer htmlSanitizer;
    private final ObjectMapper objectMapper;

    public EmailPersistenceService(EmailRepository emailRepository,
                                   EmailAnalysisRepository emailAnalysisRepository,
                                   HtmlSanitizer htmlSanitizer,
                                   ObjectMapper objectMapper) {
        this.emailRepository = emailRepository;
        this.emailAnalysisRepository = emailAnalysisRepository;
        this.htmlSanitizer = htmlSanitizer;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the saved message, or null if it already existed — whether found
     * up front or discovered via a race. A new message is stored together
     * with a PENDING analysis row, so it shows as "Analyzing…" at once and a
     * restart can't lose track of the analysis it still owes (see
     * AnalysisQueue#resumeUnfinished).
     */
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

        EmailMessage saved;
        try {
            saved = emailRepository.saveAndFlush(email);
        } catch (DataIntegrityViolationException e) {
            log.debug("Skipping message {} for account {}: already stored by a concurrent sync",
                    parsed.messageId(), account.getId());
            return null;
        }

        EmailAnalysis pending = new EmailAnalysis();
        pending.setEmail(saved);
        pending.setAnalysisStatus(AnalysisStatus.PENDING);
        saved.setAnalysis(emailAnalysisRepository.save(pending));
        return saved;
    }

    /**
     * Applies read/unread changes made in Gmail to stored messages. Returns
     * only the messages whose state actually changed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<EmailMessage> applyReadStates(UUID mailAccountId, Map<String, Boolean> readByProviderId) {
        if (readByProviderId.isEmpty()) return List.of();
        List<EmailMessage> changed = new ArrayList<>();
        for (EmailMessage email : emailRepository.findByMailAccountIdAndProviderMessageIdIn(mailAccountId, readByProviderId.keySet())) {
            boolean read = readByProviderId.get(email.getProviderMessageId());
            if (email.isRead() != read) {
                email.setRead(read);
                changed.add(email);
            }
        }
        emailRepository.saveAll(changed);
        return changed;
    }

    /**
     * Removes the local copies of messages deleted, trashed or marked as spam
     * in Gmail (their analysis, to-dos and drafts go with them via ON DELETE
     * CASCADE). Returns the ids of the rows removed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> deleteByProviderMessageIds(UUID mailAccountId, Collection<String> providerMessageIds) {
        if (providerMessageIds.isEmpty()) return List.of();
        List<UUID> ids = emailRepository.findByMailAccountIdAndProviderMessageIdIn(mailAccountId, providerMessageIds)
                .stream().map(EmailMessage::getId).toList();
        if (!ids.isEmpty()) {
            emailRepository.deleteAllByIdInBatch(ids);
        }
        return ids;
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
