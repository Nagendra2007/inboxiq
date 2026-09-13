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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the client against a local fake of OpenRouter's /chat/completions endpoint. */
class OpenRouterClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private OpenRouterClient clientAgainst(int status, String responseBody, int maxRetries) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        AppProperties properties = new AppProperties();
        properties.getAi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getAi().setApiKey("test-key");
        properties.getAi().setModel("test/model");
        properties.getAi().setMaxRetries(maxRetries);
        properties.getAi().setRequestTimeoutSeconds(5);
        return new OpenRouterClient(properties, new ObjectMapper());
    }

    @Test
    void capsTheResponseLengthSoOpenRouterDoesNotReserveTheModelMaximum() throws IOException {
        OpenRouterClient client = clientAgainst(200, "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}", 0);

        assertThat(client.complete("system", "user", null, false)).isEqualTo("hello");
        assertThat(lastRequestBody.get()).contains("\"max_tokens\":1500");
    }

    @Test
    void outOfCreditsIsReportedAsSuch() throws IOException {
        OpenRouterClient client = clientAgainst(402,
                "{\"error\":{\"message\":\"This request requires more credits\",\"code\":402}}", 2);

        assertThatThrownBy(() -> client.complete("system", "user", null, true))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("out of credits");
        assertThat(requestCount.get()).as("402 is not retried").isEqualTo(1);
    }

    @Test
    void rejectedKeyIsReportedAsSuch() throws IOException {
        OpenRouterClient client = clientAgainst(401, "{\"error\":{\"message\":\"No auth credentials found\"}}", 0);

        assertThatThrownBy(() -> client.complete("system", "user", null, false))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("API key was rejected");
    }

    @Test
    void rateLimitIsStillRecognisedAfterRetriesAreExhausted() throws IOException {
        OpenRouterClient client = clientAgainst(429, "{\"error\":{\"message\":\"Rate limit exceeded\"}}", 1);

        assertThatThrownBy(() -> client.complete("system", "user", null, false))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("busy right now");
        assertThat(requestCount.get()).as("one retry, then give up").isEqualTo(2);
    }

    @Test
    void malformedResponseKeepsItsOwnMessage() throws IOException {
        OpenRouterClient client = clientAgainst(200, "{\"choices\":[]}", 0);

        assertThatThrownBy(() -> client.complete("system", "user", null, false))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("unexpected response shape");
    }
}
