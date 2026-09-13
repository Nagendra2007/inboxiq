package com.inboxiq.gmail;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.exception.GmailIntegrationException;
import com.inboxiq.service.MailAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Produces a valid (non-expired) Gmail API access token for a
 * {@link MailAccount}, transparently refreshing it via Google's token
 * endpoint when needed using the encrypted, database-persisted refresh
 * token — independent of any active HTTP session. This is what lets Gmail
 * sync and reply-sending work from a background job, not just mid-request.
 */
@Component
public class GmailCredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(GmailCredentialProvider.class);
    private static final String GOOGLE_REGISTRATION_ID = "google";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final MailAccountService mailAccountService;
    private final HttpTransport httpTransport;
    private final JsonFactory jsonFactory;

    public GmailCredentialProvider(ClientRegistrationRepository clientRegistrationRepository,
                                    MailAccountService mailAccountService,
                                    HttpTransport httpTransport,
                                    JsonFactory jsonFactory) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.mailAccountService = mailAccountService;
        this.httpTransport = httpTransport;
        this.jsonFactory = jsonFactory;
    }

    public String getValidAccessToken(MailAccount account) {
        Instant expiry = account.getTokenExpiry();
        boolean needsRefresh = expiry == null || Instant.now().isAfter(expiry.minusSeconds(60));

        if (!needsRefresh) {
            String accessToken = mailAccountService.decryptAccessToken(account);
            if (accessToken != null) return accessToken;
        }

        String refreshToken = mailAccountService.decryptRefreshToken(account);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw GmailIntegrationException.reauthRequired(null);
        }

        ClientRegistration google = clientRegistrationRepository.findByRegistrationId(GOOGLE_REGISTRATION_ID);
        if (google == null) {
            throw new IllegalStateException("Google OAuth client is not configured (GOOGLE_CLIENT_ID/SECRET).");
        }

        try {
            GoogleTokenResponse tokenResponse = new GoogleRefreshTokenRequest(
                    httpTransport, jsonFactory, refreshToken,
                    google.getClientId(), google.getClientSecret()
            ).execute();

            String newAccessToken = tokenResponse.getAccessToken();
            long expiresInSeconds = tokenResponse.getExpiresInSeconds() != null
                    ? tokenResponse.getExpiresInSeconds() : 3600L;
            Instant newExpiry = Instant.now().plusSeconds(expiresInSeconds);

            mailAccountService.updateAccessToken(account.getId(), newAccessToken, newExpiry);
            log.info("Refreshed Gmail access token for mail account id={}", account.getId());
            return newAccessToken;

        } catch (TokenResponseException e) {
            // invalid_grant almost always means the refresh token was revoked
            // (user removed access at myaccount.google.com/permissions).
            log.warn("Gmail token refresh failed with invalid_grant-type error for account id={}", account.getId());
            throw GmailIntegrationException.reauthRequired(e);
        } catch (IOException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }
}
