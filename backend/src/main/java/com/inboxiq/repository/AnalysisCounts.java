package com.inboxiq.repository;

/** The analysis-side dashboard totals, counted in one pass instead of six. */
public record AnalysisCounts(
        long highPriority,
        long mediumPriority,
        long lowPriority,
        long highRisk,
        long mediumRisk,
        long awaitingReply
) {}
