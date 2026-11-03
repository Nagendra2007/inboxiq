package com.mailroom.security;

import com.mailroom.config.AppProperties;
import com.mailroom.entity.MailAccount;
import com.mailroom.service.GmailWatchService;
import com.mailroom.service.MailAccountService;
import com.mailroom.service.SyncCoordinator;
import com.mailroom.service.SyncTrigger;
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
 * Mailroom (the session exists). Separately, it persists the Gmail tokens,
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
    private final GmailWatchService gmailWatchService;
    private final AppProperties appProperties;

    public OAuth2LoginSuccessHandler(OAuth2AuthorizedClientRepository authorizedClientRepository,
                                      MailAccountService mailAccountService,
                                      SyncCoordinator syncCoordinator,
                                      GmailWatchService gmailWatchService,
                                      AppProperties appProperties) {
        this.authorizedClientRepository = authorizedClientRepository;
        this.mailAccountService = mailAccountService;
        this.syncCoordinator = syncCoordinator;
        this.gmailWatchService = gmailWatchService;
        this.appProperties = appProperties;
    }

    /**
     * Sends the browser back to the login page with a reason.
     *
     * <p>Checks whether the response has already gone out first. Calling
     * sendRedirect on a committed response throws, and a failure inside a
     * failure handler is how an ordinary error becomes a blank 500 page —
     * the exact shape this method exists to avoid.
     */
    private void sendToLogin(HttpServletResponse response, String frontend, String reason) {
        if (response.isCommitted()) {
            log.warn("Cannot report sign-in failure '{}': the response is already committed", reason);
            return;
        }
        try {
            response.sendRedirect(frontend + "/login?error=" + reason);
        } catch (IOException e) {
            log.warn("Could not send the browser back to the login page: {}", e.toString());
        }
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

        String frontend = appProperties.getFrontend().getBaseUrl();
        MailAccount account;
        try {
            account = mailAccountService.upsertFromOAuthLogin(email, name, accessToken, refreshToken, expiresAt);
        } catch (RuntimeException e) {
            // Everything below here runs after Google has already accepted
            // the user, so a failure is ours. Unhandled, it reaches the
            // browser as Spring's blank error page: no message, no code,
            // nothing to act on, and a signed-in user staring at a 500.
            log.error("Could not link the Gmail account after a successful Google sign-in", e);
            sendToLogin(response, frontend, "account_link_failed");
            return;
        }

        HttpSession session = request.getSession(false);
        boolean consentJustGiven = session != null
                && session.getAttribute(GoogleAuthorizationRequestResolver.CONSENT_REQUESTED_ATTRIBUTE) != null;
        if (session != null) {
            session.removeAttribute(GoogleAuthorizationRequestResolver.CONSENT_REQUESTED_ATTRIBUTE);
        }

        // Signed in, but Mailroom has no Gmail grant it can keep using (first
        // connection, access revoked, or data deleted): Google only issues a
        // refresh token on its consent screen, so go through it once. The
        // flag stops this from ever looping.
        if (!mailAccountService.hasUsableGmailGrant(account) && !consentJustGiven) {
            getRedirectStrategy().sendRedirect(request, response, frontend + "/oauth2/authorization/google?consent=1");
            return;
        }

        // Fetch new mail in the background while the browser loads the app;
        // the inbox shows what's already stored and updates over SSE.
        // Guarded for the same reason as the watch below: the user is
        // signed in by this point, and nothing done for their convenience
        // afterwards is worth refusing them entry over.
        try {
            syncCoordinator.requestSync(account.getId(), SyncTrigger.LOGIN);
        } catch (RuntimeException e) {
            log.warn("Could not start the sign-in sync: {}", e.toString());
        }
        // And make sure Gmail is still set up to tell us about the next one.
        // Cheap unless a registration is actually due, and a failure here is
        // only a return to polling, so it must never block signing in.
        try {
            gmailWatchService.ensureWatch(account.getId());
        } catch (RuntimeException e) {
            log.warn("Could not register the Gmail watch at sign-in: {}", e.getMessage());
        }

        boolean gmailJustConnected = refreshToken != null && !refreshToken.isBlank();
        try {
            getRedirectStrategy().sendRedirect(request, response,
                    frontend + (gmailJustConnected ? "/inbox?connected=1" : "/inbox"));
        } catch (RuntimeException e) {
            log.error("Could not redirect after sign-in", e);
            sendToLogin(response, frontend, "redirect_failed");
        }
    }
}
