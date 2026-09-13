package com.inboxiq.ai.prompt;

import java.util.List;

/** Builds the prompt for "Summarize this thread" — the whole conversation, not one message. */
public final class ThreadSummaryPrompt {

    private static final int MAX_MESSAGES = 15;
    private static final int MAX_CHARS_PER_MESSAGE = 3000;

    private ThreadSummaryPrompt() {}

    public static PromptPair build(List<String> messagesOldestFirst) {
        String system = PromptSafety.INJECTION_GUARD + "\n" + """
                You are InboxIQ's thread summarization engine. Summarize the entire \
                email conversation below as a whole, not message-by-message. Return \
                ONLY a JSON object matching exactly this schema:

                {
                  "summary": string (3-6 sentences covering how the conversation evolved and where it stands now),
                  "keyPoints": string[] (at most 6 short bullet phrases),
                  "openQuestions": string[] (anything still unresolved/unanswered; empty array if none)
                }

                No markdown fences, no extra keys, no commentary outside the JSON.
                """;

        List<String> trimmed = messagesOldestFirst.size() > MAX_MESSAGES
                ? messagesOldestFirst.subList(messagesOldestFirst.size() - MAX_MESSAGES, messagesOldestFirst.size())
                : messagesOldestFirst;

        StringBuilder user = new StringBuilder("EMAIL THREAD (oldest first):\n");
        for (int i = 0; i < trimmed.size(); i++) {
            String content = trimmed.get(i);
            String truncated = content.length() > MAX_CHARS_PER_MESSAGE
                    ? content.substring(0, MAX_CHARS_PER_MESSAGE) + "\n[...truncated...]"
                    : content;
            user.append(PromptSafety.delimit("MESSAGE " + (i + 1), truncated)).append("\n");
        }

        return new PromptPair(system, user.toString());
    }
}
