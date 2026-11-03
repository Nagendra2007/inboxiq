package com.mailroom.ai;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keys an FCM data payload is allowed to use.
 *
 * <p>Codes stopped arriving on the phone while ordinary mail kept working,
 * and nothing anywhere reported a failure: the code payload used "from",
 * which FCM reserves, so Google rejected the message before it was ever
 * delivered. The app could not have shown what it never received, and the
 * server saw no exception.
 *
 * <p>This is a rule about strings in a map, invisible to the compiler and
 * to every other test, so it gets one of its own.
 */
class FcmPayloadTest {

    /**
     * From Firebase's own documentation: a data key may not be "from",
     * "message_type", "notification", or begin with "google" or "gcm".
     */
    private static boolean isReserved(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.equals("from")
                || lower.equals("message_type")
                || lower.equals("notification")
                || lower.startsWith("google")
                || lower.startsWith("gcm");
    }

    @Test
    void theReservedKeyCheckRecognisesTheOneThatBitUs() {
        assertThat(isReserved("from")).isTrue();
        assertThat(isReserved("message_type")).isTrue();
        assertThat(isReserved("google.sent_time")).isTrue();
        assertThat(isReserved("sender")).isFalse();
        assertThat(isReserved("code")).isFalse();
    }

    /**
     * Reads the payload keys straight out of the source rather than
     * sending anything: the failure was a literal in a {@code Map.of}, and
     * reaching it any other way needs a real Firebase project.
     */
    @Test
    void noPayloadUsesAReservedKey() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/mailroom/ai/FcmClient.java"));

        java.util.regex.Matcher calls = java.util.regex.Pattern
                .compile("post\\(deviceToken, Map\\.of\\(([^;]*?)\\)\\);", java.util.regex.Pattern.DOTALL)
                .matcher(source);

        int payloads = 0;
        while (calls.find()) {
            payloads++;
            java.util.regex.Matcher keys = java.util.regex.Pattern
                    .compile("\"([a-zA-Z_][a-zA-Z0-9_.]*)\"\\s*,")
                    .matcher(calls.group(1));
            while (keys.find()) {
                String key = keys.group(1);
                assertThat(isReserved(key))
                        .as("FCM rejects the whole message for the data key \"%s\"", key)
                        .isFalse();
            }
        }
        // If the payloads move somewhere this cannot see them, say so
        // rather than passing quietly.
        assertThat(payloads).as("payloads found in FcmClient").isGreaterThanOrEqualTo(2);
    }

    @Test
    void sendOtpStillTakesTheSenderItNeeds() throws Exception {
        Method sendOtp = FcmClient.class.getMethod(
                "sendOtp", String.class, String.class, String.class, String.class);
        assertThat(sendOtp.getParameterCount()).isEqualTo(4);
    }
}
