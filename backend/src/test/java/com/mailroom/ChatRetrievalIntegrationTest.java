package com.mailroom;

import com.mailroom.entity.AnalysisStatus;
import com.mailroom.entity.EmailAnalysis;
import com.mailroom.entity.EmailCategory;
import com.mailroom.entity.EmailMessage;
import com.mailroom.entity.MailAccount;
import com.mailroom.entity.MailProvider;
import com.mailroom.entity.User;
import com.mailroom.repository.EmailAnalysisRepository;
import com.mailroom.repository.EmailRepository;
import com.mailroom.repository.EmailSpecifications;
import com.mailroom.repository.MailAccountRepository;
import com.mailroom.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding an imported email that nothing has analysed.
 *
 * <p>"Is there any mail about the academic calendar?" found nothing,
 * against a mailbox that plainly contained one. Two reasons, and both of
 * them are about a freshly imported mailbox looking different from the one
 * these searches were written for.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatRetrievalIntegrationTest {

    @Autowired private UserRepository users;
    @Autowired private MailAccountRepository accounts;
    @Autowired private EmailRepository emails;
    @Autowired private EmailAnalysisRepository analyses;

    private UUID accountId;
    private UUID calendarId;

    @BeforeEach
    void setUp() {
        User user = users.save(new User("chat-" + UUID.randomUUID() + "@example.com", "Chat Test"));
        MailAccount account = new MailAccount();
        account.setUser(user);
        account.setProvider(MailProvider.GOOGLE);
        account.setProviderEmail(user.getEmail());
        accountId = accounts.save(account).getId();

        // Imported history: stored, deliberately unanalysed, so it has an
        // analysis row with no category on it.
        calendarId = store(account, "Academic Calendar 2026-27",
                "registrar@university.edu", "Please find the academic calendar for the coming year.",
                180, AnalysisStatus.DEFERRED, null);

        // Recent, analysed mail that happens to mention the word, which is
        // what wins on date order if nothing else intervenes.
        for (int i = 0; i < 30; i++) {
            store(account, "Weekly digest " + i, "news@example.com",
                    "Add it to your calendar.", i, AnalysisStatus.COMPLETED, EmailCategory.NEWSLETTER);
        }
    }

    private UUID store(MailAccount account, String subject, String sender, String body,
                       int daysAgo, AnalysisStatus status, EmailCategory category) {
        EmailMessage email = new EmailMessage();
        email.setMailAccount(account);
        email.setProviderMessageId(UUID.randomUUID().toString());
        email.setSubject(subject);
        email.setSender(sender);
        email.setBodyText(body);
        email.setSnippet(body);
        email.setReceivedAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
        EmailMessage saved = emails.save(email);

        EmailAnalysis analysis = new EmailAnalysis();
        analysis.setEmail(saved);
        analysis.setAnalysisStatus(status);
        analysis.setCategory(category);
        analyses.save(analysis);
        return saved.getId();
    }

    private List<UUID> idsMatching(Specification<EmailMessage> spec) {
        return emails.findAll(EmailSpecifications.and(
                EmailSpecifications.forMailAccount(accountId), spec)).stream()
                .map(EmailMessage::getId)
                .toList();
    }

    @Test
    void aGuessedCategoryHidesEverythingNotYetAnalysed() {
        // The planner guesses EDUCATION for a question about a calendar.
        // Imported mail has no category, so an equality filter excludes it
        // — which is most of a mailbox somebody just imported.
        assertThat(idsMatching(EmailSpecifications.category(EmailCategory.EDUCATION)))
                .doesNotContain(calendarId);
    }

    @Test
    void theTolerantCategoryKeepsIt() {
        assertThat(idsMatching(EmailSpecifications.categoryOrUnknown(EmailCategory.EDUCATION)))
                .contains(calendarId);
    }

    @Test
    void theTolerantCategoryStillExcludesAWrongOne() {
        // Tolerating "unknown" must not mean tolerating anything: mail that
        // has been analysed and is a newsletter is not education.
        List<UUID> found = idsMatching(EmailSpecifications.categoryOrUnknown(EmailCategory.EDUCATION));
        List<UUID> newsletters = idsMatching(EmailSpecifications.category(EmailCategory.NEWSLETTER));

        assertThat(found).doesNotContainAnyElementsOf(newsletters);
    }

    @Test
    void aSubjectMatchIsFoundEvenWhenNewerMailMentionsTheWord() {
        List<String> keywords = List.of("academic", "calendar", "semester");

        // Thirty newer emails say "calendar" in the body. The one actually
        // called Academic Calendar is six months old, so on date order it
        // comes last — and the candidate list is capped.
        assertThat(idsMatching(EmailSpecifications.subjectContainsAny(keywords)))
                .containsExactly(calendarId);
    }

    @Test
    void theBodySearchAloneWouldHaveBuriedIt() {
        List<String> keywords = List.of("calendar");

        List<UUID> byAnything = idsMatching(EmailSpecifications.anyKeyword(keywords));

        // It is in there — it is simply thirty-first, which is why subject
        // matches are fetched separately rather than trusted to survive
        // the cap.
        assertThat(byAnything).contains(calendarId).hasSizeGreaterThan(30);
    }
}
