package com.inboxiq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The user's one-line natural-language instruction for a brand-new reply draft. */
public record GenerateReplyRequest(
        @NotBlank(message = "Please describe what you'd like the reply to say.")
        @Size(max = 1000, message = "Instruction must be at most 1000 characters.")
        String instruction
) {}
