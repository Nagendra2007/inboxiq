package com.mailroom.service;

import com.mailroom.entity.EmailAnalysis;
import com.mailroom.entity.EmailMessage;
import com.mailroom.entity.RiskLevel;
import com.mailroom.repository.EmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Notifies about new mail, and decides what the notification says.
 *
 * <p>Every unread email notifies. What stops that from being noise is not
 * withholding the notification but what it contains: the analysis writes a
 * line for the lock screen — what the email actually wants, in a dozen
 * words — so the decision of whether to pick the phone up is made by
 * reading, not by guessing from a sender and a subject line.
 *
 * <p>This runs after analysis rather than after sync, because until the
 * analysis exists there is nothing worth saying.
 */
@Service
public class MailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(MailNotificationService.class);

    private final PushNotificationService push;
    private final DeviceNotificationService devices;
    private final EmailRepository emails;

    public MailNotificationService(PushNotificationService push,
                                   DeviceNotificationService devices,
                                   EmailRepository emails) {
        this.push = push;
        this.devices = devices;
        this.emails = emails;
    }

    @Transactional(readOnly = true)
    public void notifyIfNoteworthy(UUID userId, UUID emailId) {
        // Browsers and the Android app are separate channels, and a person
        // may have one, both or neither. The decision below is shared; only
        // the delivery differs.
        // Every path out of this method is silent by nature — a
        // notification that is never sent looks exactly like one that was
        // sent and not delivered. Saying which branch was taken is the
        // difference between diagnosing that in a minute and in a day.
        if (userId == null || (!push.isEnabled() && !devices.isEnabled())) {
            log.info("No notification for email id={}: no channel is configured", emailId);
            return;
        }

        EmailMessage email = emails.findById(emailId).orElse(null);
        if (email == null || email.isRead() || email.isArchived()) {
            log.info("No notification for email id={}: already read, archived or gone", emailId);
            return;
        }

        EmailAnalysis analysis = email.getAnalysis();
        if (analysis == null) {
            log.info("No notification for email id={}: it has no analysis yet", emailId);
            return;
        }

        String otp = analysis.getOtpCode();
        boolean risky = analysis.getRiskLevel() == RiskLevel.HIGH;

        String from = senderName(email.getSender());
        String url = "/inbox?email=" + emailId;

        if (otp != null) {
            // The code is the message. Anyone reading this wants to type it
            // into something and get on with their day, so it goes in the
            // title where a phone shows it largest, and the sender — which
            // is context, not the point — goes underneath.
            log.info("Notifying about a one-time code in email id={}", emailId);
            attempt("browser", () -> push.notifyUser(userId, otp, "Code from " + from, url, "otp-" + emailId));
            attempt("app", () -> devices.notifyOtp(userId, otp, from, url));
            return;
        }

        String body = notificationBody(analysis, email.getSubject());

        // Tagging by email id means a re-analysis replaces the earlier
        // notification instead of showing the same mail twice.
        String title = risky ? "Possible scam: " + from : from;
        log.info("Notifying about email id={} (browser={}, app={})",
                emailId, push.isEnabled(), devices.isEnabled());
        attempt("browser", () -> push.notifyUser(userId, title, body, url, "email-" + emailId));
        attempt("app", () -> devices.notifyUser(userId, title, body, url));
    }

    /**
     * Runs one channel's delivery, and keeps its failure to itself.
     *
     * <p>These were called one after the other, with the only handler
     * around both of them in the caller. A browser subscription that threw
     * on its way out therefore took the phone's notification with it —
     * silently, since the caller logs the failure as one notification that
     * did not go out rather than two. The whole point of having two
     * channels is that they fail separately.
     */
    private void attempt(String channel, Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException e) {
            log.warn("The {} notification failed: {}", channel, e.toString());
        }
    }

    /**
     * What the notification says, in descending order of how well it was
     * written for the job: the analysis's own notification line, then the
     * summary, then the subject. Each fallback is a step further from
     * something a person can act on at a glance, which is why there are
     * three of them rather than one.
     */
    private static String notificationBody(EmailAnalysis analysis, String subject) {
        if (isUseful(analysis.getNotificationText())) return analysis.getNotificationText().trim();
        if (isUseful(analysis.getSummary())) return analysis.getSummary().trim();
        return isUseful(subject) ? subject.trim() : "(no subject)";
    }

    private static boolean isUseful(String text) {
        return text != null && !text.isBlank();
    }

    /** {@code "Priya Shah" <priya@x.io>} down to what a notification has room for. */
    private static String senderName(String sender) {
        if (sender == null || sender.isBlank()) return "New mail";
        String trimmed = sender.trim();
        int angle = trimmed.indexOf('<');
        if (angle > 0) {
            String name = trimmed.substring(0, angle).trim().replaceAll("^\"|\"$", "").trim();
            if (!name.isEmpty()) return name;
            trimmed = trimmed.substring(angle + 1).replace(">", "").trim();
        }
        return trimmed.isEmpty() ? "New mail" : trimmed;
    }
}
