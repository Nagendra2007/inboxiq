package com.mailroom.ai.prompt;

/**
 * Turns one spoken sentence into the three fields the composer already takes:
 * who it goes to, what it is about, and what it should say.
 *
 * <p>This deliberately does not write the email. The composer's existing
 * prompt does that, and does it better with a clean instruction than it would
 * with a transcript full of "um" and a half-corrected name. The job here is
 * only to split a sentence into fields.
 *
 * <p>The recipient comes back as whatever the speaker called the person — a
 * first name, usually — and is matched against the mailbox's own contacts
 * afterwards. The model is never shown the contact list: a transcript is
 * unreliable enough without inviting a model to invent an address that looks
 * plausible next to it.
 */
public final class ComposeCommandPrompt {

    private static final int MAX_COMMAND_CHARS = 1_000;

    private ComposeCommandPrompt() {}

    public static PromptPair build(String spokenText) {
        String system = PromptSafety.INJECTION_GUARD + "\n" + """
                You split a single spoken instruction into the fields of an email \
                composer. Return ONLY a JSON object matching exactly this schema — \
                no markdown fences, no commentary, no extra keys:

                {
                  "recipient": string or null — who the email is for, exactly as the speaker referred to them. A first name ("Priya"), a full name, or an email address if they spelled one out. Null if they did not say.
                  "subject": string or null — a short subject line, at most 8 words, that you infer from what they said. Null only if there is nothing to infer one from.
                  "instruction": string — what the email should actually say, rewritten as a clear instruction to a drafting assistant. Keep every concrete detail: dates, amounts, names, deadlines. Drop filler, false starts and self-corrections.
                  "confident": boolean — false if the text is too garbled or too vague to split reliably.
                }

                This text came from speech recognition, so expect missing \
                punctuation, run-on sentences, and corrections mid-sentence \
                ("tell him Tuesday, no, Wednesday" means Wednesday). Names are \
                the least reliable part of a transcript: copy the recipient \
                exactly as you hear it rather than guessing at a correction.

                Never invent an email address. If the speaker said a name, \
                return the name. Never add detail the speaker did not give — an \
                instruction that says less is better than one that says \
                something untrue.
                """;

        String user = "SPOKEN INSTRUCTION:\n" + PromptSafety.delimit("SPOKEN INSTRUCTION", truncate(spokenText));
        return new PromptPair(system, user);
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() <= MAX_COMMAND_CHARS ? text : text.substring(0, MAX_COMMAND_CHARS);
    }
}
