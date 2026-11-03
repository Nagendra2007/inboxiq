package com.mailroom.ai.prompt;

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
                You are an email analysis engine for Mailroom, an email intelligence \
                assistant. Analyze the single email provided below and return ONLY a \
                JSON object matching exactly this schema — no markdown fences, no \
                commentary, no extra keys:

                {
                  "summary": string (one plain-language sentence and 25 words at the very most; a second sentence only if the email really has two separate asks — the key points below carry the detail, so say what the email is and what it wants, and stop),
                  "keyPoints": string[] (short bullet phrases, at most 5),
                  "notificationText": string (one line for a phone's lock screen, 12 words at the absolute most — what this email actually wants, phrased so someone glancing at it knows whether to pick the phone up. Do NOT name the sender; the notification already shows it. Do not start with "This email" or "The sender". Prefer the concrete detail over the abstraction: "Rent is due Friday, 14,500" beats "A payment reminder". For marketing and newsletters, say what is on offer in a few words rather than pretending it is urgent),
                  "category": one of PERSONAL, WORK, EDUCATION, FINANCE, SHOPPING, DELIVERY, SECURITY, SOCIAL, MARKETING, NEWSLETTER, SUSPICIOUS, OTHER,
                  "priorityHint": one of HIGH, MEDIUM, LOW — your best-guess priority; the application combines this with rule-based signals (sender, deadlines, keywords) to compute the final priority, so this is a hint, not the final answer,
                  "riskScore": integer 0-100, higher means more likely to be a scam, phishing, or otherwise suspicious,
                  "riskLevel": one of LOW, MEDIUM, HIGH,
                  "riskReasons": string[] (short reasons behind the risk score; empty array if none apply),
                  "otpCode": string or null — a one-time code the reader is meant to type in somewhere: a verification code, OTP, 2FA code, login or confirmation code. Copy it EXACTLY as it appears, character for character. Null unless you are certain: an order number, invoice number, tracking number, reference, account number, price, date or year is NOT a one-time code, and neither is a discount or coupon code. Never reconstruct, correct or invent one — if you cannot see it verbatim in the text, return null.
                  "requiresReply": boolean,
                  "actionRequired": boolean,
                  "actionItems": [ { "description": string, "deadline": "YYYY-MM-DD" or null } ] — things the READER must go and do, which will not happen unless they do them, and which are still worth remembering an hour from now. Write each as an instruction to the reader: "Pay the hostel fee of 12,000", "Send the signed form back to the registrar". Almost every email has NONE, and an empty array is the right answer far more often than not,
                  "importantDates": string[] (each formatted "YYYY-MM-DD"),
                  "reasoning": string[] (short phrases explaining the classification)
                }

                Today's date is %s — resolve relative dates ("tomorrow", "next Friday", \
                "in two weeks") against it. If you cannot confidently resolve a relative \
                date to a specific calendar date, omit it rather than guessing.

                A to-do list people keep using is one they trust, and one wrong
                item costs more than ten missing ones. So actionItems is empty
                unless the email genuinely asks the reader to do something. NOT
                a to-do, ever:

                - typing a verification code, or anything else finished in the                 moment of reading: the reader is already doing it, and it will                 have expired by the time they look at a list
                - what the SENDER will do ("we will ship your order"), or what                 has already happened ("your payment was received")
                - reading, viewing, clicking, opening an attachment, "learn                 more", "shop now", "see details" — these are how one reads an                 email, not tasks
                - anything in marketing or a newsletter unless it names a real                 deadline the reader has chosen to care about
                - "no action required", or any variation of it. Return an empty                 array instead of saying there is nothing to do

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
