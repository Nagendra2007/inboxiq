package com.inboxiq;

import com.inboxiq.entity.AnalysisStatus;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.entity.User;
import com.inboxiq.exception.GmailIntegrationException;
import com.inboxiq.gmail.GmailHistoryPage;
import com.inboxiq.gmail.GmailHistoryPage.Change;
import com.inboxiq.gmail.GmailHistoryPage.Type;
import com.inboxiq.gmail.GmailInboxClient;
import com.inboxiq.gmail.ParsedGmailMessage;
import com.inboxiq.repository.EmailAnalysisRepository;
import com.inboxiq.repository.EmailRepository;
import com.inboxiq.repository.MailAccountRepository;
import com.inboxiq.repository.UserRepository;
import com.inboxiq.service.AnalysisQueue;
import com.inboxiq.service.EmailSyncService;
import com.inboxiq.service.SyncTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sync contract, against a fake Gmail: a first sync stores only the
 * newest 20 messages however large the mailbox is, and every later sync —
 * a returning user signing in, the periodic check, the Sync button — applies
 * only what changed since the saved checkpoint, never re-listing or
 * re-downloading the mailbox.
 */
@SpringBootTest
@ActiveProfiles("test")
class EmailSyncIntegrationTest {

    /** Pretend the mailbox holds 500 messages, m500 newest. */
    private static final List<String> MAILBOX_NEWEST_FIRST =
            IntStream.rangeClosed(1, 500).mapToObj(i -> "m" + (501 - i)).toList();

    @MockBean private GmailInboxClient gmail;
    @MockBean private AnalysisQueue analysisQueue;

    @Autowired private EmailSyncService syncService;
    @Autowired private UserRepository users;
    @Autowired private MailAccountRepository accounts;
    @Autowired private EmailRepository emails;
    @Autowired private EmailAnalysisRepository analyses;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = users.save(new User("sync-" + UUID.randomUUID() + "@example.com", "Sync Test"));
        MailAccount account = new MailAccount();
        account.setUser(user);
        account.setProvider(MailProvider.GOOGLE);
        account.setProviderEmail(user.getEmail());
        accountId = accounts.save(account).getId();

