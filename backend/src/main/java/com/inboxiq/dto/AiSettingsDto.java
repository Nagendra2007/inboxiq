package com.inboxiq.dto;

import java.time.Instant;
import java.util.List;

/**
 * The app-wide AI configuration as shown to the administrator. Never carries
 * the API key itself — only whether one is set and its last four characters.
 *
 * @param source "APP" when set in Settings, "ENVIRONMENT" when coming from AI_* variables
 */
public record AiSettingsDto(
        String provider,
        String providerLabel,
        String model,
        String baseUrl,
        boolean hasApiKey,
        String apiKeyHint,
        boolean usingEnvironmentKey,
        String source,
        String updatedBy,
        Instant updatedAt,
        EnvironmentDefault environmentDefault,
        List<ProviderOption> providers
) {
    public record EnvironmentDefault(String provider, String providerLabel, String model, boolean hasApiKey) {}

    public record ProviderOption(String id, String label, String keyUrl, List<String> suggestedModels,
                                 boolean requiresBaseUrl) {}
}
