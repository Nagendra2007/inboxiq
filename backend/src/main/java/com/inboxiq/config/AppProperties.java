package com.inboxiq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Root binder for the {@code app.*} configuration tree in application.yml.
 * Kept as one binder (with nested sections) so every application-level
 * setting has a single, typed, IDE-discoverable home instead of being
 * scattered across {@code @Value} injections.
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Frontend frontend = new Frontend();
    private final Security security = new Security();
    private final Ai ai = new Ai();
    private final Gmail gmail = new Gmail();
    private final Sync sync = new Sync();
    private final RateLimit rateLimit = new RateLimit();
    /**
     * Comma-separated Google account emails allowed to change app-wide
     * settings (the AI provider). When empty, the first account created on
     * the deployment is the administrator — see AdminAccess.
     */
    private String adminEmails = "";

    public String getAdminEmails() { return adminEmails; }
    public void setAdminEmails(String adminEmails) { this.adminEmails = adminEmails == null ? "" : adminEmails; }

    public Frontend getFrontend() { return frontend; }
    public Security getSecurity() { return security; }
    public Ai getAi() { return ai; }
    public Gmail getGmail() { return gmail; }
    public Sync getSync() { return sync; }
    public RateLimit getRateLimit() { return rateLimit; }

    public static class Frontend {
        private String baseUrl = "http://localhost:5173";
        public String getBaseUrl() { return baseUrl; }
        // Handlers append paths like "/inbox", so drop any trailing slash.
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl == null ? null : baseUrl.trim().replaceAll("/+$", ""); }
    }

    public static class Security {
        private String tokenEncryptionKey;
        private String corsAllowedOrigins = "http://localhost:5173";
        private String sessionCookieName = "INBOXIQ_SESSION";
        private long sessionMaxAgeSeconds = 604_800;
        // "Lax" is correct whenever the SPA and API share one origin: local
        // dev (Vite's proxy) and the single-service production image. In
        // production also set COOKIE_SECURE=true. A split-domain deployment
        // would need SameSite=None + Secure, but the SPA then can't read the
        // XSRF-TOKEN cookie and Safari/Firefox block the session cookie as
        // third-party — which is why DEPLOYMENT.md uses a single origin.
        private String cookieSameSite = "Lax";
        private boolean cookieSecure = false;

        public String getTokenEncryptionKey() { return tokenEncryptionKey; }
        public void setTokenEncryptionKey(String tokenEncryptionKey) { this.tokenEncryptionKey = tokenEncryptionKey; }
        public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
        public void setCorsAllowedOrigins(String corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
        public String getSessionCookieName() { return sessionCookieName; }
        public void setSessionCookieName(String sessionCookieName) { this.sessionCookieName = sessionCookieName; }
        public long getSessionMaxAgeSeconds() { return sessionMaxAgeSeconds; }
        public void setSessionMaxAgeSeconds(long sessionMaxAgeSeconds) { this.sessionMaxAgeSeconds = sessionMaxAgeSeconds; }
        public String getCookieSameSite() { return cookieSameSite; }
        public void setCookieSameSite(String cookieSameSite) { this.cookieSameSite = cookieSameSite; }
        public boolean isCookieSecure() { return cookieSecure; }
        public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
    }

    /**
     * Environment defaults for AI. The administrator can override all of it
     * in the app (Settings → AI provider); see AiSettingsService.
     */
    public static class Ai {
        private String provider = "openrouter";
        /** Blank means the provider's standard endpoint (required for "custom"). */
        private String baseUrl = "";
        private String apiKey;
        private String model;
        private String fastModel;
        private int requestTimeoutSeconds = 30;
        private int maxRetries = 2;
        // Upper bound on each response's length. Without one, OpenRouter
        // reserves credit for the model's maximum output (16k tokens for
        // gpt-4o-mini) on every call, which fails with HTTP 402 on a
        // low-balance account even though real responses are tiny.
        private int maxOutputTokens = 1500;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getFastModel() { return fastModel; }
        public void setFastModel(String fastModel) { this.fastModel = fastModel; }
        /** The fast/cheap model for trivial calls, falling back to the main model when unset. */
        public String getEffectiveFastModel() {
            return (fastModel == null || fastModel.isBlank()) ? model : fastModel;
        }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    }

    public static class Gmail {
        private int initialSyncPageSize = 25;
        // A brand-new account's very first sync only needs to feel instant
        // and show something — the full history isn't needed yet.
        private int firstSyncMessageCap = 20;
        // Once an account has synced at least once, later syncs pull in
        // everything since they connected, up to this lifetime cap per
        // account (keeps Gmail-API-quota and storage usage bounded).
        private int maxTotalSyncedMessages = 600;

        public int getInitialSyncPageSize() { return initialSyncPageSize; }
        public void setInitialSyncPageSize(int initialSyncPageSize) { this.initialSyncPageSize = initialSyncPageSize; }
        public int getFirstSyncMessageCap() { return firstSyncMessageCap; }
        public void setFirstSyncMessageCap(int firstSyncMessageCap) { this.firstSyncMessageCap = firstSyncMessageCap; }
        public int getMaxTotalSyncedMessages() { return maxTotalSyncedMessages; }
        public void setMaxTotalSyncedMessages(int maxTotalSyncedMessages) { this.maxTotalSyncedMessages = maxTotalSyncedMessages; }
    }

    public static class Sync {
        private boolean reanalyzeIfStale = false;
        public boolean isReanalyzeIfStale() { return reanalyzeIfStale; }
        public void setReanalyzeIfStale(boolean reanalyzeIfStale) { this.reanalyzeIfStale = reanalyzeIfStale; }
    }

    public static class RateLimit {
        private int aiRequestsPerMinutePerUser = 20;
        private int gmailSyncRequestsPerMinutePerUser = 6;

        public int getAiRequestsPerMinutePerUser() { return aiRequestsPerMinutePerUser; }
        public void setAiRequestsPerMinutePerUser(int v) { this.aiRequestsPerMinutePerUser = v; }
        public int getGmailSyncRequestsPerMinutePerUser() { return gmailSyncRequestsPerMinutePerUser; }
        public void setGmailSyncRequestsPerMinutePerUser(int v) { this.gmailSyncRequestsPerMinutePerUser = v; }
    }
}
