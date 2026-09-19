package com.inboxiq;

import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.entity.User;
import com.inboxiq.exception.GmailIntegrationException;
import com.inboxiq.gmail.GmailInboxClient;
import com.inboxiq.repository.EmailRepository;
import com.inboxiq.repository.MailAccountRepository;
import com.inboxiq.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acting on a selection: delete, archive, mark read. Every one of them
 * reaches Gmail before the local copy changes, one email failing doesn't take
 * the rest down with it, and no amount of ids in the body reaches anyone
 * else's mail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BulkActionIntegrationTest {

    @MockBean private GmailInboxClient gmail;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private MailAccountRepository accounts;
    @Autowired private EmailRepository emails;

    private static RequestPostProcessor as(User user) {
        return oidcLogin().idToken(token -> token.claim("email", user.getEmail()));
    }

    private User newUser() {
        return users.save(new User("bulk-" + UUID.randomUUID() + "@example.com", "Bulk Test"));
    }

    private MailAccount accountFor(User user) {
        MailAccount account = new MailAccount();
        account.setUser(user);
        account.setProvider(MailProvider.GOOGLE);
        account.setProviderEmail(user.getEmail());
        return accounts.save(account);
    }

    private EmailMessage email(MailAccount account, String gmailId) {
        EmailMessage email = new EmailMessage();
        email.setMailAccount(account);
        email.setProviderMessageId(gmailId);
        email.setSubject("Subject " + gmailId);
        email.setReceivedAt(Instant.now());
        return emails.save(email);
    }

    private String body(List<UUID> ids) throws Exception {
        return json.writeValueAsString(Map.of("ids", ids));
    }

    private String archiveBody(List<UUID> ids, boolean archived) throws Exception {
        return json.writeValueAsString(Map.of("ids", ids, "archived", archived));
    }

    @BeforeEach
    void gmailAcceptsEverything() {
        when(gmail.trashMessage(any(), anyString())).thenReturn(true);
        when(gmail.setMessageArchived(any(), anyString(), anyBoolean())).thenReturn(true);
        when(gmail.setMessageRead(any(), anyString(), anyBoolean())).thenReturn(true);
    }

    @Test
    void deletesEveryChosenEmailAndTrashesEachOneInGmail() throws Exception {
        User user = newUser();
        MailAccount account = accountFor(user);
        EmailMessage one = email(account, "b1");
        EmailMessage two = email(account, "b2");
        EmailMessage kept = email(account, "b3");

        mvc.perform(post("/api/emails/bulk-delete").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(one.getId(), two.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedIds.length()").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.changedInGmail").value(2));

        assertThat(emails.findById(one.getId())).isEmpty();
        assertThat(emails.findById(two.getId())).isEmpty();
        assertThat(emails.findById(kept.getId())).isPresent();
        verify(gmail).trashMessage(any(), eq("b1"));
        verify(gmail).trashMessage(any(), eq("b2"));
        verify(gmail, never()).trashMessage(any(), eq("b3"));
    }

    @Test
    void anEmailGmailRefusesIsLeftAloneAndTheRestStillGo() throws Exception {
        User user = newUser();
        MailAccount account = accountFor(user);
        EmailMessage ok = email(account, "g1");
        EmailMessage refused = email(account, "g2");
        doThrow(GmailIntegrationException.unavailable(new RuntimeException("Gmail is down")))
                .when(gmail).trashMessage(any(), eq("g2"));

        mvc.perform(post("/api/emails/bulk-delete").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(ok.getId(), refused.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedIds.length()").value(1))
                .andExpect(jsonPath("$.failed").value(1));

        assertThat(emails.findById(ok.getId())).isEmpty();
        // Still in Gmail, so still here: the two never drift apart.
        assertThat(emails.findById(refused.getId())).isPresent();
    }

    @Test
    void someoneElsesEmailIsNeverTouched() throws Exception {
        User user = newUser();
        MailAccount account = accountFor(user);
        EmailMessage mine = email(account, "m1");

        User stranger = newUser();
        EmailMessage theirs = email(accountFor(stranger), "s1");

        mvc.perform(post("/api/emails/bulk-delete").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(mine.getId(), theirs.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedIds.length()").value(1))
                .andExpect(jsonPath("$.failed").value(1));

        assertThat(emails.findById(mine.getId())).isEmpty();
        assertThat(emails.findById(theirs.getId())).isPresent();
        verify(gmail, never()).trashMessage(any(), eq("s1"));
    }

    /**
     * The case behind "it disappeared from InboxIQ but my mailbox looks the
     * same": Gmail didn't have the message any more, so nothing moved there.
     * The local copy still goes, but the result mustn't claim a mailbox
     * change that didn't happen.
     */
    @Test
    void anEmailGmailNoLongerHasIsRemovedHereWithoutClaimingGmailMovedIt() throws Exception {
        User user = newUser();
        MailAccount account = accountFor(user);
        EmailMessage moved = email(account, "t1");
        EmailMessage alreadyGone = email(account, "t2");
        when(gmail.trashMessage(any(), eq("t2"))).thenReturn(false);

        mvc.perform(post("/api/emails/bulk-delete").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(moved.getId(), alreadyGone.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedIds.length()").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.changedInGmail").value(1));

        assertThat(emails.findById(moved.getId())).isEmpty();
        assertThat(emails.findById(alreadyGone.getId())).isEmpty();
    }

    @Test
    void archivingTakesEmailsOutOfTheInboxWithoutLosingThem() throws Exception {
        User user = newUser();
        MailAccount account = accountFor(user);
        EmailMessage filed = email(account, "a1");
        EmailMessage kept = email(account, "a2");

        mvc.perform(post("/api/emails/bulk-archive").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(List.of(filed.getId()), true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedIds.length()").value(1))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.changedInGmail").value(1));

        // Still here, with everything derived from it — just not in the inbox.
        assertThat(emails.findById(filed.getId())).get()
                .extracting(EmailMessage::isArchived).isEqualTo(true);
        assertThat(emails.findById(kept.getId())).get()
                .extracting(EmailMessage::isArchived).isEqualTo(false);
        verify(gmail).setMessageArchived(any(), eq("a1"), eq(true));
    }

    @Test
    void archivedEmailsLeaveTheInboxListAndAppearInTheArchivedOne() throws Exception {
        User user = newUser();
        MailAccount account = accountFor(user);
        EmailMessage filed = email(account, "l1");
        email(account, "l2");

        mvc.perform(post("/api/emails/bulk-archive").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(List.of(filed.getId()), true)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/emails").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].archived").value(false));

        mvc.perform(get("/api/emails").param("archived", "true").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].archived").value(true));
    }

    @Test
    void archivedEmailsCanBeMovedBackIntoTheInbox() throws Exception {
        User user = newUser();
        MailAccount account = accountFor(user);
        EmailMessage filed = email(account, "back1");

        mvc.perform(post("/api/emails/bulk-archive").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(List.of(filed.getId()), true)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/emails/bulk-archive").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(List.of(filed.getId()), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedIds.length()").value(1));

        assertThat(emails.findById(filed.getId())).get()
                .extracting(EmailMessage::isArchived).isEqualTo(false);
        verify(gmail).setMessageArchived(any(), eq("back1"), eq(false));
    }

    @Test
    void markingReadPushesTheChangeIntoGmailToo() throws Exception {
        User user = newUser();
        MailAccount account = accountFor(user);
        EmailMessage one = email(account, "r1");

        mvc.perform(post("/api/emails/bulk-read").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("ids", List.of(one.getId()), "read", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedIds.length()").value(1));

        assertThat(emails.findById(one.getId())).get().extracting(EmailMessage::isRead).isEqualTo(true);
        verify(gmail).setMessageRead(any(), eq("r1"), eq(true));
    }

    @Test
    void anEmailGmailRefusesToArchiveStaysInTheInbox() throws Exception {
        User user = newUser();
        MailAccount account = accountFor(user);
        EmailMessage refused = email(account, "x9");
        doThrow(GmailIntegrationException.unavailable(new RuntimeException("Gmail is down")))
                .when(gmail).setMessageArchived(any(), eq("x9"), eq(true));

        mvc.perform(post("/api/emails/bulk-archive").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(List.of(refused.getId()), true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedIds.length()").value(0))
                .andExpect(jsonPath("$.failed").value(1));

        // Never hidden here while it's still sitting in the Gmail inbox.
        assertThat(emails.findById(refused.getId())).get()
                .extracting(EmailMessage::isArchived).isEqualTo(false);
    }

    @Test
    void anEmptySelectionIsRejected() throws Exception {
        User user = newUser();
        accountFor(user);

        mvc.perform(post("/api/emails/bulk-delete").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signedOutCallersGetNowhere() throws Exception {
        mvc.perform(post("/api/emails/bulk-delete").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized());
    }
}
