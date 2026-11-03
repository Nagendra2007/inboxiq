package com.mailroom.ai.dto;

import java.util.List;

/**
 * The answer, and the emails it claims to rest on.
 *
 * <p>The ids are a claim, not a fact — they are checked against the
 * candidates the model was given before anything reaches the user.
 */
public record ChatAnswerAiResult(String answer, List<String> emailIds) {}
