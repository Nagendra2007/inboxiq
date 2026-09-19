package com.inboxiq;

import com.inboxiq.entity.AnalysisStatus;
import com.inboxiq.entity.EmailAnalysis;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.entity.Priority;
import com.inboxiq.entity.RiskLevel;
import com.inboxiq.entity.User;
import com.inboxiq.repository.EmailAnalysisRepository;
import com.inboxiq.repository.EmailRepository;
import com.inboxiq.repository.EmailSummaryRow;
import com.inboxiq.repository.MailAccountRepository;
import com.inboxiq.repository.UserRepository;
import com.inboxiq.service.DashboardService;
import com.inboxiq.service.SearchService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two reads that run on every app load: the inbox list and the sidebar
 * counts. Both were rewritten for speed — the list into one projection query
 * that leaves the message bodies in the database, the counts into three
 * aggregate queries instead of ten round trips — so these check they still
 * answer exactly what they answered before.
 */
@SpringBootTest
@ActiveProfiles("test")
class InboxReadIntegrationTest {

    @Autowired private UserRepository users;
    @Autowired private MailAccountRepository accounts;
    @Autowired private EmailRepository emails;
    @Autowired private EmailAnalysisRepository analyses;
    @Autowired private DashboardService dashboardService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private SearchService searchService;

    private UUID accountId;
    private UUID otherAccountId;

    @BeforeEach
    void setUp() {
        accountId = newAccount();
        otherAccountId = newAccount();
    }

    private UUID newAccount() {
        User user = users.save(new User("inbox-" + UUID.randomUUID() + "@example.com", "Inbox Test"));
        MailAccount account = new MailAccount();
        account.setUser(user);
        account.setProvider(MailProvider.GOOGLE);
        account.setProviderEmail(user.getEmail());
        return accounts.save(account).getId();
    }

    private EmailMessage email(UUID owner, String gmailId, Instant receivedAt, boolean read) {
        EmailMessage email = new EmailMessage();
        email.setMailAccount(accounts.findById(owner).orElseThrow());
        email.setProviderMessageId(gmailId);
        email.setSender("Sender <s@example.com>");
        email.setSubject("Subject " + gmailId);
        email.setSnippet("snippet " + gmailId);
        email.setBodyText("a long body that the inbox list has no use for");
        email.setBodyHtml("<p>a long body that the inbox list has no use for</p>");
        email.setReceivedAt(receivedAt);
        email.setRead(read);
        return emails.save(email);
    }

    private void analyze(EmailMessage email, Priority priority, RiskLevel risk, boolean requiresReply) {
        EmailAnalysis analysis = new EmailAnalysis();
        analysis.setEmail(email);
        analysis.setSummary("summary of " + email.getProviderMessageId());
        analysis.setKeyPointsJson("[\"one\",\"two\"]");
        analysis.setPriority(priority);
        analysis.setRiskLevel(risk);
        analysis.setRequiresReply(requiresReply);
        analysis.setAnalysisStatus(AnalysisStatus.COMPLETED);
        analyses.save(analysis);
    }

    @Test
    void listedSummariesAreNewestFirstAndCarryTheirAnalysis() {
        Instant now = Instant.now();
        EmailMessage oldest = email(accountId, "m1", now.minus(3, ChronoUnit.HOURS), true);
        EmailMessage middle = email(accountId, "m2", now.minus(2, ChronoUnit.HOURS), false);
        EmailMessage newest = email(accountId, "m3", now.minus(1, ChronoUnit.HOURS), false);
        analyze(newest, Priority.HIGH, RiskLevel.LOW, true);
        // Another mailbox's mail must not leak into this one.
        email(otherAccountId, "x1", now, false);

        Page<EmailSummaryRow> page = emails.findSummaries(accountId, false, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(EmailSummaryRow::id)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());

        EmailSummaryRow first = page.getContent().get(0);
        assertThat(first.read()).isFalse();
        assertThat(first.snippet()).isEqualTo("snippet m3");
        assertThat(first.analysis()).isNotNull();
        assertThat(first.analysis().getSummary()).isEqualTo("summary of m3");
        // Not yet analyzed: the row still comes back, without an analysis.
        assertThat(page.getContent().get(2).analysis()).isNull();
    }

    @Test
    void listedSummariesPaginate() {
        Instant now = Instant.now();
        for (int i = 1; i <= 5; i++) {
            email(accountId, "p" + i, now.minus(i, ChronoUnit.MINUTES), false);
        }

        Page<EmailSummaryRow> first = emails.findSummaries(accountId, false, PageRequest.of(0, 2));
        Page<EmailSummaryRow> second = emails.findSummaries(accountId, false, PageRequest.of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.isLast()).isFalse();
        assertThat(first.getContent()).extracting(EmailSummaryRow::subject).containsExactly("Subject p1", "Subject p2");
        assertThat(second.getContent()).extracting(EmailSummaryRow::subject).containsExactly("Subject p3", "Subject p4");
    }

