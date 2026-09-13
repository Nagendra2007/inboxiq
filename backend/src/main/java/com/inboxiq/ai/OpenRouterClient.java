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
 * No API key is ever logged; failures log only the HTTP status and the
 * provider's one-line {@code error.message} (e.g. "requires more credits"),
 * never the request or the full response body (which could contain email
 * content).
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

        int maxOutputTokens = appProperties.getAi().getMaxOutputTokens();
        ChatRequest request = new ChatRequest(
                effectiveModel,
                List.of(new ChatMessage("system", systemPrompt), new ChatMessage("user", userPrompt)),
                jsonResponse ? new ResponseFormat("json_object") : null,
                0.3,
                maxOutputTokens > 0 ? maxOutputTokens : null
        );

        String rawBody;
        try {
            rawBody = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(appProperties.getAi().getRequestTimeoutSeconds()))
                    .retryWhen(Retry.backoff(appProperties.getAi().getMaxRetries(), Duration.ofSeconds(1))
                            .filter(this::isRetryable))
                    .block();
        } catch (RuntimeException e) {
            // After retries are exhausted Reactor wraps the last failure, so
            // look through the cause chain for the provider's HTTP error.
            WebClientResponseException httpError = findHttpError(e);
            if (httpError != null) {
                int status = httpError.getStatusCode().value();
                log.error("OpenRouter request failed with HTTP {}: {}", status, providerMessage(httpError));
                throw new AiServiceException(messageForStatus(status), httpError);
            }
            log.error("OpenRouter request failed", e);
            throw new AiServiceException("Could not reach the AI service. Please try again.", e);
        }

        return extractContent(rawBody);
    }

    /** User-facing message for an OpenRouter error status — specific enough to act on. */
    private static String messageForStatus(int status) {
        return switch (status) {
            case 401 -> "AI is unavailable: the OpenRouter API key was rejected (check OPENROUTER_API_KEY).";
            case 402 -> "AI is unavailable: the OpenRouter account is out of credits. Add credits at openrouter.ai, or set AI_MODEL to a free model.";
            case 403 -> "The AI provider declined this request.";
            case 404 -> "AI is unavailable: the configured AI_MODEL wasn't found on OpenRouter.";
            case 408, 429 -> "The AI provider is busy right now. Please try again in a minute.";
            case 400 -> "The AI provider couldn't process this request (check that AI_MODEL supports it).";
            default -> "The AI service returned an error. Please try again.";
        };
    }

    private static WebClientResponseException findHttpError(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException wcre) return wcre;
            if (t.getCause() == t) break;
        }
        return null;
    }

    /**
     * OpenRouter's own one-line explanation (e.g. "requires more credits"),
     * for the server log. Only {@code error.message} is extracted — never the
     * request, and never the full body — and it is truncated.
     */
    private String providerMessage(WebClientResponseException e) {
        try {
            String message = objectMapper.readTree(e.getResponseBodyAsString()).path("error").path("message").asText("");
            if (message.isBlank()) return "(no details)";
            return message.length() > 300 ? message.substring(0, 300) + "…" : message;
        } catch (Exception ignored) {
            return "(no details)";
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
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens
    ) {}

    private record ChatMessage(String role, String content) {}

    private record ResponseFormat(String type) {}
}
