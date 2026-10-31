package com.mailroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A transcript of one spoken composer instruction. */
public record VoiceCommandRequest(
        @NotBlank(message = "Nothing was heard.")
        @Size(max = 1000, message = "That was too long to be one instruction.")
        String text) {
}
