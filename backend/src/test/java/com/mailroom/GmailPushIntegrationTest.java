package com.mailroom;

import com.mailroom.entity.MailAccount;
import com.mailroom.entity.MailProvider;
import com.mailroom.entity.User;
import com.mailroom.repository.MailAccountRepository;
import com.mailroom.repository.UserRepository;
import com.mailroom.service.SyncCoordinator;
import com.mailroom.service.SyncTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The push endpoint is the only route in the application that the open
 * internet can POST to without a session, so what it refuses matters more
 * than what it accepts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.gmail.push-token=test-push-token")
class GmailPushIntegrationTest {

    private static final String EMAIL = "pushed@example.com";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired MailAccountRepository mailAccountRepository;
    @MockBean SyncCoordinator syncCoordinator;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        mailAccountRepository.deleteAll();
        userRepository.deleteAll();
        User user = new User();
        user.setEmail(EMAIL);
        user.setName("Push Tester");
        userRepository.save(user);

        MailAccount account = new MailAccount();
        account.setUser(user);
        account.setProvider(MailProvider.GOOGLE);
        account.setProviderEmail(EMAIL);
        account.setActive(true);
        accountId = mailAccountRepository.save(account).getId();
    }

    private static String envelope(String emailAddress) {
        String inner = "{\"emailAddress\":\"" + emailAddress + "\",\"historyId\":\"9999\"}";
        String data = Base64.getEncoder().encodeToString(inner.getBytes(StandardCharsets.UTF_8));
        return "{\"message\":{\"data\":\"" + data + "\",\"messageId\":\"1\"},\"subscription\":\"s\"}";
    }

    @Test
    void startsASyncForTheMailboxTheNotificationNames() throws Exception {
        mockMvc.perform(post("/gmail/push").param("token", "test-push-token")
                        .contentType(MediaType.APPLICATION_JSON).content(envelope(EMAIL)))
                .andExpect(status().isNoContent());

        verify(syncCoordinator).requestSync(eq(accountId), eq(SyncTrigger.PUSH));
    }

    @Test
    void refusesAWrongToken() throws Exception {
        mockMvc.perform(post("/gmail/push").param("token", "not-the-token")
                        .contentType(MediaType.APPLICATION_JSON).content(envelope(EMAIL)))
                .andExpect(status().isForbidden());

        verify(syncCoordinator, never()).requestSync(any(), any());
    }

    @Test
    void refusesWithNoTokenAtAll() throws Exception {
        mockMvc.perform(post("/gmail/push")
                        .contentType(MediaType.APPLICATION_JSON).content(envelope(EMAIL)))
                .andExpect(status().isForbidden());

        verify(syncCoordinator, never()).requestSync(any(), any());
    }

    @Test
    void ignoresAMailboxThisDeploymentDoesNotHave() throws Exception {
        // A valid token on a notification for someone else's address must not
        // become a way to probe which accounts exist here.
        mockMvc.perform(post("/gmail/push").param("token", "test-push-token")
                        .contentType(MediaType.APPLICATION_JSON).content(envelope("stranger@example.com")))
                .andExpect(status().isNoContent());

        verify(syncCoordinator, never()).requestSync(any(), any());
    }

    @Test
    void swallowsAnUnreadableBodyRatherThanAskingPubSubToRetry() throws Exception {
        // Pub/Sub retries non-2xx for up to seven days. A malformed payload is
        // never going to parse on the fourth attempt either.
        mockMvc.perform(post("/gmail/push").param("token", "test-push-token")
                        .contentType(MediaType.APPLICATION_JSON).content("not json at all"))
                .andExpect(status().isNoContent());

        verify(syncCoordinator, never()).requestSync(any(), any());
    }
}
