package com.inboxiq.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Server-Sent Events hub: one long-lived stream per open browser tab,
 * grouped by user, so every event reaches exactly the user it belongs to.
 * Streams are opened only through the authenticated {@code GET /api/events}
 * endpoint, which derives the user from the session — never from anything
 * the client sends.
 *
 * Streams are recycled every few minutes; the browser's EventSource
 * reconnects on its own and the frontend refetches the inbox on reconnect,
 * so an event missed during a reconnect is never lost for good. Kept in
 * memory, like RateLimiterService: correct for the single-instance
 * deployment this app uses (a multi-instance setup would fan out through a
 * shared broker such as Postgres LISTEN/NOTIFY or Redis).
 */
@Service
public class EventStreamService {

    private static final Logger log = LoggerFactory.getLogger(EventStreamService.class);

    /** Recycle streams well within typical proxy limits; the browser reconnects transparently. */
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(10).toMillis();
    private static final long RECONNECT_DELAY_MS = 3_000;
    /** Several tabs are fine; an unbounded number would just leak. */
    private static final int MAX_STREAMS_PER_USER = 6;

    private final Map<UUID, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final AtomicLong eventIds = new AtomicLong();

    public SseEmitter open(UUID userId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            remove(userId, emitter);
            emitter.complete();
        });
        emitter.onError(error -> remove(userId, emitter));

        List<SseEmitter> evicted = new ArrayList<>();
        streams.compute(userId, (id, list) -> {
            List<SseEmitter> current = list != null ? list : new CopyOnWriteArrayList<>();
            current.add(emitter);
            while (current.size() > MAX_STREAMS_PER_USER) {
                evicted.add(current.remove(0));
            }
            return current;
        });
        // Outside compute(): completing fires callbacks that touch the map.
        evicted.forEach(SseEmitter::complete);

        send(userId, emitter, SseEmitter.event()
                .name(RealtimeEvent.CONNECTED)
                .reconnectTime(RECONNECT_DELAY_MS)
                .data(Map.of("serverTime", Instant.now().toString()), MediaType.APPLICATION_JSON));
        return emitter;
    }

    /** Sends an event to every open stream of this user; a no-op when they have none. */
    public void publish(UUID userId, String event, Object payload) {
        List<SseEmitter> userStreams = streams.get(userId);
        if (userStreams == null || userStreams.isEmpty()) return;
        String id = Long.toString(eventIds.incrementAndGet());
        for (SseEmitter emitter : userStreams) {
            send(userId, emitter, SseEmitter.event().id(id).name(event).data(payload, MediaType.APPLICATION_JSON));
        }
    }

    /**
     * Publishes once the current transaction commits (immediately when there
     * is none), so the browser never hears about a row it can't read yet.
     */
    public void publishAfterCommit(UUID userId, String event, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(userId, event, payload);
                }
            });
        } else {
            publish(userId, event, payload);
        }
    }

    public boolean isConnected(UUID userId) {
        List<SseEmitter> userStreams = streams.get(userId);
        return userStreams != null && !userStreams.isEmpty();
    }

    /** Users with InboxIQ open right now — the mailboxes worth polling. */
    public Set<UUID> connectedUserIds() {
        return streams.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** Keeps idle connections from being cut by proxies, and detects dead ones. */
    @Scheduled(fixedRate = 20_000, initialDelay = 20_000)
    public void heartbeat() {
        streams.forEach((userId, userStreams) -> {
            for (SseEmitter emitter : userStreams) {
                send(userId, emitter, SseEmitter.event().comment("ping"));
            }
        });
    }

    private void send(UUID userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (Exception e) {
            // The tab went away (closed, network drop). The container reports
            // the broken connection on its own; just stop writing to it.
            log.debug("Dropping a closed event stream for user {}", userId);
            remove(userId, emitter);
        }
    }

    private void remove(UUID userId, SseEmitter emitter) {
        streams.computeIfPresent(userId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
