package com.mailroom.dto;

import jakarta.validation.constraints.NotBlank;

/** One browser's push endpoint, exactly as PushManager.subscribe() returned it. */
public record PushSubscribeRequest(
        @NotBlank String endpoint,
        @NotBlank String p256dh,
        @NotBlank String auth) {
}
