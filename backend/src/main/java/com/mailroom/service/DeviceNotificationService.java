package com.mailroom.service;

import com.mailroom.ai.FcmClient;
import com.mailroom.entity.DeviceToken;
import com.mailroom.entity.User;
import com.mailroom.repository.DeviceTokenRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The Android app's half of notifications: the same decision as the
 * browser's, delivered through Firebase instead.
 *
 * <p>A person can have the website notifying a laptop and the app notifying
 * a phone at once, so both channels fire and each manages its own
 * registrations. A token Firebase reports as unregistered is deleted
 * immediately — the app was uninstalled, and that is permanent.
 */
@Service
public class DeviceNotificationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceNotificationService.class);

    private final DeviceTokenRepository tokens;
    private final FcmClient fcm;
    /** Runs the second attempt in its own transaction; see {@link #register}. */
    private final TransactionTemplate retry;

    public DeviceNotificationService(DeviceTokenRepository tokens, FcmClient fcm,
                                     PlatformTransactionManager transactionManager) {
        this.tokens = tokens;
        this.fcm = fcm;
        this.retry = new TransactionTemplate(transactionManager);
        this.retry.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean isEnabled() {
        return fcm.isEnabled();
    }

    /** Never throws: a failed notification must not fail whatever raised it. */
    @Transactional
    public void notifyUser(UUID userId, String title, String body, String url) {
        if (!isEnabled()) return;
        List<DeviceToken> devices = tokens.findByUserId(userId);
        if (devices.isEmpty()) {
            log.info("Nothing to notify: no app is registered for this account");
            return;
        }
        for (DeviceToken device : devices) {
            try {
                switch (fcm.send(device.getToken(), title, body, url)) {
                    case SENT -> {
                        device.setLastUsedAt(Instant.now());
                        tokens.save(device);
                    }
                    case UNREGISTERED -> {
                        tokens.delete(device);
                        log.info("Dropped an uninstalled device registration");
                    }
                    case FAILED -> { /* logged by the client; try again next time */ }
                }
            } catch (RuntimeException e) {
                log.warn("Device notification failed: {}", e.getMessage());
            }
        }
    }

    /**
     * A one-time code, sent so the app can give it the full-width treatment
     * and a Copy button. The code travels as its own field rather than
     * inside the body text, so the app never has to parse a sentence to get
     * at the thing it is going to put on the clipboard.
     */
    @Transactional
    public void notifyOtp(UUID userId, String code, String from, String url) {
        if (!isEnabled()) return;
        for (DeviceToken device : tokens.findByUserId(userId)) {
            try {
                switch (fcm.sendOtp(device.getToken(), code, from, url)) {
                    case SENT -> {
                        device.setLastUsedAt(Instant.now());
                        tokens.save(device);
                    }
                    case UNREGISTERED -> tokens.delete(device);
                    case FAILED -> { /* logged by the client */ }
                }
            } catch (RuntimeException e) {
                log.warn("Device OTP notification failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Registers or refreshes one installation. Google reissues tokens
     * freely, and the app re-offers the same one on every page load, so
     * this is called far more often than it does anything.
     *
     * <p>Two of those calls can overlap — the app registers both when a
     * page finishes loading and when it comes back to the foreground, and
     * those happen within milliseconds of each other on launch. Both then
     * find no row and both insert, and the unique index on the token means
     * one of them loses. Registering a device that is already registered
     * is not an error, so the loser re-reads and updates the row the
     * winner just wrote.
     */
    @Transactional
    public void register(User user, String token, String platform) {
        try {
            save(user, token, platform);
        } catch (DataIntegrityViolationException e) {
            // The row now exists because somebody else wrote it. Starting a
            // new transaction matters: this one is marked rollback-only.
            log.debug("Device registration raced with another; updating the existing row");
            retry.executeWithoutResult(status -> save(user, token, platform));
        }
    }

    private void save(User user, String token, String platform) {
        DeviceToken row = tokens.findByToken(token).orElseGet(DeviceToken::new);
        row.setUser(user);
        row.setToken(token);
        row.setPlatform(platform == null || platform.isBlank() ? "android" : platform);
        tokens.saveAndFlush(row);
    }

    @Transactional
    public void unregister(String token) {
        tokens.deleteByToken(token);
    }

    /** A single notification to this user's devices, for the app's test button. */
    @Transactional
    public void sendTest(UUID userId) {
        notifyUser(userId, "Mailroom", "Notifications are working on this device.", "/inbox");
    }
}
