package com.inboxiq.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inboxiq.config.AppProperties;
import com.inboxiq.exception.AiServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the client against a local fake of an OpenAI-compatible /chat/completions endpoint. */
class OpenAiCompatibleClientTest {

    private HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private final Deque<int[]> statuses = new ArrayDeque<>();
    private final Deque<String> bodies = new ArrayDeque<>();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /** Queue responses in order; the last one repeats. */
    private void respond(int status, String body) {
        statuses.add(new int[]{status});
        bodies.add(body);
    }

    private OpenAiCompatibleClient client(int maxRetries) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = statuses.size() > 1 ? statuses.poll()[0] : statuses.peek()[0];
            String body = bodies.size() > 1 ? bodies.poll() : bodies.peek();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        AppProperties properties = new AppProperties();
        properties.getAi().setMaxRetries(maxRetries);
        properties.getAi().setRequestTimeoutSeconds(5);
        return new OpenAiCompatibleClient(properties, new ObjectMapper());
    }

    private AiConfig config(AiProvider provider) {
        return new AiConfig(provider, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", "test/model");
    }

    private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";

    @Test
    void sendsKeyModelAndAnOutputCap() throws IOException {
        respond(200, OK);
        OpenAiCompatibleClient client = client(0);

        assertThat(client.complete(config(AiProvider.OPENROUTER), "system", "user", true)).isEqualTo("hello");
        assertThat(authHeaders.get(0)).isEqualTo("Bearer test-key");
        assertThat(requestBodies.get(0))
                .contains("\"model\":\"test/model\"")
                .contains("\"max_tokens\":1500")
                .contains("\"response_format\":{\"type\":\"json_object\"}");
    }

    @Test
    void openAiGetsMaxCompletionTokens() throws IOException {
        respond(200, OK);
        OpenAiCompatibleClient client = client(0);

        client.complete(config(AiProvider.OPENAI), "system", "user", false);
        assertThat(requestBodies.get(0)).contains("\"max_completion_tokens\":1500").doesNotContain("\"max_tokens\"");
    }

    @Test
    void retriesOnceWithoutAParameterTheModelRejects() throws IOException {
        respond(400, "{\"error\":{\"message\":\"Unsupported value: 'temperature' does not support 0.3\"}}");
        respond(200, OK);
        OpenAiCompatibleClient client = client(0);

        assertThat(client.complete(config(AiProvider.OPENAI), "system", "user", false)).isEqualTo("hello");
        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(0)).contains("\"temperature\"");
        assertThat(requestBodies.get(1)).doesNotContain("\"temperature\"");
    }

    @Test
    void outOfCreditsIsReportedWithTheProviderName() throws IOException {
        respond(402, "{\"error\":{\"message\":\"This request requires more credits\",\"code\":402}}");
        OpenAiCompatibleClient client = client(2);

        assertThatThrownBy(() -> client.complete(config(AiProvider.OPENROUTER), "system", "user", true))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("OpenRouter account is out of credits")
                .extracting(e -> ((AiServiceException) e).getProviderDetail())
                .isEqualTo("This request requires more credits");
        assertThat(requestBodies).as("402 is not retried").hasSize(1);
    }

    @Test
    void rejectedKeyIsReportedAsSuch() throws IOException {
        respond(401, "{\"error\":{\"message\":\"Invalid API key\"}}");
        OpenAiCompatibleClient client = client(0);

        assertThatThrownBy(() -> client.complete(config(AiProvider.GROQ), "system", "user", false))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Groq rejected the API key");
    }

    @Test
    void rateLimitIsStillRecognisedAfterRetriesAreExhausted() throws IOException {
        respond(429, "{\"error\":{\"message\":\"Rate limit exceeded\"}}");
        OpenAiCompatibleClient client = client(1);

        assertThatThrownBy(() -> client.complete(config(AiProvider.OPENROUTER), "system", "user", false))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("busy right now");
        assertThat(requestBodies).as("one retry, then give up").hasSize(2);
    }

    @Test
    void missingKeyFailsFastWithoutCallingTheProvider() throws IOException {
        respond(200, OK);
        OpenAiCompatibleClient client = client(0);
        AiConfig noKey = new AiConfig(AiProvider.GEMINI, config(AiProvider.GEMINI).baseUrl(), null, "gemini-2.5-flash");

        assertThatThrownBy(() -> client.complete(noKey, "system", "user", false))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("no API key");
        assertThat(requestBodies).isEmpty();
    }

    @Test
    void malformedResponseKeepsItsOwnMessage() throws IOException {
        respond(200, "{\"choices\":[]}");
        OpenAiCompatibleClient client = client(0);

        assertThatThrownBy(() -> client.complete(config(AiProvider.OPENROUTER), "system", "user", false))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("unexpected response shape");
    }
}
