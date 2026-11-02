package com.mailroom.service;

import com.mailroom.entity.EmailAnalysis;
import com.mailroom.entity.EmailMessage;
import com.mailroom.entity.Priority;
import com.mailroom.entity.RiskLevel;
import com.mailroom.repository.EmailRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Decides which emails are worth a phone buzzing, and phrases them.
 *
 * <p>The decision matters more than the delivery. An app that notifies on
 * every email is a notification the user turns off in a week, and then it
 * notifies about nothing — so the bar is deliberately high: only mail the
 * analysis marked high priority, or flagged as a likely scam. Everything
 * else is waiting in the inbox when they look, which is the whole point of
 * the product.
 *
 * <p>This runs after analysis rather than after sync, because before the
 * analysis exists there is no way to know whether an email deserves an
 * interruption.
 */
@Service
public class MailNotificationService {

    private final PushNotificationService push;
    private final EmailRepository emails;

    public MailNotificationService(PushNotificationService push, EmailRepository emails) {
        this.push = push;
        this.emails = emails;
    }

    @Transactional(readOnly = true)
    public void notifyIfNoteworthy(UUID userId, UUID emailId) {
        if (!push.isEnabled() || userId == null) return;

        EmailMessage email = emails.findById(emailId).orElse(null);
        if (email == null || email.isRead() || email.isArchived()) return;

        EmailAnalysis analysis = email.getAnalysis();
        if (analysis == null) return;

        boolean risky = analysis.getRiskLevel() == RiskLevel.HIGH;
        boolean urgent = analysis.getPriority() == Priority.HIGH;
        if (!risky && !urgent) return;

        String from = senderName(email.getSender());
        String title = risky ? "Possible scam: " + from : from;
        String body = analysis.getSummary() != null && !analysis.getSummary().isBlank()
                ? analysis.getSummary()
                : (email.getSubject() == null || email.getSubject().isBlank()
                        ? "(no subject)" : email.getSubject());

        // Tagging by email id means a re-analysis replaces the earlier
        // notification instead of showing the same mail twice.
        push.notifyUser(userId, title, body, "/inbox?email=" + emailId, "email-" + emailId);
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
