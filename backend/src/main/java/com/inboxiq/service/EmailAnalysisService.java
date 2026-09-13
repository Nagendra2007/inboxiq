package com.inboxiq.service;

import com.inboxiq.ai.AiClient;
import com.inboxiq.ai.AiResponseParser;
import com.inboxiq.ai.dto.EmailAnalysisAiResult;
import com.inboxiq.ai.prompt.EmailAnalysisPrompt;
import com.inboxiq.ai.prompt.PromptPair;
import com.inboxiq.config.AppProperties;
import com.inboxiq.entity.ActionItem;
import com.inboxiq.entity.AnalysisStatus;
import com.inboxiq.entity.EmailAnalysis;
import com.inboxiq.entity.EmailCategory;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.Priority;
import com.inboxiq.entity.RiskLevel;
import com.inboxiq.exception.AiServiceException;
import com.inboxiq.repository.ActionItemRepository;
import com.inboxiq.repository.EmailAnalysisRepository;
import com.inboxiq.repository.EmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the full analysis pass for one email: one combined AI call
 * (summary + category + priority hint + risk + action items + dates, per
 * the spec's cost-optimization rule — never five separate calls) blended
 * with deterministic rule-based signals ({@link PriorityEngine},
 * {@link RiskRuleEngine}), persisted as one upserted {@link EmailAnalysis}
 * row.
 *
 * If the AI call fails outright (provider down, no API key configured,
 * unparseable response), analysis does not fail silently or leave nothing
 * for the user: rule-based signals still run and are stored, the row is
 * marked {@link AnalysisStatus#FAILED} with a user-safe reason, and the
 * email remains fully usable — just without an AI summary/category.
 */
@Service
public class EmailAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(EmailAnalysisService.class);

    private final AiClient aiClient;
    private final AiResponseParser aiResponseParser;
    private final PriorityEngine priorityEngine;
    private final RiskRuleEngine riskRuleEngine;
    private final AppProperties appProperties;
    private final EmailAnalysisRepository emailAnalysisRepository;
    private final ActionItemRepository actionItemRepository;
    private final EmailRepository emailRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public EmailAnalysisService(AiClient aiClient,
                                 AiResponseParser aiResponseParser,
                                 PriorityEngine priorityEngine,
                                 RiskRuleEngine riskRuleEngine,
                                 AppProperties appProperties,
                                 EmailAnalysisRepository emailAnalysisRepository,
                                 ActionItemRepository actionItemRepository,
                                 EmailRepository emailRepository,
                                 ObjectMapper objectMapper,
                                 PlatformTransactionManager transactionManager) {
        this.aiClient = aiClient;
        this.aiResponseParser = aiResponseParser;
        this.priorityEngine = priorityEngine;
        this.riskRuleEngine = riskRuleEngine;
        this.appProperties = appProperties;
        this.emailAnalysisRepository = emailAnalysisRepository;
        this.actionItemRepository = actionItemRepository;
        this.emailRepository = emailRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Fire-and-forget entry point used right after a new message is synced,
     * so the sync request itself never blocks on an LLM round trip. This is
     * a distinct bean-level method (not a self-invocation of {@link #analyze})
     * so Spring's async proxy actually intercepts the call.
     *
     * Note that {@code this::analyze} below IS a self-invocation, so
     * {@code analyze}'s {@code @Transactional} does not apply on this path.
     * That's deliberate — it keeps a pooled DB connection from being held for
     * the whole LLM round trip — and is why the writes inside {@code analyze}
     * run through {@link #transactionTemplate} explicitly.
     */
    @Async
    public void analyzeAsync(UUID emailId) {
        emailRepository.findById(emailId).ifPresent(this::analyze);
    }

    @Transactional
    public EmailAnalysis analyze(EmailMessage email) {
        EmailAnalysis analysis = emailAnalysisRepository.findByEmailId(email.getId())
                .orElseGet(() -> {
                    EmailAnalysis created = new EmailAnalysis();
                    created.setEmail(email);
                    return created;
                });

        String bodyForAnalysis = email.getBodyText() != null ? email.getBodyText() : plainFromHtml(email.getBodyHtml());
        RiskRuleEngine.Result ruleRisk = riskRuleEngine.evaluate(email.getSender(), email.getSubject(), bodyForAnalysis);

        try {
            PromptPair prompt = EmailAnalysisPrompt.build(email.getSender(), email.getSubject(), bodyForAnalysis);
            String raw = aiClient.complete(prompt.systemPrompt(), prompt.userPrompt(), appProperties.getAi().getModel(), true);
            EmailAnalysisAiResult aiResult = aiResponseParser.parseAnalysis(raw);

            applyAiResult(analysis, aiResult, ruleRisk, email.getSubject(), bodyForAnalysis);
            analysis.setAnalysisStatus(AnalysisStatus.COMPLETED);
            analysis.setFailureReason(null);
            analysis.setAiModelUsed(appProperties.getAi().getModel());

            // One short transaction for the analysis row plus its action
            // items (joins the caller's transaction when there is one). The
            // derived delete in replaceActionItems requires an active
            // transaction; on the async path there otherwise isn't one.
            return transactionTemplate.execute(status -> {
                EmailAnalysis saved = emailAnalysisRepository.save(analysis);
                replaceActionItems(email, aiResult.actionItems());
                return saved;
            });

        } catch (AiServiceException e) {
            log.warn("AI analysis failed for email id={}, falling back to rule-based signals only", email.getId());
            applyFallback(analysis, ruleRisk);
            analysis.setAnalysisStatus(AnalysisStatus.FAILED);
            analysis.setFailureReason("AI analysis is temporarily unavailable; showing rule-based signals only.");
            return emailAnalysisRepository.save(analysis);
        }
    }

    private void applyAiResult(EmailAnalysis analysis, EmailAnalysisAiResult ai, RiskRuleEngine.Result ruleRisk,
                                String subject, String bodyForAnalysis) {
        analysis.setSummary(ai.summary());
        analysis.setKeyPointsJson(toJson(ai.keyPoints()));
        EmailCategory category = safeCategory(ai.category());
        analysis.setCategory(category);

        Priority aiPriority = safePriority(ai.priorityHint());
        PriorityEngine.Result priorityResult = priorityEngine.score(
                aiPriority, category, ai.requiresReply(), ai.actionRequired(),
                ai.importantDates(), subject, bodyForAnalysis);
        analysis.setPriority(priorityResult.priority());
        analysis.setPriorityScore(priorityResult.score());

        // Blend: the AI's own risk assessment plus independent rule-based
        // signals, weighted so neither source alone can suppress a genuine risk.
        int blendedScore = Math.max(ai.riskScore(), ruleRisk.score());
        analysis.setRiskScore(blendedScore);
        analysis.setRiskLevel(riskLevelFor(blendedScore));

        java.util.List<String> combinedReasons = new java.util.ArrayList<>(ai.riskReasons());
        combinedReasons.addAll(ruleRisk.reasons());
        analysis.setRiskReasonsJson(toJson(combinedReasons));

        analysis.setRequiresReply(ai.requiresReply());
        analysis.setActionRequired(ai.actionRequired());
        analysis.setImportantDatesJson(toJson(ai.importantDates()));
    }

    /** No AI result available: still surface rule-based risk and a neutral default priority. */
    private void applyFallback(EmailAnalysis analysis, RiskRuleEngine.Result ruleRisk) {
        // Summary is intentionally left as-is (null on first-ever failed
        // analysis, or whatever a prior successful run stored) — there is
        // nothing safe to synthesize for free-text summary without the AI.
        if (analysis.getCategory() == null) {
            analysis.setCategory(EmailCategory.OTHER);
        }
        if (analysis.getPriority() == null) {
            analysis.setPriority(Priority.MEDIUM);
            analysis.setPriorityScore(45);
        }
        analysis.setRiskScore(ruleRisk.score());
        analysis.setRiskLevel(riskLevelFor(ruleRisk.score()));
        analysis.setRiskReasonsJson(toJson(ruleRisk.reasons()));
        if (analysis.getRequiresReply() == null) analysis.setRequiresReply(false);
        if (analysis.getActionRequired() == null) analysis.setActionRequired(false);
        if (analysis.getImportantDatesJson() == null) analysis.setImportantDatesJson("[]");
        if (analysis.getKeyPointsJson() == null) analysis.setKeyPointsJson("[]");
    }

    private void replaceActionItems(EmailMessage email, List<EmailAnalysisAiResult.ActionItemAiResult> aiItems) {
        // Only items the user hasn't already checked off get replaced, so a
        // re-analysis never resurrects something the user marked done.
        actionItemRepository.deleteByEmailIdAndCompletedFalse(email.getId());
        if (aiItems == null || aiItems.isEmpty()) return;
        for (EmailAnalysisAiResult.ActionItemAiResult item : aiItems) {
            ActionItem entity = new ActionItem();
            entity.setEmail(email);
            entity.setDescription(truncate(item.description(), 500));
            if (item.deadline() != null) {
                try {
                    entity.setDeadline(LocalDate.parse(item.deadline()));
                } catch (Exception ignored) {
                    // Already validated by AiResponseParser; defensive no-op.
                }
            }
            actionItemRepository.save(entity);
        }
    }

    private RiskLevel riskLevelFor(int score) {
        if (score >= 70) return RiskLevel.HIGH;
        if (score >= 35) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    private EmailCategory safeCategory(String value) {
        try {
            return EmailCategory.valueOf(value);
        } catch (Exception e) {
            return EmailCategory.OTHER;
        }
    }

    private Priority safePriority(String value) {
        try {
            return Priority.valueOf(value);
        } catch (Exception e) {
            return Priority.MEDIUM;
        }
    }

    private String plainFromHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }
}