        when(gmail.currentHistoryId(any())).thenReturn("1000");
        when(gmail.latestInboxMessageIds(any(), anyInt()))
                .thenAnswer(inv -> MAILBOX_NEWEST_FIRST.subList(0, inv.getArgument(1, Integer.class)));
        when(gmail.getMessageIfPresent(any(), anyString())).thenAnswer(inv -> message(inv.getArgument(1)));
    }

    private static ParsedGmailMessage message(String id) {
        int n = Integer.parseInt(id.substring(1));
        return new ParsedGmailMessage(id, "t-" + id, "Sender <s@example.com>", "me@example.com", null,
                "Subject " + id, "snippet", "body " + id, null, Instant.ofEpochSecond(1_700_000_000L + n), true, List.of());
    }

    private static GmailHistoryPage history(String newHistoryId, Change... changes) {
        return new GmailHistoryPage(List.of(changes), null, newHistoryId);
    }

    private MailAccount account() {
        return accounts.findById(accountId).orElseThrow();
    }

    private EmailMessage stored(String gmailId) {
        return emails.findByMailAccountIdAndProviderMessageId(accountId, gmailId).orElse(null);
    }

    @Test
    void firstSyncStoresOnlyTheNewest20AndSavesACheckpoint() {
        EmailSyncService.SyncOutcome outcome = syncService.syncMailbox(accountId, SyncTrigger.LOGIN);

        assertThat(outcome.mode()).isEqualTo(EmailSyncService.Mode.INITIAL);
        assertThat(outcome.newEmails()).isEqualTo(20);
        assertThat(emails.countByMailAccountId(accountId)).isEqualTo(20);
        assertThat(stored("m500")).isNotNull();
        assertThat(stored("m481")).isNotNull();
        assertThat(stored("m480")).isNull();

        verify(gmail, times(1)).latestInboxMessageIds(any(), eq(20));
        verify(gmail, times(20)).getMessageIfPresent(any(), anyString());
        verify(gmail, never()).listHistory(any(), anyString(), any());

        MailAccount account = account();
        assertThat(account.getLastHistoryId()).isEqualTo("1000");
        assertThat(account.getInitialSyncCompletedAt()).isNotNull();
        assertThat(account.getLastSyncAt()).isNotNull();

        // Shown at once as "Analyzing…", analysis queued in the background.
        EmailMessage newest = stored("m500");
        assertThat(analyses.findByEmailId(newest.getId()).orElseThrow().getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        verify(analysisQueue, times(20)).enqueue(any(), any());
    }

    @Test
    void laterSyncsApplyOnlyChangesSinceTheCheckpoint() {
        syncService.syncMailbox(accountId, SyncTrigger.LOGIN);
        clearInvocations(gmail, analysisQueue);

        when(gmail.listHistory(any(), eq("1000"), isNull())).thenReturn(history("1010",
                new Change(Type.MESSAGE_ADDED, "m501", List.of("INBOX", "UNREAD")),
                new Change(Type.LABELS_REMOVED, "m499", List.of("UNREAD")),
                new Change(Type.LABELS_ADDED, "m498", List.of("TRASH"))));

        // A returning user signs in: must not touch the 480 older messages.
        EmailSyncService.SyncOutcome outcome = syncService.syncMailbox(accountId, SyncTrigger.LOGIN);

        assertThat(outcome.mode()).isEqualTo(EmailSyncService.Mode.INCREMENTAL);
        assertThat(outcome.newEmails()).isEqualTo(1);
        assertThat(outcome.updatedEmails()).isEqualTo(1);
        assertThat(outcome.removedEmails()).isEqualTo(1);

        verify(gmail, never()).latestInboxMessageIds(any(), anyInt());
        verify(gmail, times(1)).getMessageIfPresent(any(), anyString());
        verify(gmail).getMessageIfPresent(any(), eq("m501"));
        verify(analysisQueue, times(1)).enqueue(any(), any());

        assertThat(stored("m501")).isNotNull();
        assertThat(stored("m499").isRead()).isTrue();
        assertThat(stored("m498")).isNull();
        assertThat(emails.countByMailAccountId(accountId)).isEqualTo(20);
        assertThat(account().getLastHistoryId()).isEqualTo("1010");
    }

    @Test
    void aQuietMailboxDownloadsNothing() {
        syncService.syncMailbox(accountId, SyncTrigger.LOGIN);
        clearInvocations(gmail);
        when(gmail.listHistory(any(), eq("1000"), isNull())).thenReturn(history("1003"));

        syncService.syncMailbox(accountId, SyncTrigger.POLL);

        verify(gmail, never()).getMessageIfPresent(any(), anyString());
        verify(gmail, never()).latestInboxMessageIds(any(), anyInt());
        assertThat(account().getLastHistoryId()).isEqualTo("1003");
    }

    @Test
    void anExpiredCheckpointCatchesUpWithTheNewestMessagesOnly() {
        syncService.syncMailbox(accountId, SyncTrigger.LOGIN); // m481..m500
        clearInvocations(gmail);

        when(gmail.listHistory(any(), eq("1000"), isNull()))
                .thenThrow(new GmailInboxClient.HistoryExpiredException(null));
        when(gmail.currentHistoryId(any())).thenReturn("9000");
        List<String> newestAfterLongAbsence = IntStream.rangeClosed(1, 20).mapToObj(i -> "m" + (506 - i)).toList(); // m486..m505
        when(gmail.latestInboxMessageIds(any(), eq(20))).thenReturn(newestAfterLongAbsence);

        EmailSyncService.SyncOutcome outcome = syncService.syncMailbox(accountId, SyncTrigger.CONNECT);

        assertThat(outcome.mode()).isEqualTo(EmailSyncService.Mode.CATCH_UP);
        assertThat(outcome.newEmails()).isEqualTo(5); // m501..m505; the other 15 were already stored
        verify(gmail, times(5)).getMessageIfPresent(any(), anyString());
        assertThat(account().getLastHistoryId()).isEqualTo("9000");
    }

    @Test
    void aPassThatFailsHalfwayKeepsTheCheckpointAndIsRedoneWithoutDuplicates() {
        syncService.syncMailbox(accountId, SyncTrigger.LOGIN);

        when(gmail.listHistory(any(), eq("1000"), isNull())).thenReturn(history("1020",
                new Change(Type.MESSAGE_ADDED, "m501", List.of("INBOX")),
                new Change(Type.MESSAGE_ADDED, "m502", List.of("INBOX")),
                new Change(Type.MESSAGE_ADDED, "m503", List.of("INBOX"))));
        when(gmail.getMessageIfPresent(any(), eq("m502")))
                .thenThrow(GmailIntegrationException.unavailable(null));

        assertThat(syncService.syncMailbox(accountId, SyncTrigger.POLL)).isNull();
        assertThat(account().getLastHistoryId()).isEqualTo("1000");
        assertThat(account().getLastSyncError()).isNotBlank();
        assertThat(stored("m501")).isNotNull();

        // Gmail recovers: the same history is replayed; m501 isn't stored twice.
        doAnswer(inv -> message("m502")).when(gmail).getMessageIfPresent(any(), eq("m502"));
        EmailSyncService.SyncOutcome retry = syncService.syncMailbox(accountId, SyncTrigger.POLL);

        assertThat(retry.newEmails()).isEqualTo(2);
        assertThat(emails.countByMailAccountId(accountId)).isEqualTo(23);
        assertThat(account().getLastHistoryId()).isEqualTo("1020");
        assertThat(account().getLastSyncError()).isNull();
    }

    @Test
    void revokedGmailAccessFlagsTheAccountForReconnectWithoutLosingData() {
        syncService.syncMailbox(accountId, SyncTrigger.LOGIN);
        when(gmail.listHistory(any(), anyString(), any()))
                .thenThrow(GmailIntegrationException.reauthRequired(null));

        assertThat(syncService.syncMailbox(accountId, SyncTrigger.POLL)).isNull();

        MailAccount account = account();
        assertThat(account.isReauthRequired()).isTrue();
        assertThat(account.isActive()).isTrue();
        assertThat(account.getLastHistoryId()).isEqualTo("1000");
        assertThat(emails.countByMailAccountId(accountId)).isEqualTo(20);
    }
}
