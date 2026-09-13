package com.inboxiq.ai;

/**
 * Provider-agnostic abstraction over "send a system+user prompt to an LLM,
 * get text back". {@link OpenRouterClient} is the only implementation today,
 * but nothing outside this package knows that OpenRouter is involved — swap
 * providers by adding a new implementation and changing which bean is
 * wired, no changes anywhere else in the codebase.
 */
public interface AiClient {

    /**
     * @param systemPrompt fixed instructions the model must follow (never
     *                      derived from email content)
     * @param userPrompt    the task-specific content, including any
     *                      untrusted email text — always wrapped as clearly
     *                      delimited DATA by the prompt builder, never
     *                      concatenated directly into instructions
     * @param model         which model to call (from AppProperties.ai) —
     *                      configurable, never hardcoded by a caller
     * @param jsonResponse  true to request the provider's structured/JSON
     *                      output mode when supported
     */
    String complete(String systemPrompt, String userPrompt, String model, boolean jsonResponse);
}
