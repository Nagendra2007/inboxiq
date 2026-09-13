package com.inboxiq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Sent only when the user clicks Send after reviewing/editing a draft in the
 * composer. {@code finalBodyText} is exactly what will be transmitted —
 * whatever the user last edited it to, not necessarily the originally
 * generated text — which is the whole point of a review-before-send flow.
 */
public record SendReplyRequest(
        @NotNull(message = "draftId is required.")
        UUID draftId,

        @NotBlank(message = "The reply body cannot be empty.")
        String finalBodyText,

        @NotBlank(message = "A recipient address is required.")
        String toAddress,

        @NotBlank(message = "A subject is required.")
        String subject
) {}
