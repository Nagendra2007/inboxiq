package com.inboxiq.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inboxiq.config.AppProperties;
import com.inboxiq.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * {@link AiClient} implementation for any OpenRouter-compatible endpoint.
 * OpenRouter exposes an OpenAI-style {@code /chat/completions} API in front
 * of many underlying models, which is exactly what makes the model
 * configurable at the environment-variable level ({@code AI_MODEL}) instead
 * of being baked into this class.
 *
 * No API key is ever logged; failures log only the HTTP status and a short
 * classification, never the request or response body (which could contain
 * email content).
 */
@Component
public class OpenRouterClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);

    private final WebClient webClient;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public OpenRouterClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(appProperties.getAi().getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // OpenRouter-recommended attribution headers (optional but
                // good practice; harmless if the endpoint ignores them).
                .defaultHeader("X-Title", "InboxIQ")
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, String model, boolean jsonResponse) {
        String apiKey = appProperties.getAi().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiServiceException(
                    "OPENROUTER_API_KEY is not configured. Set it in your .env to enable AI features.");
        }
        String effectiveModel = (model == null || model.isBlank()) ? appProperties.getAi().getModel() : model;
        if (effectiveModel == null || effectiveModel.isBlank()) {
            throw new AiServiceException(
                    "AI_MODEL is not configured. Set it in your .env (e.g. openai/gpt-4o-mini).");
        }

        ChatRequest request = new ChatRequest(
                effectiveModel,
                List.of(new ChatMessage("system", systemPrompt), new ChatMessage("user", userPrompt)),
                jsonResponse ? new ResponseFormat("json_object") : null,
                0.3
        );

        try {
            String rawBody = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(appProperties.getAi().getRequestTimeoutSeconds()))
                    .retryWhen(Retry.backoff(appProperties.getAi().getMaxRetries(), Duration.ofSeconds(1))
                            .filter(this::isRetryable))
                    .block();

            return extractContent(rawBody);

        } catch (WebClientResponseException e) {
            log.error("OpenRouter request failed with HTTP {}", e.getStatusCode().value());
            throw new AiServiceException("The AI service returned an error. Please try again.", e);
        } catch (Exception e) {
            log.error("OpenRouter request failed", e);
            throw new AiServiceException("Could not reach the AI service. Please try again.", e);
        }
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return true; // network/timeout errors
    }

    private String extractContent(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AiServiceException("The AI service returned an unexpected response shape.");
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new AiServiceException("The AI service returned an empty response.");
            }
            return content.asText();
        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new AiServiceException("Could not parse the AI service's response.", e);
        }
    }

    // --- Request DTOs (OpenAI-compatible chat completions schema) ---

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ChatRequest(
            String model,
            List<ChatMessage> messages,
            @JsonProperty("response_format") ResponseFormat responseFormat,
            Double temperature
    ) {}

    private record ChatMessage(String role, String content) {}

    private record ResponseFormat(String type) {}
}
