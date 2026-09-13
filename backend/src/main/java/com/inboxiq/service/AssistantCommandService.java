package com.inboxiq.service;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Classifies the user's one-line composer instruction into an intent, using
 * cheap local heuristics rather than a second LLM call — the spec is
 * explicit that a quick-adjustment button click ("Make shorter") must not
 * cost a full model round trip to route, only to execute. The frontend's
 * dedicated buttons already send an unambiguous command (see
 * {@link Intent#MAKE_SHORTER} etc.); this classifier exists for the free-text
 * box, so a user typing "make this shorter" gets the same cheap, consistent
 * handling as clicking the button.
 */
@Service
public class AssistantCommandService {

    private static final Pattern SHORTER = Pattern.compile("\\b(shorter|shorten|more concise|briefer|trim)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMAL = Pattern.compile("\\b(formal|professional|more polished)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRIENDLY = Pattern.compile("\\b(friendly|casual|warmer|less formal)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGENERATE = Pattern.compile("\\b(regenerate|try again|another (option|version|draft)|redo)\\b", Pattern.CASE_INSENSITIVE);

    public enum Intent { MAKE_SHORTER, MAKE_FORMAL, MAKE_FRIENDLY, REGENERATE, CUSTOM_ADJUST }

    public record Classification(Intent intent, String toneLabel, String adjustmentInstruction) {}

    /** Classifies a free-text follow-up instruction against an existing draft. */
    public Classification classifyAdjustment(String userText) {
        if (userText == null || userText.isBlank()) {
            return new Classification(Intent.REGENERATE, "regenerated", "Produce a fresh alternative draft.");
        }
        if (SHORTER.matcher(userText).find()) {
            return new Classification(Intent.MAKE_SHORTER, "shorter", "Make this reply noticeably shorter and more concise, keeping the key points.");
        }
        if (FORMAL.matcher(userText).find()) {
            return new Classification(Intent.MAKE_FORMAL, "formal", "Rewrite this reply in a more formal, professional tone.");
        }
        if (FRIENDLY.matcher(userText).find()) {
            return new Classification(Intent.MAKE_FRIENDLY, "friendly", "Rewrite this reply in a warmer, more friendly and casual tone.");
        }
        if (REGENERATE.matcher(userText).find()) {
            return new Classification(Intent.REGENERATE, "regenerated", "Produce a fresh alternative draft with the same intent.");
        }
        return new Classification(Intent.CUSTOM_ADJUST, "custom", userText);
    }

    /** Fixed, zero-cost mappings for the composer's dedicated quick-adjustment buttons. */
    public Classification forButton(Intent buttonIntent) {
        return switch (buttonIntent) {
            case MAKE_SHORTER -> new Classification(Intent.MAKE_SHORTER, "shorter",
                    "Make this reply noticeably shorter and more concise, keeping the key points.");
            case MAKE_FORMAL -> new Classification(Intent.MAKE_FORMAL, "formal",
                    "Rewrite this reply in a more formal, professional tone.");
            case MAKE_FRIENDLY -> new Classification(Intent.MAKE_FRIENDLY, "friendly",
                    "Rewrite this reply in a warmer, more friendly and casual tone.");
            case REGENERATE -> new Classification(Intent.REGENERATE, "regenerated",
                    "Produce a fresh alternative draft with the same intent.");
            case CUSTOM_ADJUST -> throw new IllegalArgumentException("CUSTOM_ADJUST requires free-text instruction");
        };
    }
}
