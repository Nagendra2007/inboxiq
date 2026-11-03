package com.mailroom.ai.dto;

import java.util.List;

/**
 * A question, turned into a search. Every field has already been coerced
 * to something safe by {@link com.mailroom.ai.AiResponseParser}: a model
 * that returns nonsense here produces a broad search, never an error.
 */
public record ChatPlanAiResult(
        List<String> keywords,
        List<String> senders,
        String category,
        String after,
        String before,
        boolean unreadOnly,
        String intent
) {}
