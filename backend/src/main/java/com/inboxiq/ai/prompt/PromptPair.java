package com.inboxiq.ai.prompt;

/** A system prompt (fixed instructions) paired with a user prompt (task + untrusted data). */
public record PromptPair(String systemPrompt, String userPrompt) {}
