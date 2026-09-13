package com.inboxiq.ai;

import java.util.List;
import java.util.Locale;

/**
 * AI providers InboxIQ can talk to. Every one of them exposes an
 * OpenAI-compatible {@code /chat/completions} endpoint, so a single client
 * ({@link OpenAiCompatibleClient}) serves them all — switching provider is a
 * matter of base URL, API key, and model name.
 *
 * The suggested models are only hints for the settings form; any model the
 * provider offers can be typed in and checked with "Test connection".
 */
public enum AiProvider {

    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys",
            List.of("openai/gpt-4o-mini", "google/gemini-2.5-flash", "meta-llama/llama-3.3-70b-instruct",
                    "nvidia/nemotron-3-super-120b-a12b:free")),
    OPENAI("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys",
            List.of("gpt-4o-mini", "gpt-4.1-mini", "gpt-4o")),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey",
            List.of("gemini-2.5-flash", "gemini-2.5-pro")),
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com/v1", "https://console.anthropic.com/settings/keys",
            List.of("claude-haiku-4-5-20251001", "claude-sonnet-5")),
    GROQ("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys",
            List.of("llama-3.3-70b-versatile", "llama-3.1-8b-instant")),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys",
            List.of("deepseek-chat")),
    MISTRAL("Mistral", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys",
            List.of("mistral-small-latest", "mistral-large-latest")),
    /** Any other OpenAI-compatible endpoint (a gateway, Azure-style proxy, or a local Ollama). */
    CUSTOM("Custom (OpenAI-compatible)", null, null, List.of());

    private final String label;
    private final String baseUrl;
    private final String keyUrl;
    private final List<String> suggestedModels;

    AiProvider(String label, String baseUrl, String keyUrl, List<String> suggestedModels) {
        this.label = label;
        this.baseUrl = baseUrl;
        this.keyUrl = keyUrl;
        this.suggestedModels = suggestedModels;
    }

    public String label() { return label; }
    /** Fixed endpoint for presets; null for CUSTOM, which takes one from settings. */
    public String baseUrl() { return baseUrl; }
    public String keyUrl() { return keyUrl; }
    public List<String> suggestedModels() { return suggestedModels; }
    public boolean requiresBaseUrl() { return this == CUSTOM; }

    /** Parses a provider id ("openrouter", "OpenAI", ...); blank means OpenRouter. */
    public static AiProvider fromId(String id) {
        if (id == null || id.isBlank()) return OPENROUTER;
        return AiProvider.valueOf(id.trim().toUpperCase(Locale.ROOT));
    }
}
