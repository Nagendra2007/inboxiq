package com.mailroom.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailroom.config.AppProperties;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Sends a notification to one Android device through Firebase Cloud
 * Messaging, using FCM's HTTP v1 API directly.
 *
 * <p>The usual way to do this is the Firebase Admin SDK, which is a large
 * dependency pulling gRPC and most of Google's cloud client stack. All it
 * does that matters here is sign a JWT with a service account key, swap it
 * for an access token, and POST some JSON. jose4j is already in this project
 * for web push, so that is the whole of it, in about a hundred lines.
 *
 * <p>Access tokens last an hour and are cached until shortly before they
 * expire. Blank configuration disables FCM rather than failing at the user,
 * which is also how it runs locally and in tests.
 */
@Component
public class FcmClient {

    private static final Logger log = LoggerFactory.getLogger(FcmClient.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Refresh a little early, so a token never expires mid-send. */
    private static final Duration EARLY = Duration.ofMinutes(5);

    private final AppProperties appProperties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    private volatile String accessToken;
    private volatile Instant accessTokenExpiry = Instant.EPOCH;

    public FcmClient(AppProperties appProperties, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        AppProperties.Fcm fcm = appProperties.getFcm();
        return notBlank(fcm.getProjectId()) && notBlank(fcm.getServiceAccountJson());
    }

    /** What FCM said. UNREGISTERED means the app was uninstalled and the row should go. */
    public enum Result { SENT, UNREGISTERED, FAILED }

    public Result send(String deviceToken, String title, String body, String url) {
        return post(deviceToken, Map.of("kind", "mail", "title", title, "body", body, "url", url));
    }

    /**
     * A one-time code. The code is its own field so the app can show it
     * large and put exactly it on the clipboard, without parsing prose.
     */
    public Result sendOtp(String deviceToken, String code, String from, String url) {
        // "sender", never "from": FCM reserves "from" as a data key, along
        // with "message_type" and anything beginning "google" or "gcm", and
        // rejects the whole message for using one. Ordinary mail went
        // through and codes silently did not, because only this payload
        // had it — and the rejection happens at Google, so the phone had
        // nothing to display and no way to know it was supposed to.
        return post(deviceToken, Map.of("kind", "otp", "code", code, "sender", from, "url", url));
    }

    private Result post(String deviceToken, Map<String, String> data) {
        if (!isEnabled()) return Result.FAILED;
        try {
            Map<String, Object> message = Map.of(
                    "message", Map.of(
                            "token", deviceToken,
                            // Sent as data rather than as a "notification"
                            // block so the app builds the notification
                            // itself — which is the only way to get a Copy
                            // action and a code shown at full size.
                            "data", data,
                            "android", Map.of("priority", "high")
                    ));

            webClientBuilder.build().post()
                    .uri("https://fcm.googleapis.com/v1/projects/" + appProperties.getFcm().getProjectId()
                            + "/messages:send")
                    .header("Authorization", "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(message))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
            return Result.SENT;
        } catch (WebClientResponseException e) {
            // 404 and 403 both mean this registration is dead: the app was
            // uninstalled, or the token belongs to a different project.
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 403) {
                return Result.UNREGISTERED;
            }
            log.warn("FCM send failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("FCM send failed: {}", e.toString());
            return Result.FAILED;
        }
    }

    /** A Google OAuth access token, minted from the service account key. */
    private String accessToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiry.minus(EARLY))) {
            return accessToken;
        }
        synchronized (this) {
            if (accessToken != null && Instant.now().isBefore(accessTokenExpiry.minus(EARLY))) {
                return accessToken;
            }
            JsonNode account = objectMapper.readTree(appProperties.getFcm().getServiceAccountJson());
            String clientEmail = account.path("client_email").asText();
            String tokenUri = account.path("token_uri").asText("https://oauth2.googleapis.com/token");

            JwtClaims claims = new JwtClaims();
            claims.setIssuer(clientEmail);
            claims.setAudience(tokenUri);
            claims.setClaim("scope", SCOPE);
            claims.setIssuedAt(NumericDate.now());
            claims.setExpirationTimeMinutesInTheFuture(60);

            JsonWebSignature jws = new JsonWebSignature();
            jws.setPayload(claims.toJson());
            jws.setKey(privateKey(account.path("private_key").asText()));
            jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            form.add("assertion", jws.getCompactSerialization());

            String response = webClientBuilder.build().post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            JsonNode granted = objectMapper.readTree(response);
            accessToken = granted.path("access_token").asText();
            accessTokenExpiry = Instant.now().plusSeconds(granted.path("expires_in").asLong(3600));
            return accessToken;
        }
    }

    /** The PEM in the service account JSON, as a key the JDK will sign with. */
    private static PrivateKey privateKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64.getBytes(StandardCharsets.UTF_8));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
