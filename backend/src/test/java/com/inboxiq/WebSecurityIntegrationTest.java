package com.inboxiq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full application context (H2 "test" profile) and checks the
 * HTTP-level contract the SPA depends on: routing, the 401/CSRF handshake,
 * and security headers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/login", "/inbox", "/dashboard", "/action-items", "/settings"})
    void clientSideRoutesServeTheSpaShell(String path) throws Exception {
        // /login in particular must not be Spring Security's generated login page.
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void unauthenticatedApiCallGets401JsonAndACsrfCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"))
                .andReturn();
        assertThat(xsrfCookie(result)).as("XSRF-TOKEN issued on the first GET").isNotNull();
    }

    @Test
    void mutatingRequestWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
    }

    @Test
    void mutatingRequestEchoingTheCsrfCookieIsAccepted() throws Exception {
        Cookie xsrf = xsrfCookie(mvc.perform(get("/api/auth/me")).andReturn());
        assertThat(xsrf).isNotNull();

        mvc.perform(post("/api/auth/logout").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void securityHeadersAreSent() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void googleSignInRedirectsToGoogle() throws Exception {
        mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://accounts.google.com/o/oauth2/v2/auth")));
    }

    /** Reads XSRF-TOKEN from the raw Set-Cookie headers (written via ResponseCookie). */
    private static Cookie xsrfCookie(MvcResult result) {
        for (String setCookie : result.getResponse().getHeaders("Set-Cookie")) {
            if (setCookie.startsWith("XSRF-TOKEN=")) {
                String value = setCookie.substring("XSRF-TOKEN=".length()).split(";", 2)[0];
                if (!value.isEmpty()) return new Cookie("XSRF-TOKEN", value);
            }
        }
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        return cookie != null && !cookie.getValue().isEmpty() ? cookie : null;
    }
}
