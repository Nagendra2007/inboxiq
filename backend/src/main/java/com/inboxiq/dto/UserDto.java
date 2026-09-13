package com.inboxiq.dto;

import java.util.UUID;

public record UserDto(UUID id, String email, String name, boolean gmailConnected) {}
