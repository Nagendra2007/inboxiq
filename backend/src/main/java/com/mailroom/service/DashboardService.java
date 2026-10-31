package com.mailroom.service;

import com.mailroom.repository.ActionItemRepository;
import com.mailroom.repository.AnalysisCounts;
import com.mailroom.repository.EmailAnalysisRepository;
import com.mailroom.repository.EmailCounts;
import com.mailroom.repository.EmailRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Aggregation queries backing {@code GET /api/dashboard}. Deliberately just
 * counts — no email bodies or AI content pass through here — so this
 * endpoint stays cheap to call on every dashboard load. The ten figures are
 * read in three queries rather than ten: the sidebar badges refresh this on
 * every app load and after every burst of new mail, and on a small instance
 * talking to a hosted database, round trips are most of the cost.
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
        EmailCounts emails = emailRepository.countsFor(mailAccountId, sevenDaysAgo);
        AnalysisCounts analyses = emailAnalysisRepository.countsFor(mailAccountId);
        return new DashboardStats(
                emails.total(),
                emails.unread(),
                emails.receivedSince(),
                analyses.highPriority(),
                analyses.mediumPriority(),
                analyses.lowPriority(),
                analyses.highRisk(),
                analyses.mediumRisk(),
                analyses.awaitingReply(),
                actionItemRepository.countByEmail_MailAccountIdAndCompletedFalse(mailAccountId)
        );
    }
}
