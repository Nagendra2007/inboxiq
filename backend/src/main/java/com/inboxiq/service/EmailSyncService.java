package com.inboxiq.service;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.inboxiq.config.AppProperties;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.exception.GmailIntegrationException;
import com.inboxiq.gmail.GmailHistoryPage;
import com.inboxiq.gmail.GmailInboxClient;
import com.inboxiq.gmail.ParsedGmailMessage;
import com.inboxiq.mapper.EmailMapper;
import com.inboxiq.realtime.EventStreamService;
import com.inboxiq.realtime.RealtimeEvent;
import com.inboxiq.repository.EmailRepository;
import com.inboxiq.repository.MailAccountRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Brings the local copy of a Gmail inbox up to date, one pass at a time.
 * Passes run on background threads (see SyncCoordinator) — never inside a
 * login or page request — and report progress over SSE.
 *
 * Which kind of pass runs is decided by state persisted on the mail account,
 * not by anything in the browser:
 *
 *  - First sync (no history checkpoint yet): read the mailbox's current
 *    history id, then fetch only the newest {@code first-sync-message-cap}
 *    inbox messages (one list call — mailbox size doesn't matter).
 *  - Every later pass: ask Gmail's history API for changes since the
 *    checkpoint, and apply only those: new inbox messages are stored,
 *    read/unread changes mirrored, deleted/trashed messages removed. Nothing
 *    already stored is downloaded again.
 *  - If the checkpoint is too old for Gmail's history (roughly a week of
 *    absence): catch up with the newest messages, like a first sync, rather
 *    than rescanning the mailbox.
 *
 * The checkpoint is saved only after a pass has applied everything up to it,
 * and every write is idempotent (Gmail's message id is unique per mailbox),
 * so a pass that fails or is cut off halfway is simply redone from the same
 * point by the next one, without duplicates.
 *
 * Stored messages appear in the browser at once (email.saved); AI analysis
 * is queued separately and never delays a sync (see AnalysisQueue).
 */
@Service
public class EmailSyncService {

    private static final Logger log = LoggerFactory.getLogger(EmailSyncService.class);

    public enum Mode { INITIAL, INCREMENTAL, CATCH_UP }

    /** What a completed pass did. */
    public record SyncOutcome(Mode mode, int newEmails, int updatedEmails, int removedEmails) {}

    private record PassResult(Mode mode, String checkpoint, int stored, int updated, int removed) {}

    private final GmailInboxClient gmailInboxClient;
    private final EmailRepository emailRepository;
    private final MailAccountRepository mailAccountRepository;
    private final EmailPersistenceService emailPersistenceService;
    private final MailAccountService mailAccountService;
    private final AnalysisQueue analysisQueue;
    private final EventStreamService events;
    private final EmailMapper emailMapper;
    private final AppProperties appProperties;
    private final ExecutorService downloadPool;

    public EmailSyncService(GmailInboxClient gmailInboxClient,
                            EmailRepository emailRepository,
                            MailAccountRepository mailAccountRepository,
                            EmailPersistenceService emailPersistenceService,
                            MailAccountService mailAccountService,
                            AnalysisQueue analysisQueue,
                            EventStreamService events,
                            EmailMapper emailMapper,
                            AppProperties appProperties) {
        this.gmailInboxClient = gmailInboxClient;
        this.emailRepository = emailRepository;
        this.mailAccountRepository = mailAccountRepository;
        this.emailPersistenceService = emailPersistenceService;
        this.mailAccountService = mailAccountService;
        this.analysisQueue = analysisQueue;
        this.events = events;
        this.emailMapper = emailMapper;
        this.appProperties = appProperties;

        AtomicInteger threadNumber = new AtomicInteger();
        this.downloadPool = Executors.newFixedThreadPool(
                Math.max(1, appProperties.getGmail().getFetchConcurrency()),
                runnable -> {
                    Thread thread = new Thread(runnable, "gmail-fetch-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Runs one pass for this mailbox. Failures are recorded on the account
     * and reported as sync.error rather than thrown. Returns null if the pass
     * didn't run or failed.
     */
    public SyncOutcome syncMailbox(UUID mailAccountId, SyncTrigger trigger) {
        MailAccount account = mailAccountRepository.findById(mailAccountId).filter(MailAccount::isActive).orElse(null);
        if (account == null) return null; // disconnected or deleted meanwhile
        UUID userId = account.getUser().getId();
        boolean initial = account.getLastHistoryId() == null;

        events.publish(userId, RealtimeEvent.SYNC_STARTED, Map.of("trigger", trigger, "initial", initial));
        try {
            PassResult pass = runPassWithFreshTokenOnRejection(account, userId);
            mailAccountService.recordSyncSuccess(mailAccountId, pass.checkpoint(), true);

            Map<String, Object> completed = new LinkedHashMap<>();
            completed.put("trigger", trigger);
            completed.put("mode", pass.mode());
            completed.put("initial", pass.mode() == Mode.INITIAL);
            completed.put("newEmails", pass.stored());
            completed.put("updatedEmails", pass.updated());
            completed.put("removedEmails", pass.removed());
            completed.put("lastSyncAt", Instant.now().toString());
            events.publish(userId, RealtimeEvent.SYNC_COMPLETED, completed);

            if (pass.stored() > 0 || pass.updated() > 0 || pass.removed() > 0 || pass.mode() != Mode.INCREMENTAL) {
                log.info("Gmail sync ({}, {}) for account id={}: new={}, updated={}, removed={}",
                        pass.mode(), trigger, mailAccountId, pass.stored(), pass.updated(), pass.removed());
            }
            return new SyncOutcome(pass.mode(), pass.stored(), pass.updated(), pass.removed());

        } catch (GmailIntegrationException e) {
            boolean reauth = "GMAIL_REAUTH_REQUIRED".equals(e.getErrorCode());
            if (reauth) {
                mailAccountService.markReauthRequired(mailAccountId);
            }
            log.warn("Gmail sync failed for account id={} [{}]", mailAccountId, e.getErrorCode());
            reportFailure(mailAccountId, userId, e.getErrorCode(), e.getMessage(), reauth);
            return null;
        } catch (RuntimeException e) {
            log.error("Gmail sync crashed for account id={}", mailAccountId, e);
            reportFailure(mailAccountId, userId, "SYNC_FAILED",
                    "Syncing with Gmail failed. It will be retried automatically.", false);
            return null;
        }
    }

    private void reportFailure(UUID mailAccountId, UUID userId, String code, String message, boolean reauthRequired) {
        mailAccountService.recordSyncFailure(mailAccountId, message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("reauthRequired", reauthRequired);
        events.publish(userId, RealtimeEvent.SYNC_ERROR, payload);
    }

    /**
     * Gmail can reject an access token that still looked valid to us (clock
     * skew, or access just revoked). Force one refresh and retry once; if
     * Google then rejects the refresh token too, the account is flagged for
     * reconnecting by GmailCredentialProvider.
     */
    private PassResult runPassWithFreshTokenOnRejection(MailAccount account, UUID userId) {
        try {
            return runPass(account, userId);
        } catch (GmailIntegrationException e) {
            if (!(e.getCause() instanceof GoogleJsonResponseException response) || response.getStatusCode() != 401) {
                throw e;
            }
            mailAccountService.expireAccessToken(account.getId());
            account.setTokenExpiry(null);
            return runPass(account, userId);
        }
    }

    private PassResult runPass(MailAccount account, UUID userId) {
        Gmail gmail = gmailInboxClient.open(account);
        if (account.getLastHistoryId() == null) {
            return newestMessagesPass(gmail, account, userId, Mode.INITIAL);
        }
        try {
            return historyPass(gmail, account, userId);
        } catch (GmailInboxClient.HistoryExpiredException e) {
            log.info("Gmail history checkpoint for account id={} expired; catching up with the newest messages", account.getId());
            return newestMessagesPass(gmail, account, userId, Mode.CATCH_UP);
        }
    }

    /** First sync (and catch-up): the newest N inbox messages only. */
    private PassResult newestMessagesPass(Gmail gmail, MailAccount account, UUID userId, Mode mode) {
        // Read the checkpoint *before* listing, so anything arriving while
        // this pass runs is picked up by the next (history) pass.
        String checkpoint = gmailInboxClient.currentHistoryId(gmail);
        List<String> newest = gmailInboxClient.latestInboxMessageIds(gmail, appProperties.getGmail().getFirstSyncMessageCap());
        int stored = storeMissing(gmail, account, userId, newest);
        return new PassResult(mode, checkpoint, stored, 0, 0);
    }

    /** Later syncs: only what changed since the checkpoint. */
    private PassResult historyPass(Gmail gmail, MailAccount account, UUID userId) {
        String checkpoint = account.getLastHistoryId();
        List<GmailHistoryPage.Change> changes = new ArrayList<>();
        String pageToken = null;
        do {
            GmailHistoryPage page = gmailInboxClient.listHistory(gmail, account.getLastHistoryId(), pageToken);
            changes.addAll(page.changes());
            if (page.historyId() != null) checkpoint = page.historyId();
            pageToken = page.nextPageToken();
        } while (pageToken != null);

        if (changes.isEmpty()) {
            return new PassResult(Mode.INCREMENTAL, checkpoint, 0, 0, 0);
        }
        HistoryDelta delta = HistoryDelta.of(changes);

        List<UUID> removed = emailPersistenceService.deleteByProviderMessageIds(account.getId(), delta.messagesToRemove());
        for (UUID id : removed) {
            events.publish(userId, RealtimeEvent.EMAIL_DELETED, Map.of("id", id));
        }

        List<EmailMessage> updated = emailPersistenceService.applyReadStates(account.getId(), delta.readStates());
        for (EmailMessage email : updated) {
            events.publish(userId, RealtimeEvent.EMAIL_UPDATED, Map.of("id", email.getId(), "read", email.isRead()));
        }

        // Archived in Gmail (or put back): the browser drops it from the
        // inbox list, or refetches to pick it up again.
        List<EmailMessage> reshelved = emailPersistenceService.applyArchiveStates(account.getId(), delta.archiveStates());
        for (EmailMessage email : reshelved) {
            events.publish(userId, RealtimeEvent.EMAIL_UPDATED,
                    Map.of("id", email.getId(), "read", email.isRead(), "archived", email.isArchived()));
        }

        int cap = appProperties.getGmail().getMaxNewMessagesPerSync();
        if (delta.candidateCount() > cap) {
            log.info("Account id={} has {} new messages since the last sync; storing the newest {}",
                    account.getId(), delta.candidateCount(), cap);
        }
        int stored = storeMissing(gmail, account, userId, delta.messagesToStore(cap));
        return new PassResult(Mode.INCREMENTAL, checkpoint, stored, updated.size() + reshelved.size(), removed.size());
    }

    /**
     * Downloads (in parallel) and stores the given messages that aren't
     * stored yet, in the given order. Each one is announced as soon as it is
     * saved and queued for analysis — nothing waits for the AI.
     */
    private int storeMissing(Gmail gmail, MailAccount account, UUID userId, List<String> messageIds) {
        List<String> missing = messageIds.stream()
                .filter(id -> !emailRepository.existsByMailAccountIdAndProviderMessageId(account.getId(), id))
                .toList();
        if (missing.isEmpty()) return 0;

        for (String id : missing) {
            events.publish(userId, RealtimeEvent.EMAIL_RECEIVED, Map.of("gmailMessageId", id));
        }
        List<CompletableFuture<ParsedGmailMessage>> downloads = missing.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> gmailInboxClient.getMessageIfPresent(gmail, id), downloadPool))
                .toList();

        int stored = 0;
        try {
            for (CompletableFuture<ParsedGmailMessage> download : downloads) {
                ParsedGmailMessage parsed = await(download);
                if (parsed == null) continue; // removed from Gmail in the meantime

                EmailMessage saved = emailPersistenceService.storeIfNew(account, parsed);
                if (saved == null) continue; // stored by a concurrent pass

                stored++;
                events.publish(userId, RealtimeEvent.EMAIL_SAVED, Map.of("email", emailMapper.toSummaryDto(saved)));
                analysisQueue.enqueue(saved.getId(), userId);
            }
        } finally {
            // If a download failed, skip the ones that haven't started.
            downloads.forEach(download -> download.cancel(false));
        }
        return stored;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }

    @PreDestroy
    void shutdown() {
        downloadPool.shutdownNow();
    }
}
