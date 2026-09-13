package com.inboxiq.security;

import com.inboxiq.config.AppProperties;
import com.inboxiq.service.MailAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Runs once, right after Spring Security completes the Google OAuth
 * authorization_code exchange. Persists the user + encrypted tokens into our
 * own database (see {@link MailAccountService}) so background sync and
 * "send reply" work without depending on the browser session staying open,
 * then redirects back to the SPA.
 */
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final MailAccountService mailAccountService;
    private final AppProperties appProperties;

    public OAuth2LoginSuccessHandler(OAuth2AuthorizedClientRepository authorizedClientRepository,
                                      MailAccountService mailAccountService,
                                      AppProperties appProperties) {
        this.authorizedClientRepository = authorizedClientRepository;
        this.mailAccountService = mailAccountService;
        this.appProperties = appProperties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)
                || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            log.warn("OAuth2 success handler invoked with an unexpected authentication type");
            response.sendRedirect(appProperties.getFrontend().getBaseUrl() + "/login?error=unexpected_auth_type");
            return;
        }

        OAuth2AuthorizedClient authorizedClient = authorizedClientRepository.loadAuthorizedClient(
                oauthToken.getAuthorizedClientRegistrationId(), authentication, request);

        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            log.warn("No authorized client available after OAuth2 login");
            response.sendRedirect(appProperties.getFrontend().getBaseUrl() + "/login?error=no_authorized_client");
            return;
        }

        String email = oidcUser.getEmail();
        String name = oidcUser.getFullName();
        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        String refreshToken = authorizedClient.getRefreshToken() != null
                ? authorizedClient.getRefreshToken().getTokenValue() : null;
        Instant expiresAt = authorizedClient.getAccessToken().getExpiresAt();

        if (email == null) {
            log.warn("Google login did not return an email attribute");
            response.sendRedirect(appProperties.getFrontend().getBaseUrl() + "/login?error=no_email");
            return;
        }

        mailAccountService.upsertFromOAuthLogin(email, name, accessToken, refreshToken, expiresAt);

        getRedirectStrategy().sendRedirect(request, response,
                appProperties.getFrontend().getBaseUrl() + "/inbox?connected=1");
    }
}
