package com.inboxiq.service;

import com.inboxiq.entity.Priority;
import com.inboxiq.entity.RiskLevel;
import com.inboxiq.repository.ActionItemRepository;
import com.inboxiq.repository.EmailAnalysisRepository;
import com.inboxiq.repository.EmailRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Aggregation queries backing {@code GET /api/dashboard}. Deliberately just
 * counts — no email bodies or AI content pass through here — so this
 * endpoint stays cheap to call on every dashboard load.
 */
@Service
public class DashboardService {

    private final EmailRepository emailRepository;
    private final EmailAnalysisRepository emailAnalysisRepository;
    private final ActionItemRepository actionItemRepository;

    public DashboardService(EmailRepository emailRepository,
                             EmailAnalysisRepository emailAnalysisRepository,
                             ActionItemRepository actionItemRepository) {
        this.emailRepository = emailRepository;
        this.emailAnalysisRepository = emailAnalysisRepository;
        this.actionItemRepository = actionItemRepository;
    }

    public record DashboardStats(
            long totalEmails,
            long unreadEmails,
            long receivedLast7Days,
            long highPriority,
            long mediumPriority,
            long lowPriority,
            long highRisk,
            long mediumRisk,
            long awaitingReply,
            long openActionItems
    ) {}

    @Transactional(readOnly = true)
    public DashboardStats statsFor(UUID mailAccountId) {
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        return new DashboardStats(
                emailRepository.countByMailAccountId(mailAccountId),
                emailRepository.countByMailAccountIdAndReadFalse(mailAccountId),
                emailRepository.countByMailAccountIdAndReceivedAtAfter(mailAccountId, sevenDaysAgo),
                emailAnalysisRepository.countByEmail_MailAccountIdAndPriority(mailAccountId, Priority.HIGH),
                emailAnalysisRepository.countByEmail_MailAccountIdAndPriority(mailAccountId, Priority.MEDIUM),
                emailAnalysisRepository.countByEmail_MailAccountIdAndPriority(mailAccountId, Priority.LOW),
                emailAnalysisRepository.countByEmail_MailAccountIdAndRiskLevel(mailAccountId, RiskLevel.HIGH),
                emailAnalysisRepository.countByEmail_MailAccountIdAndRiskLevel(mailAccountId, RiskLevel.MEDIUM),
                emailAnalysisRepository.countByEmail_MailAccountIdAndRequiresReplyTrue(mailAccountId),
                actionItemRepository.countByEmail_MailAccountIdAndCompletedFalse(mailAccountId)
        );
    }
}
