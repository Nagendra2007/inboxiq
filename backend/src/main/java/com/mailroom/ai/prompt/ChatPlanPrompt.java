package com.mailroom.ai.prompt;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Turns a question about a mailbox into something a database can answer.
 *
 * <p>This is the step that makes searching work without embeddings. A
 * person asks for "my fee receipts"; the mail says <em>tuition</em>,
 * <em>invoice</em>, <em>payment received</em>, <em>semester dues</em>. The
 * model knows those are the same thing, so it expands the question into
 * the words a sender would have used — which is most of what a vector
 * search would have bought, from a call that is already cheap.
 *
 * <p>It plans the search and nothing else. It never sees email content at
 * this stage, so there is no untrusted text in this prompt at all: the
 * only input is the user's own question.
 */
public final class ChatPlanPrompt {

    private ChatPlanPrompt() {}

    public static PromptPair build(String question, List<String> recentTurns) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        String system = """
                You turn a question about somebody's own email into a search over \
                their mailbox. Return ONLY a JSON object matching exactly this \
                schema — no markdown fences, no commentary, no extra keys:

                {
                  "keywords": string[] (the words that would actually appear in such an email, not the words of the question. Expand to what a SENDER would write: for "fee receipts" return things like "fee","tuition","receipt","invoice","payment","paid","semester". Include the obvious single words, at most 10, each at least 3 characters. Never include generic mail words like "email", "message", "mail", "inbox", "hi", "hello", "regards"),
                  "senders": string[] (parts of sender names or addresses, if the question names anyone — "from Priya", "from the bank". Empty when it does not),
                  "category": one of PERSONAL, WORK, EDUCATION, FINANCE, SHOPPING, DELIVERY, SECURITY, SOCIAL, MARKETING, NEWSLETTER, SUSPICIOUS, OTHER, or null. Only when the question clearly implies one — money questions are FINANCE, parcel questions are DELIVERY. Null when unsure: a wrong category hides the answer completely,
                  "after": "YYYY-MM-DD" or null — the earliest date to search, when the question implies one ("last month", "this year", "since June"),
                  "before": "YYYY-MM-DD" or null,
                  "unreadOnly": boolean — true only if the question is explicitly about unread mail,
                  "intent": "find" when the person wants the emails themselves, "summarise" when they want an overall answer drawn from several, "count" when they are asking how many
                }

                Today is %s. Resolve relative dates against it, and leave a date \
                null rather than guessing at one.

                Keywords matter more than anything else here. Too few and the \
                answer is missed entirely; wildly unrelated ones only add noise \
                that the next step filters out. Prefer being generous.
                """.formatted(today);

        StringBuilder user = new StringBuilder();
        if (recentTurns != null && !recentTurns.isEmpty()) {
            // Earlier turns so that "and the one before that?" can be
            // planned. The user's own words, never email content.
            user.append("EARLIER IN THIS CONVERSATION:\n");
            for (String turn : recentTurns) {
                user.append("- ").append(turn).append('\n');
            }
            user.append('\n');
        }
        user.append("QUESTION: ").append(question == null ? "" : question.trim());

        return new PromptPair(system, user.toString());
    }
}
