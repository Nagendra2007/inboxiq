package com.mailroom.ai.dto;

/** One spoken instruction, split into the composer's fields. Nothing is resolved yet. */
public record ComposeCommandAiResult(String recipient, String subject, String instruction, boolean confident) {}
