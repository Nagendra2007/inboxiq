package com.mailroom.dto;

import jakarta.validation.constraints.NotBlank;

/** One Android installation's FCM registration token. */
public record DeviceRegisterRequest(@NotBlank String token, String platform) {}
