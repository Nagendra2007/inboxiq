package com.inboxiq.service;

import com.inboxiq.config.AppProperties;
import com.inboxiq.entity.AnalysisStatus;
import com.inboxiq.entity.EmailAnalysis;
import com.inboxiq.mapper.EmailMapper;
import com.inboxiq.realtime.EventStreamService;
import com.inboxiq.realtime.RealtimeEvent;
import com.inboxiq.repository.AnalysisTarget;
import com.inboxiq.repository.EmailAnalysisRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs AI analysis in the background, never on a request or sync thread:
 *
 *  1. A new email is stored with a PENDING analysis and shown right away
 *     ("Analyzing…").
 *  2. It is queued here; a small, fixed number of analyses run at once, so
 *     20 new emails don't hit the AI provider's rate limit in one burst.
 *  3. When one finishes, {@code email.analysis.completed} (or
 *     {@code .failed}, carrying the rule-based fallback) goes to that user's
 *     open tabs, which update just that email.
 *
 * An email is never analyzed twice at the same time, and an email whose
 * analysis already COMPLETED is not re-analyzed (Gmail message content never
 * changes; "Run again" in the UI is the explicit way to redo one). PENDING
 * rows survive restarts, so work lost from the in-memory queue is picked up
 * again by {@link #resumeUnfinished()}, which also gives recently failed
 * analyses a bounded number of automatic retries.
 */
@Service
public class AnalysisQueue {

    private static final Logger log = LoggerFactory.getLogger(AnalysisQueue.class);

    private static final int RESUME_BATCH = 50;
    private static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    /** Only emails synced this recently get automatic retries; older ones wait for a manual "Run again". */
    private static final Duration RETRY_WINDOW = Duration.ofHours(24);

    private final EmailAnalysisService emailAnalysisService;
    private final EmailAnalysisRepository emailAnalysisRepository;
    private final EventStreamService events;
    private final EmailMapper emailMapper;
    private final AppProperties appProperties;
    private final ThreadPoolTaskExecutor executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public AnalysisQueue(EmailAnalysisService emailAnalysisService,
                         EmailAnalysisRepository emailAnalysisRepository,
                         EventStreamService events,
                         EmailMapper emailMapper,
                         AppProperties appProperties) {
        this.emailAnalysisService = emailAnalysisService;
        this.emailAnalysisRepository = emailAnalysisRepository;
        this.events = events;
        this.emailMapper = emailMapper;
        this.appProperties = appProperties;

        int threads = Math.max(1, appProperties.getAi().getAnalysisConcurrency());
        this.executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setThreadNamePrefix("ai-analysis-");
        // Queued work is not lost on shutdown: it is still PENDING in the
        // database and resumeUnfinished() re-queues it after the restart.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
    }

    /** Queues an email for analysis. Safe to call repeatedly; duplicates are ignored. */
    public void enqueue(UUID emailId, UUID userId) {
        if (!inFlight.add(emailId)) return;
        try {
            executor.execute(() -> run(emailId, userId));
        } catch (RejectedExecutionException e) {
            inFlight.remove(emailId);
            log.warn("Analysis queue rejected email id={} (shutting down?)", emailId);
        }
    }

    /** Queues once the current transaction commits, so the worker can see the row. */
    public void enqueueAfterCommit(UUID emailId, UUID userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(emailId, userId);
                }
            });
        } else {
            enqueue(emailId, userId);
        }
    }

    public boolean isQueued(UUID emailId) {
        return inFlight.contains(emailId);
    }

    private void run(UUID emailId, UUID userId) {
        try {
            Optional<EmailAnalysis> existing = emailAnalysisRepository.findByEmailId(emailId);
            if (existing.isPresent() && existing.get().getAnalysisStatus() == AnalysisStatus.COMPLETED) {
                return; // already done; content can't have changed
            }
            events.publish(userId, RealtimeEvent.ANALYSIS_STARTED, Map.of("emailId", emailId));

            Optional<EmailAnalysis> result = emailAnalysisService.analyzeStored(emailId);
            if (result.isEmpty()) return; // deleted meanwhile

            EmailAnalysis analysis = result.get();
            boolean ok = analysis.getAnalysisStatus() == AnalysisStatus.COMPLETED;
            events.publish(userId, ok ? RealtimeEvent.ANALYSIS_COMPLETED : RealtimeEvent.ANALYSIS_FAILED,
                    payload(emailId, analysis));
        } catch (RuntimeException e) {
            log.error("Background analysis crashed for email id={}", emailId, e);
            EmailAnalysis failed = emailAnalysisService.recordUnexpectedFailure(emailId);
            events.publish(userId, RealtimeEvent.ANALYSIS_FAILED, payload(emailId, failed));
        } finally {
            inFlight.remove(emailId);
        }
    }

    private Map<String, Object> payload(UUID emailId, EmailAnalysis analysis) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("emailId", emailId);
        payload.put("analysis", emailMapper.toAnalysisDto(analysis));
        return payload;
    }

    /**
     * Picks up analyses the in-memory queue lost (a restart mid-analysis)
     * and retries recent failures a bounded number of times.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void resumeUnfinished() {
        try {
            PageRequest batch = PageRequest.of(0, RESUME_BATCH);
            for (AnalysisTarget target : emailAnalysisRepository.findPendingTargets(batch)) {
                enqueue(target.emailId(), target.userId());
            }
            Instant now = Instant.now();
            for (AnalysisTarget target : emailAnalysisRepository.findRetryableFailedTargets(
                    appProperties.getAi().getAnalysisMaxAttempts(), now.minus(RETRY_AFTER), now.minus(RETRY_WINDOW), batch)) {
                enqueue(target.emailId(), target.userId());
            }
        } catch (RuntimeException e) {
            log.warn("Could not check for unfinished analyses: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
