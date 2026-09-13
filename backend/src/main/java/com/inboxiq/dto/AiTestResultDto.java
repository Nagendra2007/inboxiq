package com.inboxiq.dto;

/** Outcome of "Test connection": whether the provider answered, and a human-readable explanation. */
public record AiTestResultDto(boolean ok, String message, long latencyMs) {}
