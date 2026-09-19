package com.inboxiq;

import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.entity.User;
import com.inboxiq.repository.MailAccountRepository;
import com.inboxiq.repository.UserRepository;
import com.inboxiq.service.EmailSyncService;
import com.inboxiq.service.SyncCoordinator;
import com.inboxiq.service.SyncTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Mail keeps arriving while nobody is watching. The periodic check used to
 * cover only users with the app open, which is why a returning user met a
 * catch-up sync instead of an inbox that was already up to date.
 */
@SpringBootTest(properties = {
        // The test profile turns background checks off so other tests can drive
        // sync by hand; this is the one class that wants them on.
        "app.gmail.background-poll-minutes=5",
        "app.gmail.background-active-days=30"
})
@ActiveProfiles("test")
class BackgroundSyncIntegrationTest {

    @MockBean private EmailSyncService emailSyncService;

    @Autowired private SyncCoordinator coordinator;
    @Autowired private UserRepository users;
    @Autowired private MailAccountRepository accounts;

    private MailAccount newAccount() {
        User user = users.save(new User("away-" + UUID.randomUUID() + "@example.com", "Away Test"));
        MailAccount account = new MailAccount();
        account.setUser(user);
        account.setProvider(MailProvider.GOOGLE);
        account.setProviderEmail(user.getEmail());
        return accounts.save(account);
    }

    @Test
    void aMailboxNobodyIsWatchingIsStillChecked() {
        MailAccount account = newAccount();
        account.setLastHistoryId("1000");
        account.setLastSyncAt(Instant.now().minus(1, ChronoUnit.HOURS));
        accounts.save(account);

        coordinator.pollMailboxes();

        verify(emailSyncService, timeout(5_000)).syncMailbox(account.getId(), SyncTrigger.BACKGROUND);
    }

    @Test
    void aMailboxCheckedAMomentAgoIsLeftAlone() {
        MailAccount account = newAccount();
        account.setLastHistoryId("1000");
        account.setLastSyncAt(Instant.now().minus(30, ChronoUnit.SECONDS));
        accounts.save(account);

        coordinator.pollMailboxes();

        verify(emailSyncService, never()).syncMailbox(eq(account.getId()), any());
    }

    @Test
    void aMailboxWaitingToBeReconnectedIsLeftAlone() {
        MailAccount account = newAccount();
        account.setLastHistoryId("1000");
        account.setLastSyncAt(Instant.now().minus(1, ChronoUnit.HOURS));
        account.setReauthRequired(true);
        accounts.save(account);

        coordinator.pollMailboxes();

        verify(emailSyncService, never()).syncMailbox(eq(account.getId()), any());
    }

    @Test
    void aMailboxNobodyHasOpenedInMonthsStopsBeingChecked() {
        MailAccount abandoned = newAccount();
        abandoned.setLastHistoryId("1000");
        abandoned.setLastSyncAt(Instant.now().minus(90, ChronoUnit.DAYS));
        accounts.save(abandoned);

        assertThat(dueNow()).doesNotContain(abandoned.getId());

        // Signing in syncs the mailbox, which revives the background checks.
        abandoned.setLastSyncAt(Instant.now().minus(1, ChronoUnit.HOURS));
        accounts.save(abandoned);

        assertThat(dueNow()).contains(abandoned.getId());
    }

    @Test
    void aDisconnectedMailboxIsNotChecked() {
        MailAccount account = newAccount();
        account.setLastSyncAt(Instant.now().minus(1, ChronoUnit.HOURS));
        account.setActive(false);
        accounts.save(account);

        assertThat(dueNow()).doesNotContain(account.getId());
    }

    private java.util.List<UUID> dueNow() {
        Instant now = Instant.now();
        return accounts.findIdsDueForBackgroundSync(
                MailProvider.GOOGLE,
                now.minus(5, ChronoUnit.MINUTES),
                now.minus(30, ChronoUnit.DAYS),
                PageRequest.of(0, 50));
    }
}
