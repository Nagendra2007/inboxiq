package com.mailroom.controller;

import com.mailroom.dto.DeviceRegisterRequest;
import com.mailroom.entity.User;
import com.mailroom.security.CurrentUserProvider;
import com.mailroom.service.DeviceNotificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Where the Android app registers for notifications.
 *
 * <p>Authenticated by the same session cookie as everything else: the app
 * is a WebView, so it already holds one, and its page-initiated requests
 * carry it without the app ever seeing the value.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final CurrentUserProvider currentUserProvider;
    private final DeviceNotificationService devices;

    public DeviceController(CurrentUserProvider currentUserProvider, DeviceNotificationService devices) {
        this.currentUserProvider = currentUserProvider;
        this.devices = devices;
    }

    /** Whether this deployment can notify an app at all, so it can say so. */
    @GetMapping("/config")
    public Map<String, Boolean> config() {
        return Map.of("enabled", devices.isEnabled());
    }

    @PostMapping("/register")
    public Map<String, Boolean> register(@Valid @RequestBody DeviceRegisterRequest request) {
        User user = currentUserProvider.getCurrentUser();
        devices.register(user, request.token(), request.platform());
        return Map.of("registered", true);
    }

    @PostMapping("/unregister")
    public Map<String, Boolean> unregister(@RequestBody DeviceRegisterRequest request) {
        currentUserProvider.getCurrentUser();
        devices.unregister(request.token());
        return Map.of("registered", false);
    }

    @PostMapping("/test")
    public Map<String, Boolean> test() {
        devices.sendTest(currentUserProvider.getCurrentUser().getId());
        return Map.of("sent", true);
    }

    /**
     * A one-time code notification, on demand.
     *
     * <p>Codes travel on their own path — their own message shape, their
     * own channel, their own layout on the phone — and that path could
     * only ever be exercised by waiting for an email that happened to
     * contain a code. When it went wrong there was no way to tell a
     * failure to find the code from a failure to display it.
     */
    @PostMapping("/test-code")
    public Map<String, Boolean> testCode() {
        devices.sendTestCode(currentUserProvider.getCurrentUser().getId());
        return Map.of("sent", true);
    }
}
