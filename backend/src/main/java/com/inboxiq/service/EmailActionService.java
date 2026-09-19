package com.inboxiq.service;

import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.gmail.GmailInboxClient;
import com.inboxiq.repository.EmailRepository;
import com.inboxiq.repository.MailAccountRepository;
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
import java.util.function.Consumer;

/**
 * The things InboxIQ can do to an email in the user's real mailbox: trash it,
 * archive it, mark it read or unread. Every one of them reaches Gmail first
 * and changes the local copy only afterwards, so the two never drift apart —
 * a message InboxIQ says it archived really is out of the Gmail inbox.
 *
 * The Gmail calls are what take the time — a fifth of a second each, times
 * however many were selected — so they go out several at a time rather than
 * one after another. The local rows are updated afterwards in one statement.
 *
 * A pass cut off half way (a restart, a dropped connection) leaves Gmail
 * changed and the local copy not. That mends itself: the next incremental
 * sync sees the same label change in Gmail's history and applies it here.
 */
@Service
public class EmailActionService {

    private static final Logger log = LoggerFactory.getLogger(EmailActionService.class);

    /** Enough to make a selection feel quick without hammering Gmail's per-user limit. */
    private static final int GMAIL_CONCURRENCY = 5;

    private final GmailInboxClient gmailInboxClient;
    private final EmailRepository emailRepository;
    private final MailAccountRepository mailAccountRepository;
    private final ExecutorService gmailPool;

    public EmailActionService(GmailInboxClient gmailInboxClient,
                              EmailRepository emailRepository,
                              MailAccountRepository mailAccountRepository) {
        this.gmailInboxClient = gmailInboxClient;
        this.emailRepository = emailRepository;
        this.mailAccountRepository = mailAccountRepository;

        AtomicInteger threadNumber = new AtomicInteger();
        this.gmailPool = Executors.newFixedThreadPool(GMAIL_CONCURRENCY, runnable -> {
            Thread thread = new Thread(runnable, "gmail-action-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * What one pass did.
     *
     * @param appliedIds    emails changed in InboxIQ
     * @param changedInGmail how many of those Gmail actually changed just now;
     *                       the rest were ones Gmail no longer had, so the
     *                       caller mustn't claim their mailbox changed
     * @param failed        emails left untouched in both places
     */
    public record Result(List<UUID> appliedIds, int changedInGmail, int failed) {}

    /** One Gmail call: true when Gmail changed something, false when it no longer had the message. */
    @FunctionalInterface
    private interface GmailCall {
        boolean run(EmailMessage email);
    }

    /** Trashes each in Gmail — reversible there for 30 days — and deletes the local copies. */
    public Result trashAndDelete(List<EmailMessage> emails) {
        return run(emails, "Delete",
                email -> gmailInboxClient.trashMessage(email.getMailAccount(), email.getProviderMessageId()),
                ids -> ids.forEach(emailRepository::deleteById));
    }

    /**
     * Takes each out of the Gmail inbox, or puts it back. Nothing is deleted
     * either way — the message keeps its summary and to-dos and simply stops
     * (or starts) appearing in the inbox list.
     */
    public Result setArchived(List<EmailMessage> emails, boolean archived) {
        return run(emails, archived ? "Archive" : "Move to inbox",
                email -> gmailInboxClient.setMessageArchived(
                        email.getMailAccount(), email.getProviderMessageId(), archived),
                ids -> emailRepository.setArchived(ids, archived));
    }

    /** Mirrors a read/unread change into Gmail, so triage here empties the real inbox too. */
    public Result setRead(List<EmailMessage> emails, boolean read) {
        return run(emails, read ? "Mark read" : "Mark unread",
                email -> gmailInboxClient.setMessageRead(email.getMailAccount(), email.getProviderMessageId(), read),
                ids -> emailRepository.setRead(ids, read));
    }

    /**
     * Opening an email marks it read here; this mirrors that into Gmail
     * without the reader waiting for a round trip to Google. The account is
     * re-read on the pool thread rather than handed across from the request,
     * since a JPA entity belongs to the session that loaded it.
     *
     * Nothing depends on this succeeding: if it doesn't, InboxIQ still shows
     * the email as read and Gmail still shows it unread — a difference the
     * user can settle either way, and one the next explicit mark-read fixes.
     */
    public void markReadInGmailQuietly(UUID mailAccountId, String providerMessageId) {
        CompletableFuture.runAsync(() -> {
            try {
                mailAccountRepository.findById(mailAccountId)
                        .filter(MailAccount::isActive)
                        .filter(account -> !account.isReauthRequired())
                        .ifPresent(account -> gmailInboxClient.setMessageRead(account, providerMessageId, true));
            } catch (RuntimeException e) {
                log.debug("Could not mark a message read in Gmail: {}", rootCause(e).toString());
            }
        }, gmailPool);
    }

    private Result run(List<EmailMessage> emails, String what, GmailCall call, Consumer<List<UUID>> applyLocally) {
        if (emails.isEmpty()) return new Result(List.of(), 0, 0);

        Map<UUID, CompletableFuture<Boolean>> inFlight = new LinkedHashMap<>();
        for (EmailMessage email : emails) {
            inFlight.put(email.getId(), CompletableFuture.supplyAsync(() -> call.run(email), gmailPool));
        }

        List<UUID> applied = new ArrayList<>(emails.size());
        int changedInGmail = 0;
        int failed = 0;
        for (Map.Entry<UUID, CompletableFuture<Boolean>> entry : inFlight.entrySet()) {
            try {
                if (Boolean.TRUE.equals(entry.getValue().join())) changedInGmail++;
                applied.add(entry.getKey());
            } catch (RuntimeException e) {
                failed++;
                log.warn("{} failed in Gmail for email id={}; leaving it as it was: {}",
                        what, entry.getKey(), rootCause(e).toString());
            }
        }

        if (!applied.isEmpty()) applyLocally.accept(applied);
        // Deliberately at INFO: "it changed here but not in my Gmail" is the
        // one complaint these lines have to be able to answer.
        log.info("{}: {} asked for, {} changed in Gmail, {} already gone from Gmail, {} refused by Gmail",
                what, emails.size(), changedInGmail, applied.size() - changedInGmail, failed);
        return new Result(applied, changedInGmail, failed);
    }

    private static Throwable rootCause(Throwable e) {
        return (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
    }

    @PreDestroy
    void shutdown() {
        gmailPool.shutdown();
    }
}
