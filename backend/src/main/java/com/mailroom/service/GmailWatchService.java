package com.mailroom.service;

import com.mailroom.config.AppProperties;
import com.mailroom.entity.MailAccount;
import com.mailroom.entity.MailProvider;
import com.mailroom.gmail.GmailClientFactory;
import com.mailroom.gmail.GmailInboxClient;
import com.mailroom.repository.MailAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Keeps each connected mailbox registered for Gmail push notifications.
 *
 * <p>Without this the app learns about new mail by asking, every ten seconds,
 * whether anything changed — and hears "no" almost every time. With it, Gmail
 * publishes to a Pub/Sub topic the moment a message lands, Google posts that
 * to {@code /gmail/push}, and a sync starts within a second or two.
 *
 * <p>The failure mode worth designing against is that a {@code users.watch()}
 * expires after seven days and Gmail then stops publishing <em>silently</em>:
 * no error, no final notification. Nothing breaks loudly; mail just drifts
 * back to arriving on the poll interval, which is easy to miss because it
 * still works. So the expiry is stored per account and renewed early, and the
 * poll stays in place as the net underneath.
 *
 * <p>Disabled entirely when no topic is configured, which is how the app runs
 * locally and in tests.
 */
@Service
public class GmailWatchService {

    private static final Logger log = LoggerFactory.getLogger(GmailWatchService.class);

    /** Renew this far ahead of expiry, so a failed attempt has room to retry. */
    private static final Duration RENEW_BEFORE = Duration.ofDays(2);

    private final MailAccountRepository mailAccountRepository;
    private final GmailClientFactory clientFactory;
    private final GmailInboxClient gmail;
    private final AppProperties appProperties;

    public GmailWatchService(MailAccountRepository mailAccountRepository,
                             GmailClientFactory clientFactory,
                             GmailInboxClient gmail,
                             AppProperties appProperties) {
        this.mailAccountRepository = mailAccountRepository;
        this.clientFactory = clientFactory;
        this.gmail = gmail;
        this.appProperties = appProperties;
    }

    public boolean isEnabled() {
        String topic = appProperties.getGmail().getPushTopic();
        return topic != null && !topic.isBlank();
    }

    /**
     * Registers or renews the watch for one mailbox unless the current one is
     * still good for a while. Safe to call often — it is a field comparison
     * until a call is actually due.
     */
    @Transactional
    public void ensureWatch(UUID mailAccountId) {
        if (!isEnabled()) return;
        mailAccountRepository.findById(mailAccountId).ifPresent(account -> {
            if (!account.isActive() || account.isReauthRequired()) return;
            Instant expiry = account.getGmailWatchExpiration();
            if (expiry != null && expiry.isAfter(Instant.now().plus(RENEW_BEFORE))) return;
            register(account);
        });
    }

    /**
     * Renews every mailbox that is within {@link #RENEW_BEFORE} of lapsing.
     * Hourly rather than daily: a watch is seven days, so an hourly beat gives
     * roughly forty-eight attempts inside the renewal window, and a stretch of
     * Gmail API trouble has to last two days to actually drop a mailbox.
     */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void renewExpiringWatches() {
        if (!isEnabled()) return;
        Instant cutoff = Instant.now().plus(RENEW_BEFORE);
        List<MailAccount> due = mailAccountRepository
                .findWatchesDueForRenewal(MailProvider.GOOGLE, cutoff);
        if (due.isEmpty()) return;
        int ok = 0;
        for (MailAccount account : due) {
            if (register(account)) ok++;
        }
        log.info("Gmail watch renewal: {} due, {} registered", due.size(), ok);
    }

    private boolean register(MailAccount account) {
        try {
            Instant expiration = gmail.watchInbox(clientFactory.forAccount(account),
                    appProperties.getGmail().getPushTopic());
            account.setGmailWatchExpiration(expiration);
            mailAccountRepository.save(account);
            log.info("Gmail watch registered for account id={}, expires {}", account.getId(), expiration);
            return true;
        } catch (RuntimeException e) {
            // Never fatal: the poll still finds the mail, just later. Leaving
            // the old expiry in place means the next run tries again.
            log.warn("Gmail watch registration failed for account id={}: {}",
                    account.getId(), e.getMessage());
            return false;
        }
    }
}
