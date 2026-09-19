package com.inboxiq.service;

import com.inboxiq.config.AppProperties;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.realtime.EventStreamService;
import com.inboxiq.repository.MailAccountRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Decides when Gmail sync passes run, and makes sure they never block a
 * request: every trigger — sign-in, the app being opened, the Sync button,
 * the periodic check — just asks for a pass, which runs on a background
 * thread while the request returns immediately.
 *
 * At most one pass per mailbox runs at a time; a request made while one is
 * running is dropped (the running pass, or the next periodic check, covers
 * it). New mail is found by two periodic checks, each a single cheap Gmail
 * history call:
 *
 *  - users who currently have InboxIQ open (an open event stream) every
 *    {@code app.gmail.poll-interval-seconds}, so mail lands in front of them
 *    within seconds;
 *  - everyone else every {@code app.gmail.background-poll-minutes}, so mail
 *    is fetched and analyzed while the app is closed and is simply there at
 *    the next sign-in, instead of a catch-up that starts when they arrive.
 *
 * Background checks only happen while the server is running, so a host that
 * stops the app when nobody is using it (a free tier that sleeps when idle)
 * can only catch up once someone opens the app — see DEPLOYMENT.md.
 *
 * In-memory coordination, like the rest of the realtime layer: correct for
 * this single-instance deployment. The persistent parts (checkpoint, last
 * result) live on the mail account row.
 */
@Service
public class SyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SyncCoordinator.class);

    /** Checks for a user who has the app open are skipped if a pass finished this recently. */
    private static final Duration MIN_GAP_WHILE_OPEN = Duration.ofSeconds(10);

    private final EmailSyncService emailSyncService;
    private final MailAccountRepository mailAccountRepository;
    private final EventStreamService events;
    private final AppProperties appProperties;
    private final ThreadPoolTaskExecutor executor;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> lastFinished = new ConcurrentHashMap<>();

    public SyncCoordinator(EmailSyncService emailSyncService,
                           MailAccountRepository mailAccountRepository,
                           EventStreamService events,
                           AppProperties appProperties) {
        this.emailSyncService = emailSyncService;
        this.mailAccountRepository = mailAccountRepository;
        this.events = events;
        this.appProperties = appProperties;

        this.executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("gmail-sync-");
        // A pass cut off by shutdown is harmless: its checkpoint wasn't
        // saved, so the next pass after restart redoes it.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
    }

    /**
     * Starts a pass in the background unless one is already running for this
     * mailbox, or an automatic one ran too recently. Returns whether a new
     * pass was started.
     */
    public boolean requestSync(UUID mailAccountId, SyncTrigger trigger) {
        Duration minGap = minGapFor(trigger);
        if (minGap != null) {
            Instant finished = lastFinished.get(mailAccountId);
            if (finished != null && finished.isAfter(Instant.now().minus(minGap))) {
                return false;
            }
        }
        if (!running.add(mailAccountId)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    emailSyncService.syncMailbox(mailAccountId, trigger);
                } finally {
                    // Recorded for failed passes too, so a mailbox Gmail keeps
                    // rejecting isn't retried on every tick.
                    lastFinished.put(mailAccountId, Instant.now());
                    running.remove(mailAccountId);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            running.remove(mailAccountId);
            log.warn("Sync executor rejected a pass for account id={} (shutting down?)", mailAccountId);
            return false;
        }
    }

    /** Null for the triggers a person just asked for (sign-in, the Sync button). */
    private Duration minGapFor(SyncTrigger trigger) {
        return switch (trigger) {
            case CONNECT, POLL -> MIN_GAP_WHILE_OPEN;
            case BACKGROUND -> backgroundInterval();
            case LOGIN, MANUAL -> null;
        };
    }

    public boolean isSyncing(UUID mailAccountId) {
        return running.contains(mailAccountId);
    }

    /**
     * Checks for new mail: often for everyone who has InboxIQ open, and on a
     * slower beat for everyone who doesn't, so their inbox is already up to
     * date the next time they sign in.
     */
    @Scheduled(fixedDelayString = "#{${app.gmail.poll-interval-seconds:30} * 1000}", initialDelay = 30_000)
    public void pollMailboxes() {
        Set<UUID> openMailboxes = new HashSet<>();
        try {
            for (UUID userId : events.connectedUserIds()) {
                mailAccountRepository.findByUserIdAndProviderAndActiveTrue(userId, MailProvider.GOOGLE)
                        .filter(account -> !account.isReauthRequired())
                        .map(MailAccount::getId)
                        .ifPresent(accountId -> {
                            openMailboxes.add(accountId);
                            requestSync(accountId, SyncTrigger.POLL);
                        });
            }
        } catch (RuntimeException e) {
            log.warn("Could not schedule Gmail checks: {}", e.getMessage());
        }
        pollMailboxesOfAbsentUsers(openMailboxes);
    }

    /**
     * The mailboxes nobody is watching right now. Each check costs a single
     * history call that usually answers "nothing changed"; when something did
     * change it is fetched and analyzed there and then, so the next sign-in
     * has nothing left to do but read rows out of the database.
     */
    private void pollMailboxesOfAbsentUsers(Set<UUID> openMailboxes) {
        Duration interval = backgroundInterval();
        if (interval.isZero()) return;
        AppProperties.Gmail gmail = appProperties.getGmail();
        Instant now = Instant.now();
        try {
            List<UUID> due = mailAccountRepository.findIdsDueForBackgroundSync(
                    MailProvider.GOOGLE,
                    now.minus(interval),
                    now.minus(Duration.ofDays(Math.max(1, gmail.getBackgroundActiveDays()))),
                    PageRequest.of(0, Math.max(1, gmail.getBackgroundBatchSize())));
            for (UUID accountId : due) {
                if (openMailboxes.contains(accountId)) continue;
                requestSync(accountId, SyncTrigger.BACKGROUND);
            }
        } catch (RuntimeException e) {
            log.warn("Could not schedule background Gmail checks: {}", e.getMessage());
        }
    }

    /** Zero when background checks are switched off. */
    private Duration backgroundInterval() {
        return Duration.ofMinutes(Math.max(0, appProperties.getGmail().getBackgroundPollMinutes()));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
