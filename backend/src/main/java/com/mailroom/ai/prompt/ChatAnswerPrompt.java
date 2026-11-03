package com.mailroom.ai.prompt;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Answers the question from the mail that was found.
 *
 * <p>This is the only step that sees email content, so it is the only one
 * an attacker can reach — anybody who can send the user an email can put
 * text in this prompt. Two things keep that contained, and neither is the
 * prompt itself.
 *
 * <p>The model has <b>no tools and no side effects</b>. It returns prose
 * and a list of ids. It cannot send, delete, archive or change anything,
 * so the worst an injected instruction can achieve is a wrong answer.
 *
 * <p>And its citations are <b>verified</b> against the candidates it was
 * given before anything is shown. The model chooses which emails answer
 * the question; it is never trusted with what the emails are.
 */
public final class ChatAnswerPrompt {

    private ChatAnswerPrompt() {}

    /** One email as the answering model sees it: never the full body. */
    public record Candidate(String id, String from, String date, String subject, String summary) {}

    public static PromptPair build(String question, List<Candidate> candidates, List<String> recentTurns) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        String system = PromptSafety.INJECTION_GUARD + "\n" + """
                You answer questions about the user's own email, using only the \
                emails listed below. Return ONLY a JSON object matching exactly \
                this schema — no markdown fences, no commentary, no extra keys:

                {
                  "answer": string — a direct reply in plain language, at most 4 sentences. Lead with the answer, not with what you did: "Three fee receipts, the most recent on 12 August for 45,000" rather than "I searched your mail and found...". Mention amounts, dates and senders when the emails give them, because that is usually the actual question. If the emails do not answer it, say so plainly and say what you did find,
                  "emailIds": string[] — the ids of the emails your answer rests on, most relevant first, at most 8. Only ids from the list below, copied exactly. Empty if none of them are relevant
                }

                Today is %s.

                Only cite an email you actually used. A list of everything that \
                was searched is not an answer, and padding it with near misses \
                makes the real matches harder to spot.

                Never state as fact anything the emails do not say. You are \
                reading a summary of each email, not the whole of it, so if the \
                detail asked for is not there, say that it is not rather than \
                inferring it.
                """.formatted(today);

        StringBuilder user = new StringBuilder();
        if (recentTurns != null && !recentTurns.isEmpty()) {
            user.append("EARLIER IN THIS CONVERSATION:\n");
            for (String turn : recentTurns) {
                user.append("- ").append(turn).append('\n');
            }
            user.append('\n');
        }

        user.append("QUESTION: ").append(question == null ? "" : question.trim()).append("\n\n");

        StringBuilder emails = new StringBuilder();
        for (Candidate candidate : candidates) {
            emails.append("id: ").append(candidate.id()).append('\n')
                    .append("from: ").append(nullSafe(candidate.from())).append('\n')
                    .append("date: ").append(nullSafe(candidate.date())).append('\n')
                    .append("subject: ").append(nullSafe(candidate.subject())).append('\n')
                    .append("summary: ").append(nullSafe(candidate.summary())).append('\n')
                    .append("---\n");
        }
        // The ids are ours and the rest is the senders'. All of it goes
        // inside the delimiters: an id is only useful to an attacker if
        // they can get the model to treat the surrounding text as
        // instructions, and that is exactly what this prevents.
        user.append(PromptSafety.delimit("EMAILS", emails.toString()));

        return new PromptPair(system, user.toString());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
