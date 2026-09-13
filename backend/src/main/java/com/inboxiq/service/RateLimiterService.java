package com.inboxiq.service;

import com.inboxiq.config.AppProperties;
import com.inboxiq.exception.ApiException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory, per-user token-bucket rate limiting for the two most
 * expensive/abusable operations: AI calls and Gmail sync requests. In-memory
 * is a deliberate, documented simplification for a single-instance
 * deployment (see README "Scaling notes") — a multi-instance deployment
 * would back this with Redis instead, which is why callers only see this
 * interface, not the storage mechanism.
 */
@Service
public class RateLimiterService {

    private final AppProperties appProperties;
    private final ConcurrentHashMap<UUID, Bucket> aiBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Bucket> gmailSyncBuckets = new ConcurrentHashMap<>();

    public RateLimiterService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public void checkAiRequest(UUID userId) {
        Bucket bucket = aiBuckets.computeIfAbsent(userId, id -> newBucket(appProperties.getRateLimit().getAiRequestsPerMinutePerUser()));
        consumeOrThrow(bucket, "AI request rate limit reached. Please wait a moment and try again.");
    }

    public void checkGmailSyncRequest(UUID userId) {
        Bucket bucket = gmailSyncBuckets.computeIfAbsent(userId, id -> newBucket(appProperties.getRateLimit().getGmailSyncRequestsPerMinutePerUser()));
        consumeOrThrow(bucket, "Gmail sync rate limit reached. Please wait a moment and try again.");
    }

    private Bucket newBucket(int perMinute) {
        Bandwidth limit = Bandwidth.classic(perMinute, Refill.greedy(perMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private void consumeOrThrow(Bucket bucket, String message) {
        if (!bucket.tryConsume(1)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
        }
    }
}
