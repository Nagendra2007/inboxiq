package com.mailroom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import java.util.List;

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

    static String[] spaRoutes() {
        return com.mailroom.security.SpaRoutes.ALL;
    }

    @ParameterizedTest
    @MethodSource("spaRoutes")
    void clientSideRoutesServeTheSpaShell(String path) throws Exception {
        // /login in particular must not be Spring Security's generated login page.
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    /**
     * Every route the frontend declares is one the server will serve.
     *
     * <p>Read from the router rather than listed here, because a list
     * written twice is a list that disagrees with itself. Clicking through
     * the app never asks the server for these paths — the router handles
     * them — so a route the backend does not know about works perfectly
     * until somebody reloads the page or follows a link to it, and then
     * returns 403 for a page that plainly exists. That is exactly how
     * /ask shipped.
     */
    @Test
    void theServerKnowsEveryRouteTheFrontendHas() throws Exception {
        String router = java.nio.file.Files.readString(
                java.nio.file.Path.of("../frontend/src/App.tsx"));

        java.util.regex.Matcher paths = java.util.regex.Pattern
                .compile("path=\"(/[^\"*]*)\"")
                .matcher(router);

        java.util.List<String> declared = new java.util.ArrayList<>();
        while (paths.find()) declared.add(paths.group(1));

        assertThat(declared).as("routes found in App.tsx").isNotEmpty();

        assertThat(com.mailroom.security.SpaRoutes.ALL)
                .as("every route the frontend declares must also be served by the backend")
                .contains(declared.toArray(new String[0]));
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
