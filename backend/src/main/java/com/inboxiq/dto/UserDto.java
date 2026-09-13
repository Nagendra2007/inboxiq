package com.inboxiq.dto;

import java.util.UUID;

/** @param admin whether this user may change app-wide settings (the AI provider) */
public record UserDto(UUID id, String email, String name, boolean gmailConnected, boolean admin) {}
