package com.inboxiq.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Backs the composer's [Regenerate]/[Make shorter]/[Make formal]/
 * [Make friendly]/free-text-edit controls. Exactly one of {@code button} or
 * {@code freeText} should be supplied; {@code button} takes precedence when
 * both are present, since a dedicated button click is unambiguous.
 */
public record AdjustReplyRequest(
        @NotNull(message = "previousDraftId is required.")
        UUID previousDraftId,

        /** One of MAKE_SHORTER, MAKE_FORMAL, MAKE_FRIENDLY, REGENERATE — see AssistantCommandService.Intent. */
        String button,

        String freeText
) {}
