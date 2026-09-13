package com.inboxiq.ai.dto;

import java.util.List;

/**
 * Validated, type-safe view of the analysis JSON the LLM returns. Built by
 * {@link com.inboxiq.ai.AiResponseParser#parseAnalysis}, which never lets a
 * malformed/partial LLM response reach the rest of the app as a raw map —
 * every field here has already been coerced to a safe value or defaulted.
 */
public record EmailAnalysisAiResult(
        String summary,
        List<String> keyPoints,
        String category,
        String priorityHint,
        int riskScore,
        String riskLevel,
        List<String> riskReasons,
        boolean requiresReply,
        boolean actionRequired,
        List<ActionItemAiResult> actionItems,
        List<String> importantDates,
        List<String> reasoning
) {
    public record ActionItemAiResult(String description, String deadline) {}
}
