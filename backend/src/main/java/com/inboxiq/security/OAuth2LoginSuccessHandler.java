package com.inboxiq.security;

import com.inboxiq.config.AppProperties;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.service.MailAccountService;
import com.inboxiq.service.SyncCoordinator;
import com.inboxiq.service.SyncTrigger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
 * authorization_code exchange. At this point the user is signed in to
 * InboxIQ (the session exists). Separately, it persists the Gmail tokens,
 * encrypted, into our own database (see {@link MailAccountService}) so
 * background sync and "send reply" work without the browser session, starts
 * a background sync, and redirects back to the SPA — without waiting for
 * Gmail.
 */
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final MailAccountService mailAccountService;
    private final SyncCoordinator syncCoordinator;
    private final AppProperties appProperties;

    public OAuth2LoginSuccessHandler(OAuth2AuthorizedClientRepository authorizedClientRepository,
                                      MailAccountService mailAccountService,
                                      SyncCoordinator syncCoordinator,
                                      AppProperties appProperties) {
        this.authorizedClientRepository = authorizedClientRepository;
        this.mailAccountService = mailAccountService;
        this.syncCoordinator = syncCoordinator;
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

        MailAccount account = mailAccountService.upsertFromOAuthLogin(email, name, accessToken, refreshToken, expiresAt);
        String frontend = appProperties.getFrontend().getBaseUrl();

        HttpSession session = request.getSession(false);
        boolean consentJustGiven = session != null
                && session.getAttribute(GoogleAuthorizationRequestResolver.CONSENT_REQUESTED_ATTRIBUTE) != null;
        if (session != null) {
            session.removeAttribute(GoogleAuthorizationRequestResolver.CONSENT_REQUESTED_ATTRIBUTE);
        }

        // Signed in, but InboxIQ has no Gmail grant it can keep using (first
        // connection, access revoked, or data deleted): Google only issues a
        // refresh token on its consent screen, so go through it once. The
        // flag stops this from ever looping.
        if (!mailAccountService.hasUsableGmailGrant(account) && !consentJustGiven) {
            getRedirectStrategy().sendRedirect(request, response, frontend + "/oauth2/authorization/google?consent=1");
            return;
        }

        // Fetch new mail in the background while the browser loads the app;
        // the inbox shows what's already stored and updates over SSE.
        syncCoordinator.requestSync(account.getId(), SyncTrigger.LOGIN);

        boolean gmailJustConnected = refreshToken != null && !refreshToken.isBlank();
        getRedirectStrategy().sendRedirect(request, response,
                frontend + (gmailJustConnected ? "/inbox?connected=1" : "/inbox"));
    }
}
