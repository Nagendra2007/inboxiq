package com.inboxiq.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The user's To/Subject/one-line instruction for a brand-new, from-scratch email draft. */
public record ComposeGenerateRequest(
        @NotBlank(message = "A recipient address is required.")
        @Email(message = "Please enter a valid email address.")
        String toAddress,

        @NotBlank(message = "A subject is required.")
        @Size(max = 500, message = "Subject must be at most 500 characters.")
        String subject,

        @NotBlank(message = "Please describe what you'd like the email to say.")
        @Size(max = 1000, message = "Instruction must be at most 1000 characters.")
        String instruction
) {}
