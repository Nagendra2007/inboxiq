package com.mailroom.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailroom.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns text into a vector, through any OpenAI-compatible
 * {@code /embeddings} endpoint.
 *
 * <p>Deliberately separate from {@link AiClient}'s configuration, because
 * the provider that answers questions usually cannot do this: OpenRouter
 * and Groq both serve chat completions and neither serves embeddings. So
 * semantic search is off unless its own key and model are configured, and
 * the application says which at startup rather than quietly behaving
 * differently from the way it is documented.
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Enough for a batch, short enough not to time out a background pass. */
    static final int MAX_BATCH = 64;

    private final AppProperties appProperties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(AppProperties appProperties, WebClient.Builder webClientBuilder,
                           ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * A key is not part of this. Some endpoints want one and some are
     * reached another way, and refusing to work without one would disable
     * the feature on a perfectly good gateway.
     */
    public boolean isEnabled() {
        AppProperties.Embeddings config = appProperties.getEmbeddings();
        return notBlank(config.getModel()) && notBlank(config.getBaseUrl());
    }

    public String model() {
        return appProperties.getEmbeddings().getModel();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportConfiguration() {
        if (isEnabled()) {
            log.info("Semantic mail search is on, embedding with {}", model());
        } else {
            // Said out loud because the feature degrades rather than fails:
            // questions still get answered from keyword search, just less
            // well, and nothing on screen would ever mention it.
            log.info("Semantic mail search is off: no embedding model is configured. "
                    + "Questions will be answered from keyword search alone.");
        }
    }

    /**
     * Embeds a batch, in order.
     *
     * @return one vector per input, or an empty list if the call failed —
     *         never a partial list, because the caller matches results to
     *         inputs by position
     */
    public List<float[]> embed(List<String> texts) {
        if (!isEnabled() || texts.isEmpty()) return List.of();
        if (texts.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Batch of " + texts.size() + " exceeds " + MAX_BATCH);
        }

        AppProperties.Embeddings config = appProperties.getEmbeddings();
        try {
            return call(config, texts, config.getDimensions());
        } catch (Exception first) {
            // "dimensions" is an OpenAI extension. A gateway in front of
            // the same model may reject the whole request for an argument
            // it does not know, and the vectors are perfectly usable at
            // whatever size it returns — so losing the feature over an
            // optional parameter would be a poor trade.
            if (config.getDimensions() > 0) {
                try {
                    List<float[]> vectors = call(config, texts, 0);
                    log.info("This endpoint does not accept 'dimensions'; using the model's own size");
                    dimensionsRejected = true;
                    return vectors;
                } catch (Exception second) {
                    log.warn("Embedding failed: {}", second.toString());
                    return List.of();
                }
            }
            // Never fatal: a question is still answerable without this.
            log.warn("Embedding failed: {}", first.toString());
            return List.of();
        }
    }

    /** Remembered so the rejected parameter is not re-sent on every batch. */
    private volatile boolean dimensionsRejected = false;

    private List<float[]> call(AppProperties.Embeddings config, List<String> texts, int dimensions)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("input", texts);
        if (dimensions > 0 && !dimensionsRejected) body.put("dimensions", dimensions);

        WebClient.RequestBodySpec request = webClientBuilder.build().post()
                .uri(config.getBaseUrl() + "/embeddings")
                .contentType(MediaType.APPLICATION_JSON);
        if (notBlank(config.getApiKey())) {
            request = request.header("Authorization", "Bearer " + config.getApiKey());
        }

        String response = request
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT);

        JsonNode data = objectMapper.readTree(response).path("data");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new IllegalStateException(
                    "embedding response had " + data.size() + " vectors for " + texts.size() + " inputs");
        }

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (JsonNode item : data) {
            JsonNode values = item.path("embedding");
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) vector[i] = (float) values.get(i).asDouble();
            vectors.add(normalise(vector));
        }
        return vectors;
    }

    /**
     * Scales to unit length, so similarity is a dot product.
     *
     * <p>Doing it once here rather than at every comparison is the
     * difference between one square root per email and one per email per
     * question.
     */
    static float[] normalise(float[] vector) {
        double sum = 0;
        for (float value : vector) sum += (double) value * value;
        double length = Math.sqrt(sum);
        if (length == 0) return vector;
        for (int i = 0; i < vector.length; i++) vector[i] = (float) (vector[i] / length);
        return vector;
    }

    /** Similarity of two unit vectors: -1 to 1, higher is closer. */
    public static double similarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1;
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += (double) a[i] * b[i];
        return sum;
    }

    /** Little-endian floats, which is what every machine this runs on uses. */
    public static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) buffer.putFloat(value);
        return buffer.array();
    }

    public static float[] fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
        return vector;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
