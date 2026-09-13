package com.inboxiq.service;

import com.inboxiq.config.AppProperties;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.gmail.GmailInboxClient;
import com.inboxiq.gmail.GmailListPage;
import com.inboxiq.gmail.ParsedGmailMessage;
import com.inboxiq.repository.EmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Pulls messages from Gmail into the local database.
 *
 * Today this implements the "initial sync" the spec calls for: page through
 * INBOX (newest first) up to a configured cap, skipping any message id
 * already present for this account (the unique constraint on
 * (mail_account_id, provider_message_id) makes this safe even under
 * concurrent syncs). Each newly stored message is queued for AI analysis
 * asynchronously so the sync call itself returns quickly.
 *
 * "Future incremental sync" (the spec's phrase) is a straightforward
 * extension of the same shape once real Gmail push notifications are wired
 * up: call {@code gmail.users().history().list(...)} starting from
 * {@link MailAccount#getLastHistoryId()} instead of re-listing the whole
 * inbox, applying only the added/changed message ids it reports, then
 * advancing {@code lastHistoryId}. The storage/dedup/analysis path below is
 * already shared and needs no change to support that.
 */
@Service
public class EmailSyncService {

    private static final Logger log = LoggerFactory.getLogger(EmailSyncService.class);

    private final GmailInboxClient gmailInboxClient;
    private final EmailRepository emailRepository;
    private final EmailPersistenceService emailPersistenceService;
    private final EmailAnalysisService emailAnalysisService;
    private final AppProperties appProperties;
    private final RateLimiterService rateLimiterService;

    public EmailSyncService(GmailInboxClient gmailInboxClient,
                             EmailRepository emailRepository,
                             EmailPersistenceService emailPersistenceService,
                             EmailAnalysisService emailAnalysisService,
                             AppProperties appProperties,
                             RateLimiterService rateLimiterService) {
        this.gmailInboxClient = gmailInboxClient;
        this.emailRepository = emailRepository;
        this.emailPersistenceService = emailPersistenceService;
        this.emailAnalysisService = emailAnalysisService;
        this.appProperties = appProperties;
        this.rateLimiterService = rateLimiterService;
    }

    public record SyncResult(int fetched, int newlyStored) {}

    /**
     * Runs synchronously, storing new messages and scheduling their
     * analysis. How many messages this pass is allowed to pull depends on
     * whether the account has ever synced before:
     *  - First sync ever (zero emails stored for this account): capped at
     *    {@code app.gmail.first-sync-message-cap} — small and fast, just
     *    enough for the inbox to not feel empty.
     *  - Every sync after that: pulls everything new, up to this account's
     *    remaining headroom under the lifetime cap
     *    {@code app.gmail.max-total-synced-messages}.
     * Either way this stays small enough to run within a normal HTTP
     * request timeout.
     */
    @Transactional
    public SyncResult syncInbox(MailAccount account) {
        rateLimiterService.checkGmailSyncRequest(account.getUser().getId());
        int pageSize = appProperties.getGmail().getInitialSyncPageSize();

        long alreadyStored = emailRepository.countByMailAccountId(account.getId());
        int cap = alreadyStored == 0
                ? appProperties.getGmail().getFirstSyncMessageCap()
                : (int) Math.max(0, appProperties.getGmail().getMaxTotalSyncedMessages() - alreadyStored);

        if (cap == 0) {
            log.info("Gmail sync for account id={} skipped: already at the {}-message lifetime cap",
                    account.getId(), appProperties.getGmail().getMaxTotalSyncedMessages());
            return new SyncResult(0, 0);
        }

        int fetched = 0;
        int stored = 0;
        String pageToken = null;

        do {
            GmailListPage page = gmailInboxClient.listInboxMessages(account, pageToken, Math.min(pageSize, cap - fetched));
            for (String messageId : page.messageIds()) {
                fetched++;
                if (storeIfNew(account, messageId) != null) {
                    stored++;
                }
                if (fetched >= cap) break;
            }
            pageToken = page.nextPageToken();
        } while (pageToken != null && fetched < cap);

        log.info("Gmail sync for account id={} complete: fetched={}, newlyStored={}", account.getId(), fetched, stored);
        return new SyncResult(fetched, stored);
    }

    /**
     * Fetches and persists one message if it isn't already stored. Returns
     * null if it already existed — either found by this cheap up-front
     * check, or discovered via a race that {@link EmailPersistenceService}
     * handles safely (see its javadoc).
     */
    private EmailMessage storeIfNew(MailAccount account, String providerMessageId) {
        Optional<EmailMessage> existing = emailRepository.findByMailAccountIdAndProviderMessageId(account.getId(), providerMessageId);
        if (existing.isPresent()) {
            return null;
        }

        ParsedGmailMessage parsed = gmailInboxClient.getMessage(account, providerMessageId);
        EmailMessage saved = emailPersistenceService.storeIfNew(account, parsed);
        if (saved == null) {
            return null;
        }

        // Fire-and-forget: analysis runs on its own thread so this sync
        // request doesn't block on an LLM round trip. Failures are handled
        // (and logged) inside EmailAnalysisService itself.
        emailAnalysisService.analyzeAsync(saved.getId());
        return saved;
    }
}
