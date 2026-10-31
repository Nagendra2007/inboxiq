package com.mailroom.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailroom.config.AppProperties;
import com.mailroom.entity.MailProvider;
import com.mailroom.repository.MailAccountRepository;
import com.mailroom.service.SyncCoordinator;
import com.mailroom.service.SyncTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Where Google delivers Gmail's "this mailbox changed" notifications.
 *
 * <p>This endpoint is deliberately outside {@code /api/**}: it is called by
 * Google rather than by a signed-in browser, so it has no session, no CSRF
 * token and no user. What stands in for authentication is a shared secret in
 * the query string, which is what the Pub/Sub push subscription is configured
 * with. Compared constant-time, because a timing oracle on a token is still a
 * timing oracle.
 *
 * <p>The notification body is treated as a nudge and nothing more. It carries
 * an email address and a historyId, and neither is trusted: the address only
 * selects which mailbox to sync, and the sync itself runs from the historyId
 * this application last stored. A forged or replayed notification can
 * therefore cause an early sync, which is harmless, and nothing else.
 *
 * <p>It always answers 204. Pub/Sub retries anything else with backoff for up
 * to seven days, so reporting a transient failure here buys a retry storm
 * rather than a recovery — and the poll already covers whatever a dropped
 * notification missed.
 */
@RestController
public class GmailPushController {

    private static final Logger log = LoggerFactory.getLogger(GmailPushController.class);

    private final MailAccountRepository mailAccountRepository;
    private final SyncCoordinator syncCoordinator;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public GmailPushController(MailAccountRepository mailAccountRepository,
                               SyncCoordinator syncCoordinator,
                               AppProperties appProperties,
                               ObjectMapper objectMapper) {
        this.mailAccountRepository = mailAccountRepository;
        this.syncCoordinator = syncCoordinator;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/gmail/push")
    public ResponseEntity<Void> receive(@RequestParam(name = "token", required = false) String token,
                                        @RequestBody(required = false) String body) {
        String expected = appProperties.getGmail().getPushToken();
        if (expected == null || expected.isBlank() || !constantTimeEquals(expected, token)) {
            // No detail in the response: an attacker probing this endpoint
            // learns only that it exists.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String emailAddress = extractEmailAddress(body);
        if (emailAddress == null) return ResponseEntity.noContent().build();

        mailAccountRepository.findByProviderEmailAndProviderAndActiveTrue(emailAddress, MailProvider.GOOGLE)
                .ifPresent(account -> {
                    if (account.isReauthRequired()) return;
                    syncCoordinator.requestSync(account.getId(), SyncTrigger.PUSH);
                });
        return ResponseEntity.noContent().build();
    }

    /**
     * Pub/Sub wraps the payload twice: a JSON envelope whose {@code message.data}
     * is base64, which decodes to Gmail's own JSON of
     * {@code {"emailAddress": ..., "historyId": ...}}.
     */
    private String extractEmailAddress(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode data = objectMapper.readTree(body).path("message").path("data");
            if (data.isMissingNode() || !data.isTextual()) return null;
            byte[] decoded = Base64.getDecoder().decode(data.asText());
            JsonNode payload = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            String email = payload.path("emailAddress").asText(null);
            return (email == null || email.isBlank()) ? null : email;
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Unreadable Gmail push notification: {}", e.getMessage());
            return null;
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
