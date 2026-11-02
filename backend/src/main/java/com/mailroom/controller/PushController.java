package com.mailroom.controller;

import com.mailroom.dto.PushSubscribeRequest;
import com.mailroom.entity.User;
import com.mailroom.security.CurrentUserProvider;
import com.mailroom.service.PushNotificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Browser notification subscriptions.
 *
 * <p>The browser does the hard part: it asks the user for permission, talks
 * to its vendor's push service, and comes back with an endpoint and a pair
 * of keys. All this does is remember them against the signed-in user, and
 * hand out the public VAPID key the browser needs in order to ask.
 */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final CurrentUserProvider currentUserProvider;
    private final PushNotificationService pushService;

    public PushController(CurrentUserProvider currentUserProvider, PushNotificationService pushService) {
        this.currentUserProvider = currentUserProvider;
        this.pushService = pushService;
    }

    /**
     * What the browser needs before it can subscribe. Returns enabled:false
     * rather than an error when no keys are configured, so the UI can simply
     * not offer notifications instead of showing a failure.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> body = new HashMap<>();
        body.put("enabled", pushService.isEnabled());
        body.put("publicKey", pushService.isEnabled() ? pushService.publicKey() : null);
        return body;
    }

    @PostMapping("/subscribe")
    public Map<String, Boolean> subscribe(@Valid @RequestBody PushSubscribeRequest request) {
        User user = currentUserProvider.getCurrentUser();
        pushService.subscribe(user, request.endpoint(), request.p256dh(), request.auth());
        return Map.of("subscribed", true);
    }

    @PostMapping("/unsubscribe")
    public Map<String, Boolean> unsubscribe(@RequestBody PushSubscribeRequest request) {
        currentUserProvider.getCurrentUser();
        pushService.unsubscribe(request.endpoint());
        return Map.of("subscribed", false);
    }

    /** Sends one to this user, so the Settings toggle can prove it works. */
    @PostMapping("/test")
    public Map<String, Boolean> test() {
        User user = currentUserProvider.getCurrentUser();
        pushService.notifyUser(user.getId(), "Mailroom",
                "Notifications are working. This is what new mail will look like.",
                "/inbox", "test");
        return Map.of("sent", true);
    }
}
