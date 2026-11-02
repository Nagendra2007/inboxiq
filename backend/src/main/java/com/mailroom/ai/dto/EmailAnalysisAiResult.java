package com.mailroom.ai.dto;

import java.util.List;

/**
 * Validated, type-safe view of the analysis JSON the LLM returns. Built by
 * {@link com.mailroom.ai.AiResponseParser#parseAnalysis}, which never lets a
 * malformed/partial LLM response reach the rest of the app as a raw map —
 * every field here has already been coerced to a safe value or defaulted.
 */
public record EmailAnalysisAiResult(
        String summary,
        List<String> keyPoints,
        /** One line written for a lock screen, or null to fall back to the summary. */
        String notificationText,
        String category,
        String priorityHint,
        int riskScore,
        String riskLevel,
        List<String> riskReasons,
        /** A one-time code to type in somewhere, or null. Verified against the
         *  email text before use, because a model can invent a plausible one. */
        String otpCode,
        boolean requiresReply,
        boolean actionRequired,
        List<ActionItemAiResult> actionItems,
        List<String> importantDates,
        List<String> reasoning
) {
    public record ActionItemAiResult(String description, String deadline) {}
}
