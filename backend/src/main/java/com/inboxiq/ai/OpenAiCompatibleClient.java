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
import java.util.Locale;

/**
 * {@link AiClient} for any provider exposing the OpenAI-style
 * {@code /chat/completions} API — OpenRouter, OpenAI, Google Gemini,
 * Anthropic, Groq, DeepSeek, Mistral, or a custom endpoint. The provider,
 * endpoint, key and model arrive per call in an {@link AiConfig}.
 *
 * No API key is ever logged; failures log only the HTTP status and the
 * provider's one-line {@code error.message} (e.g. "requires more credits"),
 * never the request or the full response body (which could contain email
 * content).
 */
@Component
public class OpenAiCompatibleClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final double TEMPERATURE = 0.3;

    private final WebClient webClient;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String complete(AiConfig config, String systemPrompt, String userPrompt, boolean jsonResponse) {
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new AiServiceException("AI is not configured: no provider endpoint is set (Settings → AI provider).");
        }
        if (config.model() == null || config.model().isBlank()) {
            throw new AiServiceException("AI is not configured: no model is set (Settings → AI provider, or AI_MODEL).");
        }
        // A custom endpoint (e.g. a local Ollama) may legitimately need no key.
        if (!config.hasApiKey() && config.provider() != AiProvider.CUSTOM) {
            throw new AiServiceException("AI is not configured: no API key is set (Settings → AI provider, or AI_API_KEY).");
        }

        int cap = appProperties.getAi().getMaxOutputTokens();
        Integer outputCap = cap > 0 ? cap : null;
        // OpenAI's current models take max_completion_tokens (and its
        // reasoning models reject max_tokens outright); everyone else uses
        // the widely supported max_tokens.
        boolean completionTokens = config.provider() == AiProvider.OPENAI;
        ChatRequest request = new ChatRequest(
                config.model(),
                List.of(new ChatMessage("system", systemPrompt), new ChatMessage("user", userPrompt)),
                jsonResponse ? new ResponseFormat("json_object") : null,
                TEMPERATURE,
                completionTokens ? null : outputCap,
                completionTokens ? outputCap : null
        );
        return send(config, request, true);
    }

    private String send(AiConfig config, ChatRequest request, boolean allowSimplerRetry) {
        String label = config.provider().label();
        String rawBody;
        try {
            rawBody = webClient.post()
                    .uri(config.baseUrl() + "/chat/completions")
                    .headers(headers -> {
                        if (config.hasApiKey()) headers.setBearerAuth(config.apiKey());
                        // OpenRouter's optional attribution header.
                        if (config.provider() == AiProvider.OPENROUTER) headers.set("X-Title", "InboxIQ");
                    })
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
            if (httpError == null) {
                log.error("{} request failed", label, e);
                throw new AiServiceException("Could not reach the AI service. Please try again.", e);
            }
            int status = httpError.getStatusCode().value();
            String providerMessage = providerMessage(httpError);

            // Some models reject an optional parameter (a fixed temperature,
            // JSON mode, max_tokens); retry once without it rather than fail.
            if (status == 400 && allowSimplerRetry) {
                ChatRequest simpler = request.withoutParameterNamedIn(providerMessage);
                if (simpler != null) {
                    log.warn("{} rejected a request parameter ({}); retrying without it", label, providerMessage);
                    return send(config, simpler, false);
                }
            }
            log.error("{} request failed with HTTP {}: {}", label, status, providerMessage);
            throw new AiServiceException(messageForStatus(status, label), providerMessage, httpError);
        }

        return extractContent(rawBody);
    }

    /** User-facing message for a provider error status — specific enough to act on. */
    private static String messageForStatus(int status, String label) {
        return switch (status) {
            case 401 -> "AI is unavailable: " + label + " rejected the API key (Settings → AI provider).";
            case 402 -> "AI is unavailable: the " + label + " account is out of credits.";
            case 403 -> label + " declined this request.";
            case 404 -> "AI is unavailable: " + label + " doesn't recognise the configured model (Settings → AI provider).";
            case 408, 429 -> label + " is busy right now. Please try again in a minute.";
            case 400 -> label + " couldn't process this request (check the model in Settings → AI provider).";
            default -> "The AI service returned an error. Please try again.";
        };
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return true; // network/timeout errors
    }

    private static WebClientResponseException findHttpError(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException wcre) return wcre;
            if (t.getCause() == t) break;
        }
        return null;
    }

    /**
     * The provider's own one-line explanation, for logs and "Test connection".
     * Only {@code error.message} is extracted — never the request, never the
     * full body — and it is truncated.
     */
    private String providerMessage(WebClientResponseException e) {
        try {
            JsonNode root = objectMapper.readTree(e.getResponseBodyAsString());
            JsonNode error = root.path("error");
            String message = error.isTextual() ? error.asText() : error.path("message").asText("");
            if (message.isBlank()) return null;
            return message.length() > 300 ? message.substring(0, 300) + "…" : message;
        } catch (Exception ignored) {
            return null;
        }
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
            @JsonProperty("max_tokens") Integer maxTokens,
            @JsonProperty("max_completion_tokens") Integer maxCompletionTokens
    ) {
        /** A copy without the optional parameter the provider complained about, or null if none applies. */
        ChatRequest withoutParameterNamedIn(String providerMessage) {
            if (providerMessage == null) return null;
            String message = providerMessage.toLowerCase(Locale.ROOT);
            if (temperature != null && message.contains("temperature")) {
                return new ChatRequest(model, messages, responseFormat, null, maxTokens, maxCompletionTokens);
            }
            if (responseFormat != null && message.contains("response_format")) {
                return new ChatRequest(model, messages, null, temperature, maxTokens, maxCompletionTokens);
            }
            if (maxTokens != null && message.contains("max_tokens")) {
                return new ChatRequest(model, messages, responseFormat, temperature, null, maxTokens);
            }
            return null;
        }
    }

    private record ChatMessage(String role, String content) {}

    private record ResponseFormat(String type) {}
}
