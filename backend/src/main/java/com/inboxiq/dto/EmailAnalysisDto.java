package com.inboxiq.dto;

import java.util.List;

/**
 * API-facing view of {@link com.inboxiq.entity.EmailAnalysis}. JSON list
 * columns are decoded here so the frontend never has to parse embedded JSON
 * strings itself.
 */
public record EmailAnalysisDto(
        String summary,
        List<String> keyPoints,
        String category,
        String priority,
        Integer priorityScore,
        Integer riskScore,
        String riskLevel,
        List<String> riskReasons,
        Boolean requiresReply,
        Boolean actionRequired,
        List<String> importantDates,
        String analysisStatus,
        String failureReason
) {}
