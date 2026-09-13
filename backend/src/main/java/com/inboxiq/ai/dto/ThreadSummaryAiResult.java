package com.inboxiq.ai.dto;

import java.util.List;

public record ThreadSummaryAiResult(String summary, List<String> keyPoints, List<String> openQuestions) {}
