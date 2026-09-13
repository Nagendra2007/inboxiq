package com.inboxiq.ai.prompt;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Builds the single structured prompt used for the whole analysis pass —
 * summary, category, priority hint, risk assessment, and action/date
 * extraction all come back from one LLM call (see README "LLM cost
 * optimization") instead of five separate ones.
 */
public final class EmailAnalysisPrompt {

    private static final int MAX_BODY_CHARS = 12_000;

    private EmailAnalysisPrompt() {}

    public static PromptPair build(String sender, String subject, String bodyText) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        String system = PromptSafety.INJECTION_GUARD + "\n" + """
                You are an email analysis engine for InboxIQ, an email intelligence \
                assistant. Analyze the single email provided below and return ONLY a \
                JSON object matching exactly this schema — no markdown fences, no \
                commentary, no extra keys:

                {
                  "summary": string (2-4 plain-language sentences),
                  "keyPoints": string[] (short bullet phrases, at most 5),
                  "category": one of PERSONAL, WORK, EDUCATION, FINANCE, SHOPPING, DELIVERY, SECURITY, SOCIAL, MARKETING, NEWSLETTER, SUSPICIOUS, OTHER,
                  "priorityHint": one of HIGH, MEDIUM, LOW — your best-guess priority; the application combines this with rule-based signals (sender, deadlines, keywords) to compute the final priority, so this is a hint, not the final answer,
                  "riskScore": integer 0-100, higher means more likely to be a scam, phishing, or otherwise suspicious,
                  "riskLevel": one of LOW, MEDIUM, HIGH,
                  "riskReasons": string[] (short reasons behind the risk score; empty array if none apply),
                  "requiresReply": boolean,
                  "actionRequired": boolean,
                  "actionItems": [ { "description": string, "deadline": "YYYY-MM-DD" or null } ],
                  "importantDates": string[] (each formatted "YYYY-MM-DD"),
                  "reasoning": string[] (short phrases explaining the classification)
                }

                Today's date is %s — resolve relative dates ("tomorrow", "next Friday", \
                "in two weeks") against it. If you cannot confidently resolve a relative \
                date to a specific calendar date, omit it rather than guessing.

                Phrase any risk assessment as a possibility for the user to consider, \
                never as a certainty ("appears potentially suspicious", not "this is a \
                scam"). Consider, when present: sender/domain anomalies, urgency or \
                threat language, requests for credentials/payment/account verification, \
                suspicious or mismatched links, and generic greetings on a claimed \
                personal or financial matter.
                """.formatted(today);

        String user = "SENDER: " + PromptSafety.delimit("SENDER", sender)
                + "\nSUBJECT: " + PromptSafety.delimit("SUBJECT", subject)
                + "\nBODY:\n" + PromptSafety.delimit("EMAIL BODY", truncate(bodyText));

        return new PromptPair(system, user);
    }

    private static String truncate(String body) {
        if (body == null) return "";
        if (body.length() <= MAX_BODY_CHARS) return body;
        return body.substring(0, MAX_BODY_CHARS) + "\n[...truncated for length...]";
    }
}
