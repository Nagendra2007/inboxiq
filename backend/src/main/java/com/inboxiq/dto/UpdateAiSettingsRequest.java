package com.inboxiq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An administrator's AI settings change (also used, unsaved, by "Test
 * connection"). A blank {@code apiKey} keeps the saved key for the same
 * provider, or falls back to the environment's key.
 */
public record UpdateAiSettingsRequest(
        @NotBlank @Size(max = 32) String provider,
        @NotBlank @Size(max = 128) String model,
        @Size(max = 500) String apiKey,
        @Size(max = 500) String baseUrl
) {
    /** Never prints the key. */
    @Override
    public String toString() {
        return "UpdateAiSettingsRequest[provider=" + provider + ", model=" + model + ", baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "unchanged" : "***") + "]";
    }
}
