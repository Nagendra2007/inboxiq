package com.inboxiq.service;

import com.inboxiq.entity.EmailMessage;
import com.inboxiq.gmail.GmailInboxClient;
import com.inboxiq.repository.EmailRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * Deleting emails, one or many: each is moved to Trash in the user's real
 * Gmail first and only then removed locally, so InboxIQ's copy and the real
 * inbox never drift apart.
 *
 * The Gmail calls are what take the time — a fifth of a second each, times
 * however many were selected — so they go out several at a time rather than
 * one after another. The local rows are deleted afterwards on the calling
 * thread, where they cost about a millisecond each.
 *
 * A pass cut off half way (a restart, a dropped connection) leaves messages
 * trashed in Gmail whose local copy is still here. That mends itself: the
 * next incremental sync sees the TRASH change in Gmail's history and removes
 * them.
 */
@Service
public class EmailDeletionService {

    private static final Logger log = LoggerFactory.getLogger(EmailDeletionService.class);

    /** Enough to make a selection feel quick without hammering Gmail's per-user limit. */
    private static final int TRASH_CONCURRENCY = 5;

    private final GmailInboxClient gmailInboxClient;
    private final EmailRepository emailRepository;
    private final ExecutorService trashPool;

    public EmailDeletionService(GmailInboxClient gmailInboxClient, EmailRepository emailRepository) {
        this.gmailInboxClient = gmailInboxClient;
        this.emailRepository = emailRepository;

        AtomicInteger threadNumber = new AtomicInteger();
        this.trashPool = Executors.newFixedThreadPool(TRASH_CONCURRENCY, runnable -> {
            Thread thread = new Thread(runnable, "gmail-trash-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * What one delete pass did. {@code deletedIds} are gone from InboxIQ;
     * {@code movedToTrash} of them were moved to Trash by Gmail just now and
     * the remainder were ones Gmail no longer had. {@code failed} stayed put
     * in both places.
     */
    public record Result(List<UUID> deletedIds, int movedToTrash, int failed) {}

    /**
     * Trashes each of these in Gmail and deletes the ones that went. An email
     * Gmail refuses is left alone in both places rather than disappearing
     * from InboxIQ while sitting in the inbox.
     */
    public Result trashAndDelete(List<EmailMessage> emails) {
        if (emails.isEmpty()) return new Result(List.of(), 0, 0);

        Map<UUID, CompletableFuture<Boolean>> trashing = new LinkedHashMap<>();
        for (EmailMessage email : emails) {
            trashing.put(email.getId(), CompletableFuture.supplyAsync(
                    () -> gmailInboxClient.trashMessage(email.getMailAccount(), email.getProviderMessageId()),
                    trashPool));
        }

        List<UUID> deleted = new ArrayList<>(emails.size());
        int movedToTrash = 0;
        int failed = 0;
        for (Map.Entry<UUID, CompletableFuture<Boolean>> entry : trashing.entrySet()) {
            try {
                if (Boolean.TRUE.equals(entry.getValue().join())) movedToTrash++;
                deleted.add(entry.getKey());
            } catch (RuntimeException e) {
                failed++;
                log.warn("Could not move email id={} to Gmail Trash; leaving it where it is: {}",
                        entry.getKey(), rootCause(e).toString());
            }
        }

        for (UUID id : deleted) {
            emailRepository.deleteById(id);
        }
        // Deliberately at INFO: "it vanished here but it's still in my Gmail"
        // is the one complaint this log has to be able to answer.
        log.info("Delete: {} asked for, {} moved to Gmail Trash, {} already gone from Gmail, {} refused by Gmail",
                emails.size(), movedToTrash, deleted.size() - movedToTrash, failed);
        return new Result(deleted, movedToTrash, failed);
    }

    private static Throwable rootCause(Throwable e) {
        return (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
    }

    @PreDestroy
    void shutdown() {
        trashPool.shutdown();
    }
}
