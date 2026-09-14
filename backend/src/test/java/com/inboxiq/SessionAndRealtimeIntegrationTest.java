package com.inboxiq;

import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.entity.User;
import com.inboxiq.realtime.EventStreamService;
import com.inboxiq.repository.MailAccountRepository;
import com.inboxiq.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Persistent sign-in (a database-backed session behind a cookie that
 * outlives the browser), sign-in vs. Gmail authorization, and the realtime
 * stream's authentication and per-user scoping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionAndRealtimeIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private MailAccountRepository accounts;
    @Autowired private EventStreamService events;

    private static RequestPostProcessor as(User user) {
        return oidcLogin().idToken(token -> token.claim("email", user.getEmail()));
    }

    private User newUser() {
        return users.save(new User("rt-" + UUID.randomUUID() + "@example.com", "Realtime Test"));
    }

    @Test
    void theSessionIsStoredInTheDatabaseBehindACookieThatOutlivesTheBrowser() throws Exception {
        long before = jdbc.queryForObject("select count(*) from SPRING_SESSION", Long.class);

        // Starting sign-in is the first thing that needs a session.
        MvcResult result = mvc.perform(get("/oauth2/authorization/google")).andReturn();

        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        String session = cookies.stream().filter(c -> c.startsWith("INBOXIQ_SESSION=")).findFirst().orElseThrow();
        assertThat(session)
                .contains("Max-Age=2592000") // 30 days: not a browser-session cookie
                .contains("HttpOnly")
                .containsIgnoringCase("SameSite=Lax");
        assertThat(jdbc.queryForObject("select count(*) from SPRING_SESSION", Long.class)).isGreaterThan(before);
    }

    @Test
    void signingInDoesNotForceGoogleConsentButConnectingGmailDoes() throws Exception {
        mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(header().string("Location", containsString("prompt=select_account")))
                .andExpect(header().string("Location", containsString("access_type=offline")));

        mvc.perform(get("/oauth2/authorization/google").param("consent", "1"))
                .andExpect(header().string("Location", containsString("prompt=consent")))
                .andExpect(header().string("Location", containsString("access_type=offline")));
    }

    @Test
    void gmailNeedingReconnectIsReportedWithoutSigningTheUserOut() throws Exception {
        User user = newUser();
        MailAccount account = new MailAccount();
        account.setUser(user);
        account.setProvider(MailProvider.GOOGLE);
        account.setProviderEmail(user.getEmail());
        account.setReauthRequired(true);
        accounts.save(account);

        mvc.perform(get("/api/auth/me").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gmailConnected").value(true))
                .andExpect(jsonPath("$.gmailReauthRequired").value(true));
    }

    @Test
    void theEventStreamRequiresSignIn() throws Exception {
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void eventsReachOnlyTheirOwnUsersStream() throws Exception {
        User alice = newUser();
        User bob = newUser();

        MvcResult aliceStream = mvc.perform(get("/api/events").with(as(alice)))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult bobStream = mvc.perform(get("/api/events").with(as(bob)))
                .andExpect(request().asyncStarted()).andReturn();

        assertThat(aliceStream.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(aliceStream.getResponse().getContentAsString()).contains("event:connected");
        assertThat(events.isConnected(alice.getId())).isTrue();

        events.publish(alice.getId(), "email.saved", Map.of("marker", "for-alice"));

        assertThat(aliceStream.getResponse().getContentAsString())
                .contains("event:email.saved")
                .contains("for-alice");
        assertThat(bobStream.getResponse().getContentAsString())
                .doesNotContain("email.saved")
                .doesNotContain("for-alice");
    }
}
