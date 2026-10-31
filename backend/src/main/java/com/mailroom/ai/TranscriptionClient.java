package com.mailroom.ai;

import com.mailroom.config.AppProperties;
import com.mailroom.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Speech to text, done on the server from an audio clip the browser
 * recorded.
 *
 * <p>The browser can do this itself through the Web Speech API, and for a
 * while this app did. The catch is that Chrome's implementation is a call to
 * a Google service: when it cannot be reached, or does not like the audio,
 * it returns nothing at all and says nothing about why. That is a bad
 * foundation for a feature, and it is unavailable in Firefox entirely.
 *
 * <p>Recording the audio and sending it to Whisper costs a fraction of a
 * penny a clip and is better on every axis that matters here — it works in
 * every browser, it fails with an actual error, and it is markedly more
 * accurate on Indian English and on names, which is the hardest and most
 * consequential part of dictating an email.
 *
 * <p>Any OpenAI-compatible endpoint works, since they all expose
 * {@code /audio/transcriptions}. Blank configuration disables the feature
 * rather than failing at the user.
 */
@Component
public class TranscriptionClient {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** Generous for one dictated sentence; small enough to refuse a wrong upload. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private final AppProperties appProperties;
    private final WebClient.Builder webClientBuilder;

    public TranscriptionClient(AppProperties appProperties, WebClient.Builder webClientBuilder) {
        this.appProperties = appProperties;
        this.webClientBuilder = webClientBuilder;
    }

    public boolean isEnabled() {
        AppProperties.Ai ai = appProperties.getAi();
        return notBlank(ai.getTranscriptionApiKey()) && notBlank(ai.getTranscriptionBaseUrl());
    }

    public String transcribe(byte[] audio, String filename) {
        if (!isEnabled()) {
            throw new AiServiceException("Voice input is not configured on this deployment.");
        }
        if (audio.length == 0) {
            throw new AiServiceException("The recording was empty.");
        }
        if (audio.length > MAX_BYTES) {
            throw new AiServiceException("That recording is too long. Keep it under a minute.");
        }
        AppProperties.Ai ai = appProperties.getAi();

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                // Whisper picks its decoder from the extension, so a name
                // is not optional here even though the bytes are the point.
                return filename;
            }
        });
        body.part("model", ai.getTranscriptionModel());
        // Plain text back: no timestamps, no segments, nothing to parse.
        body.part("response_format", "json");

        try {
            Map<?, ?> response = webClientBuilder.build().post()
                    .uri(ai.getTranscriptionBaseUrl().replaceAll("/+$", "") + "/audio/transcriptions")
                    .header("Authorization", "Bearer " + ai.getTranscriptionApiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(TIMEOUT);

            Object text = response == null ? null : response.get("text");
            if (text == null || text.toString().isBlank()) {
                throw new AiServiceException("Nothing could be made out of that recording.");
            }
            return text.toString().strip();
        } catch (WebClientResponseException e) {
            // The provider's own words are usually the useful ones, but they
            // can carry the key back in an error body, so only the status
            // reaches the user.
            log.warn("Transcription failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new AiServiceException(switch (e.getStatusCode().value()) {
                case 401, 403 -> "The transcription service rejected the API key.";
                case 429 -> "The transcription service is rate limiting. Try again in a moment.";
                default -> "The transcription service could not handle that recording.";
            });
        } catch (AiServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Transcription call failed: {}", e.toString());
            throw new AiServiceException("Could not reach the transcription service.");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
