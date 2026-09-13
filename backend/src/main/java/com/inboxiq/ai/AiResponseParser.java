package com.inboxiq.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inboxiq.ai.dto.EmailAnalysisAiResult;
import com.inboxiq.ai.dto.ReplyAiResult;
import com.inboxiq.ai.dto.ThreadSummaryAiResult;
import com.inboxiq.entity.EmailCategory;
import com.inboxiq.entity.Priority;
import com.inboxiq.entity.RiskLevel;
import com.inboxiq.exception.AiServiceException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a raw LLM text response into validated result records. This is the
 * app's defense against "never blindly trust malformed LLM JSON": every
 * value is defensively extracted with a safe fallback, enum-like strings are
 * matched case-insensitively against the real enum (falling back to a
 * sensible default rather than throwing on an unrecognized value), numeric
 * scores are clamped into range, and dates that don't parse are dropped
 * rather than propagated as garbage. Only a response that isn't parseable
 * JSON at all raises {@link AiServiceException}, for the caller to apply a
 * rule-based fallback.
 */
@Component
public class AiResponseParser {

    private final ObjectMapper objectMapper;

    public AiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EmailAnalysisAiResult parseAnalysis(String raw) {
        JsonNode node = parseJson(raw);

        List<String> keyPoints = textArray(node, "keyPoints");
        List<String> riskReasons = textArray(node, "riskReasons");
        List<String> importantDates = validDatesOnly(textArray(node, "importantDates"));
        List<String> reasoning = textArray(node, "reasoning");

        List<EmailAnalysisAiResult.ActionItemAiResult> actionItems = new ArrayList<>();
        if (node.path("actionItems").isArray()) {
            for (JsonNode item : node.path("actionItems")) {
                String description = text(item, "description", null);
                if (description == null || description.isBlank()) continue;
                String deadline = text(item, "deadline", null);
                actionItems.add(new EmailAnalysisAiResult.ActionItemAiResult(
                        description, isValidIsoDate(deadline) ? deadline : null));
            }
        }

        return new EmailAnalysisAiResult(
                text(node, "summary", "No summary available."),
                keyPoints,
                safeEnum(text(node, "category", null), EmailCategory.class, EmailCategory.OTHER).name(),
                safeEnum(text(node, "priorityHint", null), Priority.class, Priority.MEDIUM).name(),
                clamp(intValue(node, "riskScore", 0), 0, 100),
                safeEnum(text(node, "riskLevel", null), RiskLevel.class, RiskLevel.LOW).name(),
                riskReasons,
                boolValue(node, "requiresReply", false),
                boolValue(node, "actionRequired", false),
                actionItems,
                importantDates,
                reasoning
        );
    }

    public ReplyAiResult parseReply(String raw) {
        JsonNode node = parseJson(raw);
        String reply = text(node, "reply", null);
        if (reply == null || reply.isBlank()) {
            throw new AiServiceException("The AI did not return a reply draft.");
        }
        return new ReplyAiResult(reply.strip());
    }

    public ThreadSummaryAiResult parseThreadSummary(String raw) {
        JsonNode node = parseJson(raw);
        return new ThreadSummaryAiResult(
                text(node, "summary", "No summary available."),
                textArray(node, "keyPoints"),
                textArray(node, "openQuestions")
        );
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiServiceException("The AI returned an empty response.");
        }
        String cleaned = stripMarkdownFences(raw.strip());
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            throw new AiServiceException("The AI returned a response that could not be parsed as JSON.", e);
        }
    }

    /** Models occasionally wrap JSON in ```json ... ``` despite instructions not to. */
    private String stripMarkdownFences(String s) {
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return s.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return s;
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : fallback;
    }

    private int intValue(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : fallback;
    }

    private boolean boolValue(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : fallback;
    }

    private List<String> textArray(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        JsonNode arr = node.path(field);
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }

    private List<String> validDatesOnly(List<String> dates) {
        List<String> result = new ArrayList<>();
        for (String d : dates) {
            if (isValidIsoDate(d)) result.add(d);
        }
        return result;
    }

    private boolean isValidIsoDate(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private <E extends Enum<E>> E safeEnum(String value, Class<E> enumType, E fallback) {
        if (value == null) return fallback;
        for (E constant : enumType.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value.trim())) {
                return constant;
            }
        }
        return fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
