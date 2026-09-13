package com.inboxiq.gmail;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.gmail.Gmail;
import com.inboxiq.entity.MailAccount;
import org.springframework.stereotype.Component;

/** Builds a per-request Gmail API client authorized with a fresh access token. */
@Component
public class GmailClientFactory {

    private static final String APPLICATION_NAME = "InboxIQ";

    private final HttpTransport httpTransport;
    private final JsonFactory jsonFactory;
    private final GmailCredentialProvider credentialProvider;

    public GmailClientFactory(HttpTransport httpTransport, JsonFactory jsonFactory,
                               GmailCredentialProvider credentialProvider) {
        this.httpTransport = httpTransport;
        this.jsonFactory = jsonFactory;
        this.credentialProvider = credentialProvider;
    }

    public Gmail forAccount(MailAccount account) {
        String accessToken = credentialProvider.getValidAccessToken(account);
        return new Gmail.Builder(httpTransport, jsonFactory,
                request -> request.getHeaders().setAuthorization("Bearer " + accessToken))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
