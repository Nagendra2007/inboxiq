package com.mailroom;

import com.mailroom.entity.DeviceToken;
import com.mailroom.entity.User;
import com.mailroom.repository.DeviceTokenRepository;
import com.mailroom.repository.UserRepository;
import com.mailroom.service.DeviceNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registering a phone, against a real database.
 *
 * <p>This path had nothing covering it and returned a 500 in production
 * the first time a phone tried it, which the app could only report as a
 * number. The unit tests could not have caught it: what went wrong was the
 * schema, not the logic.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeviceRegistrationIntegrationTest {

    @Autowired private UserRepository users;
    @Autowired private DeviceTokenRepository tokens;
    @Autowired private DeviceNotificationService devices;

    private User newUser() {
        return users.save(new User("device-" + UUID.randomUUID() + "@example.com", "Device Test"));
    }

    /**
     * A real Firebase registration token: 163 characters, a colon, and the
     * URL-safe base64 alphabet. Length is the point — these do not fit in
     * the 255-character column an entity gets by default, and nothing in
     * the type system says so.
     */
    private static String firebaseToken() {
        StringBuilder token = new StringBuilder("fK9mQ2vXTnO3pL8wRcY1dZ:APA91b");
        while (token.length() < 163) {
            token.append("HqW-3nR_xT7kVm2Zp0LsDgYbQj8cF5aNvU4eI6oP1tXrM9hJyB");
        }
        return token.substring(0, 163);
    }

    @Test
    void aPhoneCanRegister() {
        User user = newUser();
        String token = firebaseToken();

        devices.register(user, token, "android");

        List<DeviceToken> stored = tokens.findByUserId(user.getId());
        assertThat(stored).hasSize(1);
        // Stored whole: a token truncated by the column is a token Firebase
        // will reject, and the rejection looks exactly like an uninstalled
        // app, so the row would be deleted and the phone would go quiet.
        assertThat(stored.get(0).getToken()).isEqualTo(token);
        assertThat(stored.get(0).getPlatform()).isEqualTo("android");
    }

    @Test
    void registeringTheSameTokenTwiceUpdatesTheOneRow() {
        User user = newUser();
        String token = firebaseToken();

        devices.register(user, token, "android");
        devices.register(user, token, "android");

        assertThat(tokens.findByUserId(user.getId())).hasSize(1);
    }

    @Test
    void aTokenThatMovesToAnotherAccountFollowsIt() {
        User first = newUser();
        User second = newUser();
        String token = firebaseToken();

        devices.register(first, token, "android");
        devices.register(second, token, "android");

        // One phone, one row: signing out and in as someone else must not
        // leave the previous account's notifications arriving on it.
        assertThat(tokens.findByUserId(first.getId())).isEmpty();
        assertThat(tokens.findByUserId(second.getId())).hasSize(1);
    }

    /**
     * The app registers both when a page finishes loading and when it
     * returns to the foreground, and on launch those land within
     * milliseconds of each other. Before this was handled, the second
     * insert hit the unique index on the token and the phone was told
     * "notifications could not be turned on (500)" — while the first
     * request had in fact succeeded.
     */
    @Test
    void twoRegistrationsAtOnceDoNotFail() throws Exception {
        User user = newUser();
        String token = firebaseToken();

        int threads = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Throwable>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                try {
                    go.await();
                    devices.register(user, token, "android");
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<Throwable> result : results) {
            assertThat(result.get()).as("a concurrent registration failed").isNull();
        }
        assertThat(tokens.findByUserId(user.getId())).hasSize(1);
    }

    @Test
    void aMissingPlatformDefaultsRatherThanFailing() {
        User user = newUser();

        devices.register(user, firebaseToken(), null);

        assertThat(tokens.findByUserId(user.getId()).get(0).getPlatform()).isEqualTo("android");
    }
}
