package com.mailroom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailroom.config.AppProperties;
import com.mailroom.entity.PushSubscription;
import com.mailroom.entity.User;
import com.mailroom.repository.PushSubscriptionRepository;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Security;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends browser notifications, so Mailroom can say something useful while
 * nobody has it open.
 *
 * <p>Web push needs no third party and costs nothing: the browser vendor's
 * own push service does the delivery, and a VAPID key pair is how it knows
 * the message came from this application. The private half lives in config
 * and never leaves the server; the public half is handed to the browser so
 * it can address a subscription back to us.
 *
 * <p>Disabled entirely when no keys are configured, which is how it runs
 * locally and in tests.
 *
 * <p>One subscription per browser, and people have several. A notification
 * therefore fans out, and a dead endpoint is deleted rather than retried:
 * a 404 or 410 from a push service means the browser threw the
 * subscription away, which is permanent and not worth keeping a row for.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    static {
        // The Web Push payload encryption is elliptic-curve, which the JDK
        // does not provide in the shape this library wants.
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    private final PushSubscriptionRepository subscriptions;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private volatile PushService pushService;

    public PushNotificationService(PushSubscriptionRepository subscriptions,
                                   AppProperties appProperties,
                                   ObjectMapper objectMapper) {
        this.subscriptions = subscriptions;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        AppProperties.Push push = appProperties.getPush();
        return notBlank(push.getPublicKey()) && notBlank(push.getPrivateKey());
    }

    public String publicKey() {
        return appProperties.getPush().getPublicKey();
    }

    /**
     * Fans a notification out to every browser this user has subscribed.
     * Never throws: a notification that cannot be delivered must not take
     * down whatever was happening when it was raised.
     */
    @Transactional
    public void notifyUser(UUID userId, String title, String body, String url, String tag) {
        if (!isEnabled()) return;
        List<PushSubscription> targets = subscriptions.findByUserId(userId);
        if (targets.isEmpty()) return;

        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "title", title, "body", body, "url", url, "tag", tag));
        } catch (Exception e) {
            log.warn("Could not build a push payload: {}", e.getMessage());
            return;
        }

        for (PushSubscription target : targets) {
            send(target, payload);
        }
    }

    private void send(PushSubscription target, String payload) {
        try {
            nl.martijndwars.webpush.Subscription.Keys keys =
                    new nl.martijndwars.webpush.Subscription.Keys(target.getP256dh(), target.getAuth());
            nl.martijndwars.webpush.Subscription sub =
                    new nl.martijndwars.webpush.Subscription(target.getEndpoint(), keys);

            int status = service().send(new Notification(sub, payload)).getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                // The browser discarded this subscription. It will never
                // come back, so neither should the row.
                subscriptions.delete(target);
                log.info("Dropped an expired push subscription");
            } else if (status >= 400) {
                log.warn("Push rejected with status {}", status);
            } else {
                target.setLastUsedAt(Instant.now());
                subscriptions.save(target);
            }
        } catch (Exception e) {
            log.warn("Push delivery failed: {}", e.toString());
        }
    }

    private PushService service() throws Exception {
        PushService existing = pushService;
        if (existing != null) return existing;
        synchronized (this) {
            if (pushService == null) {
                AppProperties.Push push = appProperties.getPush();
                pushService = new PushService(push.getPublicKey(), push.getPrivateKey(), push.getSubject());
            }
            return pushService;
        }
    }

    /** Registers or refreshes one browser's subscription. */
    @Transactional
    public void subscribe(User user, String endpoint, String p256dh, String auth) {
        PushSubscription sub = subscriptions.findByEndpoint(endpoint).orElseGet(PushSubscription::new);
        sub.setUser(user);
        sub.setEndpoint(endpoint);
        sub.setP256dh(p256dh);
        sub.setAuth(auth);
        subscriptions.save(sub);
    }

    @Transactional
    public void unsubscribe(String endpoint) {
        subscriptions.deleteByEndpoint(endpoint);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
