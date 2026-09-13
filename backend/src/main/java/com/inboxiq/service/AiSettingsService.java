package com.inboxiq.service;

import com.inboxiq.ai.AiConfig;
import com.inboxiq.ai.AiProvider;
import com.inboxiq.config.AppProperties;
import com.inboxiq.dto.AiSettingsDto;
import com.inboxiq.dto.UpdateAiSettingsRequest;
import com.inboxiq.entity.AiSettings;
import com.inboxiq.exception.ApiException;
import com.inboxiq.repository.AiSettingsRepository;
import com.inboxiq.security.TokenEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The single, app-wide AI configuration every AI call uses.
 *
 * Precedence: the administrator's choice saved in the app (Settings → AI
 * provider) wins; until one is saved, the AI_* environment variables apply.
 * A saved choice without its own API key borrows the environment's key, but
 * only for the same provider — a key is never sent to a different provider.
 *
 * The resolved configuration is cached in memory (it's read on every AI
 * call and changes only when the admin saves) and refreshed on save/reset.
 * With more than one app instance, others pick up a change on restart.
 */
@Service
public class AiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);

    private final AiSettingsRepository repository;
    private final TokenEncryptionService encryption;
    private final AppProperties appProperties;

    private volatile AiConfig cached;

    public AiSettingsService(AiSettingsRepository repository, TokenEncryptionService encryption,
                             AppProperties appProperties) {
        this.repository = repository;
        this.encryption = encryption;
        this.appProperties = appProperties;
    }

    /** The configuration AI calls use right now. */
    public AiConfig current() {
        AiConfig config = cached;
        if (config == null) {
            config = load();
            cached = config;
        }
        return config;
    }

    /** What the AI_* environment variables say, ignoring anything saved in the app. */
    public AiConfig environmentDefault() {
        AppProperties.Ai ai = appProperties.getAi();
        AiProvider provider;
        try {
            provider = AiProvider.fromId(ai.getProvider());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown AI_PROVIDER '{}'; treating it as a custom OpenAI-compatible endpoint", ai.getProvider());
            provider = AiProvider.CUSTOM;
        }
        String baseUrl = hasText(ai.getBaseUrl()) ? ai.getBaseUrl() : provider.baseUrl();
        return new AiConfig(provider, normalizeUrl(baseUrl), trimToNull(ai.getApiKey()), trimToNull(ai.getModel()));
    }

    /** Validates and saves an administrator's change, then returns the resulting view. */
    public AiSettingsDto save(UpdateAiSettingsRequest request, String adminEmail) {
        Validated change = validate(request);
        AiSettings settings = repository.findById(AiSettings.SINGLETON_ID).orElseGet(AiSettings::new);

        String encryptedKey;
        if (change.newApiKey() != null) {
            encryptedKey = encryption.encrypt(change.newApiKey());
        } else if (change.keepStoredKey()) {
            encryptedKey = settings.getEncryptedApiKey();
        } else {
            encryptedKey = null; // use the environment's key
        }

        settings.setProvider(change.provider().name());
        settings.setModel(change.model());
        settings.setBaseUrl(change.provider() == AiProvider.CUSTOM ? change.baseUrl() : null);
        settings.setEncryptedApiKey(encryptedKey);
        settings.setUpdatedBy(adminEmail);
        repository.save(settings);
        cached = null;

        log.info("AI settings changed by {}: provider={}, model={}", adminEmail, change.provider(), change.model());
        return view();
    }

    /** Drops the saved choice; the environment variables apply again. */
    public void reset() {
        repository.deleteById(AiSettings.SINGLETON_ID);
        cached = null;
        log.info("AI settings reset to the environment defaults");
    }

    /** The configuration a change would produce, without saving it — for "Test connection". */
    public AiConfig candidate(UpdateAiSettingsRequest request) {
        Validated change = validate(request);
        String appKey = change.newApiKey() != null ? change.newApiKey()
                : change.keepStoredKey() ? storedKey().orElse(null)
                : null;
        return resolve(change.provider(), change.baseUrl(), change.model(), appKey);
    }

    public AiSettingsDto view() {
        Optional<AiSettings> stored = repository.findById(AiSettings.SINGLETON_ID);
        AiConfig effective = current();
        AiConfig env = environmentDefault();
        return new AiSettingsDto(
                effective.provider().name(),
                effective.provider().label(),
                effective.model(),
                effective.provider() == AiProvider.CUSTOM ? effective.baseUrl() : null,
                effective.hasApiKey(),
                keyHint(effective.apiKey()),
                stored.map(s -> s.getEncryptedApiKey() == null).orElse(true),
                stored.isPresent() ? "APP" : "ENVIRONMENT",
                stored.map(AiSettings::getUpdatedBy).orElse(null),
                stored.map(AiSettings::getUpdatedAt).orElse(null),
                new AiSettingsDto.EnvironmentDefault(env.provider().name(), env.provider().label(), env.model(), env.hasApiKey()),
                Arrays.stream(AiProvider.values())
                        .map(p -> new AiSettingsDto.ProviderOption(p.name(), p.label(), p.keyUrl(), p.suggestedModels(),
                                p.requiresBaseUrl()))
                        .toList());
    }

    // --- internals ---

    private record Validated(AiProvider provider, String baseUrl, String model, String newApiKey, boolean keepStoredKey) {}

    private Validated validate(UpdateAiSettingsRequest request) {
        AiProvider provider;
        try {
            provider = AiProvider.fromId(request.provider());
        } catch (IllegalArgumentException e) {
            throw badRequest("Unknown AI provider.");
        }
        String model = request.model().trim();

        String baseUrl = null;
        if (provider == AiProvider.CUSTOM) {
            baseUrl = normalizeUrl(request.baseUrl());
            if (baseUrl == null || !(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) {
                throw badRequest("Enter the endpoint's base URL, e.g. https://your-gateway.example.com/v1.");
            }
        }

        String newKey = trimToNull(request.apiKey());
        if (newKey != null) {
            return new Validated(provider, baseUrl, model, newKey, false);
        }

        // No key typed: keep the saved key if it belongs to this provider (and endpoint)…
        Optional<AiSettings> stored = repository.findById(AiSettings.SINGLETON_ID);
        boolean storedKeyFits = stored.isPresent()
                && stored.get().getEncryptedApiKey() != null
                && provider.name().equals(stored.get().getProvider())
                && (provider != AiProvider.CUSTOM || Objects.equals(baseUrl, normalizeUrl(stored.get().getBaseUrl())));
        if (storedKeyFits) {
            return new Validated(provider, baseUrl, model, null, true);
        }
        // …or fall back to the environment's key for the same provider.
        AiConfig env = environmentDefault();
        if ((provider == env.provider() && env.hasApiKey()) || provider == AiProvider.CUSTOM) {
            return new Validated(provider, baseUrl, model, null, false);
        }
        throw badRequest("Enter an API key for " + provider.label() + ".");
    }

    private AiConfig load() {
        Optional<AiSettings> stored = repository.findById(AiSettings.SINGLETON_ID);
        if (stored.isEmpty()) return environmentDefault();
        AiSettings settings = stored.get();
        try {
            AiProvider provider = AiProvider.fromId(settings.getProvider());
            String appKey = settings.getEncryptedApiKey() == null ? null : encryption.decrypt(settings.getEncryptedApiKey());
            return resolve(provider, settings.getBaseUrl(), settings.getModel(), appKey);
        } catch (RuntimeException e) {
            // Unknown provider, or TOKEN_ENCRYPTION_KEY changed since the key was saved.
            log.error("Saved AI settings can't be used ({}); falling back to the environment defaults",
                    e.getClass().getSimpleName());
            return environmentDefault();
        }
    }

    private Optional<String> storedKey() {
        return repository.findById(AiSettings.SINGLETON_ID)
                .map(AiSettings::getEncryptedApiKey)
                .map(encryption::decrypt);
    }

    /**
     * Turns a choice into a callable configuration. With its own key it uses
     * the provider's standard endpoint; without one it borrows the
     * environment's key and endpoint, but only for the same provider.
     */
    private AiConfig resolve(AiProvider provider, String customBaseUrl, String model, String appKey) {
        String custom = normalizeUrl(customBaseUrl);
        if (appKey != null) {
            return new AiConfig(provider, provider == AiProvider.CUSTOM ? custom : provider.baseUrl(), appKey, model);
        }
        AiConfig env = environmentDefault();
        boolean sameProvider = provider == env.provider();
        String baseUrl = provider == AiProvider.CUSTOM ? custom : sameProvider ? env.baseUrl() : provider.baseUrl();
        return new AiConfig(provider, baseUrl, sameProvider ? env.apiKey() : null, model);
    }

    private static String keyHint(String key) {
        return key != null && key.length() > 8 ? "…" + key.substring(key.length() - 4) : null;
    }

    private static String normalizeUrl(String url) {
        String trimmed = trimToNull(url);
        return trimmed == null ? null : trimmed.replaceAll("/+$", "");
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_SETTINGS", message);
    }
}
