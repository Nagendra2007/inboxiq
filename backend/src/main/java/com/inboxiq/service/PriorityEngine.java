package com.inboxiq.service;

import com.inboxiq.entity.EmailCategory;
import com.inboxiq.entity.Priority;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Hybrid priority scoring: the AI's {@code priorityHint} is only one input,
 * not the final answer (see {@code EmailAnalysisPrompt}). This engine
 * combines it with deterministic, explainable rule-based signals — an
 * upcoming deadline, urgency language, an explicit "requires reply" flag —
 * so priority doesn't silently change just because the model's mood does on
 * a re-run, and so the reasoning behind a HIGH label is always inspectable.
 *
 * Score bands (0-100), fixed by the spec:
 *   HIGH   80-100
 *   MEDIUM 40-79
 *   LOW    0-39
 */
@Component
public class PriorityEngine {

    private static final Pattern URGENT_WORDS = Pattern.compile(
            "\\b(urgent|asap|immediately|action required|time[- ]sensitive|deadline|" +
            "final notice|last chance|expires? (today|tomorrow|soon)|overdue)\\b",
            Pattern.CASE_INSENSITIVE);

    public record Result(int score, Priority priority, List<String> reasons) {}

    public Result score(Priority aiPriorityHint, EmailCategory category, boolean requiresReply,
                         boolean actionRequired, List<String> importantDates,
                         String subject, String bodyText) {

        java.util.List<String> reasons = new java.util.ArrayList<>();
        int score = baseScoreFor(aiPriorityHint);
        reasons.add("AI priority hint: " + (aiPriorityHint == null ? "MEDIUM (default)" : aiPriorityHint));

        if (actionRequired) {
            score += 15;
            reasons.add("Email indicates an action is required (+15)");
        }
        if (requiresReply) {
            score += 10;
            reasons.add("Email appears to expect a reply (+10)");
        }
        if (hasUpcomingDeadline(importantDates)) {
            score += 15;
            reasons.add("Contains a date within the next 3 days (+15)");
        } else if (importantDates != null && !importantDates.isEmpty()) {
            score += 5;
            reasons.add("Contains a future date (+5)");
        }
        if (containsUrgentLanguage(subject) || containsUrgentLanguage(bodyText)) {
            score += 10;
            reasons.add("Contains urgency language (+10)");
        }
        if (category == EmailCategory.SECURITY) {
            score += 10;
            reasons.add("Category is Security (+10)");
        } else if (category == EmailCategory.FINANCE) {
            score += 5;
            reasons.add("Category is Finance (+5)");
        } else if (category == EmailCategory.MARKETING || category == EmailCategory.NEWSLETTER) {
            score -= 15;
            reasons.add("Category is Marketing/Newsletter (-15)");
        }

        score = clamp(score, 0, 100);
        return new Result(score, bandFor(score), reasons);
    }

    private int baseScoreFor(Priority hint) {
        if (hint == null) return 45;
        return switch (hint) {
            case HIGH -> 70;
            case MEDIUM -> 45;
            case LOW -> 20;
        };
    }

    private Priority bandFor(int score) {
        if (score >= 80) return Priority.HIGH;
        if (score >= 40) return Priority.MEDIUM;
        return Priority.LOW;
    }

    private boolean containsUrgentLanguage(String s) {
        return s != null && URGENT_WORDS.matcher(s).find();
    }

    private boolean hasUpcomingDeadline(List<String> isoDates) {
        if (isoDates == null || isoDates.isEmpty()) return false;
        LocalDate today = LocalDate.now();
        LocalDate soon = today.plusDays(3);
        for (String d : isoDates) {
            try {
                LocalDate date = LocalDate.parse(d);
                if (!date.isBefore(today) && !date.isAfter(soon)) {
                    return true;
                }
            } catch (Exception ignored) {
                // Already validated upstream by AiResponseParser; defensive no-op here.
            }
        }
        return false;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
