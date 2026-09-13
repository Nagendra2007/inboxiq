package com.inboxiq.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inboxiq.ai.dto.EmailAnalysisAiResult;
import com.inboxiq.ai.dto.ReplyAiResult;
import com.inboxiq.entity.EmailCategory;
import com.inboxiq.entity.Priority;
import com.inboxiq.entity.RiskLevel;
import com.inboxiq.exception.AiServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The AI response parser is the app's single defense against malformed or
 * hostile LLM output ever reaching persistence, so it is tested against
 * garbage, not just the happy path.
 */
class AiResponseParserTest {

    private final AiResponseParser parser = new AiResponseParser(new ObjectMapper());

    @Test
    void parsesAWellFormedAnalysisResponse() {
        String json = """
                {
                  "summary": "Reminder about the project deadline.",
                  "keyPoints": ["Deadline is Friday", "Reply needed"],
                  "category": "WORK",
                  "priorityHint": "HIGH",
                  "riskScore": 5,
                  "riskLevel": "LOW",
                  "riskReasons": [],
                  "requiresReply": true,
                  "actionRequired": true,
                  "actionItems": [{"description": "Submit report", "deadline": "2026-09-20"}],
                  "importantDates": ["2026-09-20"],
                  "reasoning": ["Explicit deadline mentioned"]
                }
                """;

        EmailAnalysisAiResult result = parser.parseAnalysis(json);

        assertThat(result.summary()).isEqualTo("Reminder about the project deadline.");
        assertThat(result.category()).isEqualTo(EmailCategory.WORK.name());
        assertThat(result.priorityHint()).isEqualTo(Priority.HIGH.name());
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW.name());
        assertThat(result.actionItems()).hasSize(1);
        assertThat(result.actionItems().get(0).deadline()).isEqualTo("2026-09-20");
        assertThat(result.importantDates()).containsExactly("2026-09-20");
    }

    @Test
    void stripsMarkdownCodeFencesBeforeParsing() {
        String fenced = "```json\n{\"summary\": \"ok\"}\n```";
        EmailAnalysisAiResult result = parser.parseAnalysis(fenced);
        assertThat(result.summary()).isEqualTo("ok");
    }

    @Test
    void fallsBackToSafeDefaultsForUnknownEnumValues() {
        String json = """
                {"summary": "x", "category": "NOT_A_REAL_CATEGORY", "priorityHint": "SUPER_URGENT", "riskLevel": "BOGUS"}
                """;
        EmailAnalysisAiResult result = parser.parseAnalysis(json);

        assertThat(result.category()).isEqualTo(EmailCategory.OTHER.name());
        assertThat(result.priorityHint()).isEqualTo(Priority.MEDIUM.name());
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW.name());
    }

    @Test
    void clampsAnOutOfRangeRiskScoreIntoZeroToHundred() {
        String json = """
                {"summary": "x", "riskScore": 999}
                """;
        assertThat(parser.parseAnalysis(json).riskScore()).isEqualTo(100);

        String negative = """
                {"summary": "x", "riskScore": -50}
                """;
        assertThat(parser.parseAnalysis(negative).riskScore()).isEqualTo(0);
    }

    @Test
    void dropsUnparseableDatesRatherThanPropagatingThem() {
        String json = """
                {"summary": "x", "importantDates": ["2026-09-20", "not-a-date", "next Friday"]}
                """;
        assertThat(parser.parseAnalysis(json).importantDates()).containsExactly("2026-09-20");
    }

    @Test
    void skipsActionItemsMissingADescription() {
        String json = """
                {"summary": "x", "actionItems": [{"description": "", "deadline": null}, {"description": "Do the thing"}]}
                """;
        assertThat(parser.parseAnalysis(json).actionItems()).extracting("description")
                .containsExactly("Do the thing");
    }

    @Test
    void throwsAiServiceExceptionForCompletelyUnparseableJson() {
        assertThatThrownBy(() -> parser.parseAnalysis("not json at all {{{"))
                .isInstanceOf(AiServiceException.class);
    }

    @Test
    void throwsAiServiceExceptionForAnEmptyResponse() {
        assertThatThrownBy(() -> parser.parseAnalysis("")).isInstanceOf(AiServiceException.class);
        assertThatThrownBy(() -> parser.parseAnalysis(null)).isInstanceOf(AiServiceException.class);
    }

    @Test
    void parsesAReplyAndRejectsAMissingOne() {
        ReplyAiResult ok = parser.parseReply("{\"reply\": \"Sounds good, see you then.\"}");
        assertThat(ok.reply()).isEqualTo("Sounds good, see you then.");

        assertThatThrownBy(() -> parser.parseReply("{\"reply\": \"\"}"))
                .isInstanceOf(AiServiceException.class);
        assertThatThrownBy(() -> parser.parseReply("{}"))
                .isInstanceOf(AiServiceException.class);
    }
}
