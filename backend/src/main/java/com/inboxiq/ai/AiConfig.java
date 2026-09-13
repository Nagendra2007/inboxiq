package com.inboxiq.ai;

/**
 * Everything needed to make one AI call: which provider, where, with which
 * key and model. Resolved by {@code AiSettingsService} from the admin's
 * in-app choice or the AI_* environment variables.
 */
public record AiConfig(AiProvider provider, String baseUrl, String apiKey, String model) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Never prints the key — records otherwise include every field in toString(). */
    @Override
    public String toString() {
        return "AiConfig[provider=" + provider + ", baseUrl=" + baseUrl + ", model=" + model
                + ", apiKey=" + (hasApiKey() ? "***" : "none") + "]";
    }
}
