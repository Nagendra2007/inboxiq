package com.inboxiq.security;

import com.inboxiq.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class OAuth2LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginFailureHandler.class);

    private final AppProperties appProperties;

    public OAuth2LoginFailureHandler(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException {
        // Log the reason server-side only; the user only ever sees a generic code.
        log.warn("Google OAuth login failed: {}", exception.getClass().getSimpleName());
        String reason = URLEncoder.encode("google_oauth_failed", StandardCharsets.UTF_8);
        response.sendRedirect(appProperties.getFrontend().getBaseUrl() + "/login?error=" + reason);
    }
}
