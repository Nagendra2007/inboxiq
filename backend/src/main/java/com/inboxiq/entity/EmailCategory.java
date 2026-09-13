package com.inboxiq.entity;

/**
 * Coarse-grained email categories used for filtering/dashboard grouping.
 * Adding a new category is additive: append a constant here, add it to the
 * allowed-values list in {@code EmailAnalysisPrompt}, and (optionally) give
 * it an icon in the frontend's category map.
 */
public enum EmailCategory {
    PERSONAL,
    WORK,
    EDUCATION,
    FINANCE,
    SHOPPING,
    DELIVERY,
    SECURITY,
    SOCIAL,
    MARKETING,
    NEWSLETTER,
    SUSPICIOUS,
    OTHER
}
