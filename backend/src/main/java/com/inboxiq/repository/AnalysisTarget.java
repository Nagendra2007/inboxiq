package com.inboxiq.repository;

import java.util.UUID;

/** An email waiting for background analysis, and the user to notify about it. */
public record AnalysisTarget(UUID emailId, UUID userId) {}
