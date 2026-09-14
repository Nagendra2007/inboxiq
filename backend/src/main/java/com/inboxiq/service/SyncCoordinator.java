package com.inboxiq.service;

import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.realtime.EventStreamService;
import com.inboxiq.repository.MailAccountRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
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
 * it). New mail is detected by checking the mailboxes of users who currently
 * have InboxIQ open (an open event stream) every
 * {@code app.gmail.poll-interval-seconds}; each check is a single cheap
 * Gmail history call. Nobody's mailbox is polled while they're away — the
 * next time they open the app, one incremental pass catches up.
 *
 * In-memory coordination, like the rest of the realtime layer: correct for
 * this single-instance deployment. The persistent parts (checkpoint, last
 * result) live on the mail account row.
 */
@Service
public class SyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SyncCoordinator.class);

    /** Automatic triggers (CONNECT, POLL) are skipped if a pass finished this recently. */
    private static final Duration MIN_GAP_FOR_AUTOMATIC = Duration.ofSeconds(10);

    private final EmailSyncService emailSyncService;
    private final MailAccountRepository mailAccountRepository;
    private final EventStreamService events;
    private final ThreadPoolTaskExecutor executor;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> lastFinished = new ConcurrentHashMap<>();

    public SyncCoordinator(EmailSyncService emailSyncService,
                           MailAccountRepository mailAccountRepository,
                           EventStreamService events) {
        this.emailSyncService = emailSyncService;
        this.mailAccountRepository = mailAccountRepository;
        this.events = events;

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
     * mailbox. Returns whether a new pass was started.
     */
    public boolean requestSync(UUID mailAccountId, SyncTrigger trigger) {
        if (trigger == SyncTrigger.CONNECT || trigger == SyncTrigger.POLL) {
            Instant finished = lastFinished.get(mailAccountId);
            if (finished != null && finished.isAfter(Instant.now().minus(MIN_GAP_FOR_AUTOMATIC))) {
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

    public boolean isSyncing(UUID mailAccountId) {
        return running.contains(mailAccountId);
    }

    /** Checks for new mail for everyone who has InboxIQ open. */
    @Scheduled(fixedDelayString = "#{${app.gmail.poll-interval-seconds:30} * 1000}", initialDelay = 30_000)
    public void pollOpenMailboxes() {
        try {
            for (UUID userId : events.connectedUserIds()) {
                mailAccountRepository.findByUserIdAndProviderAndActiveTrue(userId, MailProvider.GOOGLE)
                        .filter(account -> !account.isReauthRequired())
                        .map(MailAccount::getId)
                        .ifPresent(accountId -> requestSync(accountId, SyncTrigger.POLL));
            }
        } catch (RuntimeException e) {
            log.warn("Could not schedule Gmail checks: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