    /**
     * The point of the projection. Reading the page as entities asked the
     * database once for the emails and then once more per row for its
     * analysis — the inverse side of a one-to-one can't be proxied — so a
     * 40-row inbox cost 41 round trips.
     */
    @Test
    void awholePageOfSummariesCostsOnlyAQueryOrTwo() {
        Instant now = Instant.now();
        for (int i = 1; i <= 20; i++) {
            analyze(email(accountId, "q" + i, now.minus(i, ChronoUnit.MINUTES), false),
                    Priority.MEDIUM, RiskLevel.LOW, false);
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        Page<EmailSummaryRow> page = emails.findSummaries(accountId, false, PageRequest.of(0, 40));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allSatisfy(row -> assertThat(row.analysis()).isNotNull());
        // The page itself and its total. (Generous, so a scheduled job that
        // happens to run alongside can't make this flaky — the behaviour it
        // guards against would be 21.)
        assertThat(statistics.getPrepareStatementCount()).isLessThan(8);
    }

    /** Filtered views (the category tabs, "unread only") go through search instead. */
    @Test
    void searchFiltersOnTheAnalysisAndBringsItBackInTheSameQuery() {
        Instant now = Instant.now();
        for (int i = 1; i <= 10; i++) {
            analyze(email(accountId, "f" + i, now.minus(i, ChronoUnit.MINUTES), false),
                    i <= 6 ? Priority.HIGH : Priority.LOW, RiskLevel.LOW, false);
        }
        analyze(email(accountId, "read", now, true), Priority.HIGH, RiskLevel.LOW, false);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        var criteria = new SearchService.SearchCriteria(null, null, null, Priority.HIGH, null, true, false, null, null);
        Page<EmailMessage> page = searchService.search(
                accountId, criteria, PageRequest.of(0, 40, Sort.by("receivedAt").descending()));

        assertThat(page.getContent()).extracting(EmailMessage::getProviderMessageId)
                .containsExactly("f1", "f2", "f3", "f4", "f5", "f6");
        assertThat(page.getContent().get(0).getAnalysis().getPriority()).isEqualTo(Priority.HIGH);
        // Without the fetch graph this was one query per result row.
        assertThat(statistics.getPrepareStatementCount()).isLessThan(4);
    }

    @Test
    void dashboardCountsCoverOnlyTheOwnMailbox() {
        Instant now = Instant.now();
        EmailMessage high = email(accountId, "d1", now.minus(1, ChronoUnit.HOURS), false);
        EmailMessage medium = email(accountId, "d2", now.minus(2, ChronoUnit.DAYS), false);
        EmailMessage low = email(accountId, "d3", now.minus(30, ChronoUnit.DAYS), true);
        analyze(high, Priority.HIGH, RiskLevel.HIGH, true);
        analyze(medium, Priority.MEDIUM, RiskLevel.MEDIUM, false);
        analyze(low, Priority.LOW, RiskLevel.LOW, true);

        EmailMessage otherHigh = email(otherAccountId, "x2", now, false);
        analyze(otherHigh, Priority.HIGH, RiskLevel.HIGH, true);

        DashboardService.DashboardStats stats = dashboardService.statsFor(accountId);

        assertThat(stats.totalEmails()).isEqualTo(3);
        assertThat(stats.unreadEmails()).isEqualTo(2);
        assertThat(stats.receivedLast7Days()).isEqualTo(2);
        assertThat(stats.highPriority()).isEqualTo(1);
        assertThat(stats.mediumPriority()).isEqualTo(1);
        assertThat(stats.lowPriority()).isEqualTo(1);
        assertThat(stats.highRisk()).isEqualTo(1);
        assertThat(stats.mediumRisk()).isEqualTo(1);
        assertThat(stats.awaitingReply()).isEqualTo(2);
        assertThat(stats.openActionItems()).isZero();
    }

    @Test
    void dashboardCountsAreAllZeroForAnEmptyMailbox() {
        DashboardService.DashboardStats stats = dashboardService.statsFor(accountId);

        assertThat(stats.totalEmails()).isZero();
        assertThat(stats.unreadEmails()).isZero();
        assertThat(stats.receivedLast7Days()).isZero();
        assertThat(stats.highPriority()).isZero();
        assertThat(stats.awaitingReply()).isZero();
    }
}
