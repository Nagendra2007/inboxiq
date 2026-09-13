package com.inboxiq.ai;

/**
 * Provider-agnostic abstraction over "send a system+user prompt to an LLM,
 * get text back". Which provider, key and model to use is passed in per call
 * as an {@link AiConfig} — normally {@code AiSettingsService#current()}, the
 * administrator's app-wide choice — so nothing outside this package knows
 * which provider is involved.
 */
public interface AiClient {

    /**
     * @param config        provider, endpoint, key and model for this call
     * @param systemPrompt  fixed instructions the model must follow (never
     *                      derived from email content)
     * @param userPrompt    the task-specific content, including any
     *                      untrusted email text — always wrapped as clearly
     *                      delimited DATA by the prompt builder, never
     *                      concatenated directly into instructions
     * @param jsonResponse  true to request the provider's structured/JSON
     *                      output mode when supported
     */
    String complete(AiConfig config, String systemPrompt, String userPrompt, boolean jsonResponse);
}
