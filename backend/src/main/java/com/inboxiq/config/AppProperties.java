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
        /** Background analyses running at once (see AnalysisQueue). */
        private int analysisConcurrency = 2;
        /** Automatic attempts per email before a failed analysis is left for a manual retry. */
        private int analysisMaxAttempts = 3;

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
        public int getAnalysisConcurrency() { return analysisConcurrency; }
        public void setAnalysisConcurrency(int analysisConcurrency) { this.analysisConcurrency = analysisConcurrency; }
        public int getAnalysisMaxAttempts() { return analysisMaxAttempts; }
        public void setAnalysisMaxAttempts(int analysisMaxAttempts) { this.analysisMaxAttempts = analysisMaxAttempts; }
    }

    /** Gmail sync. See EmailSyncService for how first and incremental syncs differ. */
    public static class Gmail {
        // The first sync of a mailbox fetches only the newest N inbox messages,
        // whatever the mailbox size. Later syncs are incremental (history API).
        private int firstSyncMessageCap = 20;
        // Upper bound on new messages stored by one incremental pass (e.g.
        // after a long absence): the newest ones are kept.
        private int maxNewMessagesPerSync = 100;
        // Mailboxes of users with InboxIQ open are checked this often.
        private int pollIntervalSeconds = 30;
        // Mailboxes of users who are away are checked this often, so new mail
        // is fetched and analyzed while the app is closed. 0 disables it.
        private int backgroundPollMinutes = 5;
        // Background checks stop for a mailbox nobody has used in this long
        // (signing in again revives it). Keeps an abandoned deployment from
        // spending Gmail quota and AI credits indefinitely.
        private int backgroundActiveDays = 30;
        // Upper bound on mailboxes started per background tick.
        private int backgroundBatchSize = 50;
        private int fetchConcurrency = 4;

        public int getFirstSyncMessageCap() { return firstSyncMessageCap; }
        public void setFirstSyncMessageCap(int firstSyncMessageCap) { this.firstSyncMessageCap = firstSyncMessageCap; }
        public int getMaxNewMessagesPerSync() { return maxNewMessagesPerSync; }
        public void setMaxNewMessagesPerSync(int maxNewMessagesPerSync) { this.maxNewMessagesPerSync = maxNewMessagesPerSync; }
        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
        public int getBackgroundPollMinutes() { return backgroundPollMinutes; }
        public void setBackgroundPollMinutes(int backgroundPollMinutes) { this.backgroundPollMinutes = backgroundPollMinutes; }
        public int getBackgroundActiveDays() { return backgroundActiveDays; }
        public void setBackgroundActiveDays(int backgroundActiveDays) { this.backgroundActiveDays = backgroundActiveDays; }
        public int getBackgroundBatchSize() { return backgroundBatchSize; }
        public void setBackgroundBatchSize(int backgroundBatchSize) { this.backgroundBatchSize = backgroundBatchSize; }
        public int getFetchConcurrency() { return fetchConcurrency; }
        public void setFetchConcurrency(int fetchConcurrency) { this.fetchConcurrency = fetchConcurrency; }
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
