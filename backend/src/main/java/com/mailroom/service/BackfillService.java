package com.mailroom.service;

import com.mailroom.entity.AnalysisStatus;
import com.mailroom.entity.EmailMessage;
import com.mailroom.entity.MailAccount;
import com.mailroom.gmail.GmailInboxClient;
import com.mailroom.exception.GmailIntegrationException;
import com.mailroom.gmail.GmailListPage;
import com.mailroom.gmail.ParsedGmailMessage;
import com.mailroom.realtime.EventStreamService;
import com.mailroom.realtime.RealtimeEvent;
import com.mailroom.repository.EmailRepository;
import com.mailroom.repository.MailAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;

/**
 * Imports a mailbox's history, a page at a time.
 *
 * <p>The first sync takes only the newest handful of messages, because
 * signing in should not wait on a mailbox. That is the right trade for the
 * inbox and the wrong one for anything that searches: a question like
 * "where are my fee receipts" is asking about mail from months ago, and
 * mail that was never stored cannot be found.
 *
 * <p>Three things make this bearable to run against a real mailbox.
 *
 * <p>It is <b>resumable</b>. Gmail's page token is written to the account
 * after every page, so a deploy in the middle of a ten thousand message
 * import costs one page rather than the whole run.
 *
 * <p>It is <b>paced</b>. Gmail's per-user rate limit is the binding
 * constraint, not this machine, so pages are fetched in order with a pause
 * between them rather than as fast as the network allows.
 *
 * <p>And it <b>does not analyse what it imports</b>. One AI call per
 * message across a whole mailbox is an expense nobody asked for, for
 * messages nearly all of which will never be read. Imported mail is stored
 * {@link AnalysisStatus#DEFERRED} and analysed when something needs it.
 */
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    /** Gmail's own maximum for a message list page. */
    private static final int PAGE_SIZE = 100;

    /**
     * Between pages. Gmail bills a list call cheaply and a message fetch
     * dearly, and a hundred fetches land in a burst — this keeps a large
     * import from spending the whole per-user quota and stalling the
     * ordinary sync that shares it.
     */
    private static final Duration BETWEEN_PAGES = Duration.ofSeconds(2);

    private static final DateTimeFormatter GMAIL_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final MailAccountRepository mailAccountRepository;
    private final EmailRepository emailRepository;
    private final GmailInboxClient gmail;
    private final EmailPersistenceService emailPersistenceService;
    private final EventStreamService events;

    /** Accounts importing right now, so a second request does not duplicate one. */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mail-backfill");
        thread.setDaemon(true);
        return thread;
    });

    public BackfillService(MailAccountRepository mailAccountRepository,
                           EmailRepository emailRepository,
                           GmailInboxClient gmail,
                           EmailPersistenceService emailPersistenceService,
                           EventStreamService events) {
        this.mailAccountRepository = mailAccountRepository;
        this.emailRepository = emailRepository;
        this.gmail = gmail;
        this.emailPersistenceService = emailPersistenceService;
        this.events = events;
    }

    /** What the settings page shows while an import runs, and afterwards. */
    public record Progress(boolean running, int stored, Instant startedAt, Instant finishedAt, String query) {}

    @Transactional(readOnly = true)
    public Progress progress(UUID mailAccountId) {
        MailAccount account = mailAccountRepository.findById(mailAccountId).orElse(null);
        if (account == null) return new Progress(false, 0, null, null, null);
        return new Progress(running.contains(mailAccountId), account.getBackfillStored(),
                account.getBackfillStartedAt(), account.getBackfillFinishedAt(), account.getBackfillQuery());
    }

    /**
     * Starts importing, or resumes one that stopped.
     *
     * @param months how far back to go, at least one
     * @return false when an import for this account is already running
     */
    @Transactional
    public boolean start(UUID mailAccountId, int months) {
        MailAccount account = mailAccountRepository.findById(mailAccountId)
                .filter(MailAccount::isActive).orElse(null);
        if (account == null || !running.add(mailAccountId)) return false;

        String query = queryFor(months);
        // A different range is a different run: the page token belongs to
        // the query that produced it, and reusing it across queries pages
        // through the wrong result set.
        if (!query.equals(account.getBackfillQuery())) {
            account.setBackfillQuery(query);
            account.setBackfillPageToken(null);
            account.setBackfillStored(0);
        }
        account.setBackfillStartedAt(Instant.now());
        account.setBackfillFinishedAt(null);
        mailAccountRepository.save(account);

        workers.submit(() -> run(mailAccountId));
        return true;
    }

    /**
     * Gmail's query syntax, and deliberately not {@code in:inbox}: the
     * receipts and confirmations worth searching for are exactly the mail
     * that has been filed, archived or auto-labelled away.
     *
     * <p>Always bounded by a date. An unbounded import of a mailbox years
     * deep is a lot of somebody's Gmail quota spent on mail nobody is
     * going to ask about.
     */
    private static String queryFor(int months) {
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusMonths(Math.max(1, months));
        return "-in:chats after:" + from.format(GMAIL_DATE);
    }

    private void run(UUID mailAccountId) {
        int stored = 0;
        try {
            while (true) {
                MailAccount account = mailAccountRepository.findById(mailAccountId)
                        .filter(MailAccount::isActive).orElse(null);
                if (account == null) return;

                GmailListPage page = gmail.search(account, account.getBackfillQuery(),
                        account.getBackfillPageToken(), PAGE_SIZE);
                stored += storePage(account, page.messageIds());

                // Written before the pause, so a restart resumes from the
                // page after the one just finished rather than repeating it.
                account.setBackfillPageToken(page.nextPageToken());
                account.setBackfillStored(account.getBackfillStored() + stored);
                mailAccountRepository.save(account);
                publish(account, page.nextPageToken() == null);
                stored = 0;

                if (page.nextPageToken() == null) {
                    finish(mailAccountId);
                    return;
                }
                Thread.sleep(BETWEEN_PAGES.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (GmailIntegrationException e) {
            // Nothing is lost: the token is stored, and starting again
            // continues from where this stopped.
            log.warn("History import paused for account id={}: {}", mailAccountId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("History import failed for account id={}", mailAccountId, e);
        } finally {
            running.remove(mailAccountId);
        }
    }

    private int storePage(MailAccount account, List<String> messageIds) {
        List<String> missing = messageIds.stream()
                .filter(id -> !emailRepository.existsByMailAccountIdAndProviderMessageId(account.getId(), id))
                .toList();

        int stored = 0;
        for (String id : missing) {
            ParsedGmailMessage parsed = gmail.getMessageIfPresent(gmail.open(account), id);
            if (parsed == null) continue; // deleted from Gmail meanwhile
            EmailMessage saved = emailPersistenceService.storeIfNew(account, parsed, AnalysisStatus.DEFERRED);
            if (saved != null) stored++;
        }
        return stored;
    }

    @Transactional
    protected void finish(UUID mailAccountId) {
        mailAccountRepository.findById(mailAccountId).ifPresent(account -> {
            account.setBackfillFinishedAt(Instant.now());
            account.setBackfillPageToken(null);
            mailAccountRepository.save(account);
            log.info("History import finished for account id={}: {} messages",
                    mailAccountId, account.getBackfillStored());
        });
    }

    private void publish(MailAccount account, boolean done) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stored", account.getBackfillStored());
        payload.put("done", done);
        events.publish(account.getUser().getId(), RealtimeEvent.BACKFILL_PROGRESS, payload);
    }

    @PreDestroy
    void shutDown() {
        workers.shutdownNow();
    }
}
