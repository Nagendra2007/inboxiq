package com.inboxiq.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Shapes the redirect to Google so that signing in and authorizing Gmail are
 * two separate things:
 *
 *  - {@code /oauth2/authorization/google} — signing in. Shows Google's
 *    account chooser only. A returning user whose Gmail grant InboxIQ still
 *    holds is not asked to consent again.
 *  - {@code /oauth2/authorization/google?consent=1} — (re)authorizing Gmail.
 *    Forces Google's consent screen, the only way to get a new refresh token
 *    (Google issues one on consent, not on every sign-in). Used for the
 *    first connection, "Reconnect Gmail", and automatically after sign-in
 *    when InboxIQ holds no usable grant (see OAuth2LoginSuccessHandler).
 *
 * Both ask for {@code access_type=offline}, so a consent yields a refresh
 * token that lets the server use Gmail with no browser session involved.
 */
public class GoogleAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    /** Session flag: this OAuth round trip was the forced-consent one. */
    public static final String CONSENT_REQUESTED_ATTRIBUTE = "inboxiq.gmailConsentRequested";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public GoogleAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization");
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return customize(request, delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest customize(HttpServletRequest request, OAuth2AuthorizationRequest authorizationRequest) {
        if (authorizationRequest == null) return null;
        boolean consent = "1".equals(request.getParameter("consent"));
        if (consent) {
            request.getSession().setAttribute(CONSENT_REQUESTED_ATTRIBUTE, Boolean.TRUE);
        }
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .additionalParameters(params -> {
                    params.put("access_type", "offline");
                    params.put("prompt", consent ? "consent" : "select_account");
                })
                .build();
    }
}
