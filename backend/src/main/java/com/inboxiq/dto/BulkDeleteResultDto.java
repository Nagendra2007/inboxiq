package com.inboxiq.dto;

import java.util.List;
import java.util.UUID;

/**
 * What a "delete selected" actually managed to do. Emails are deleted one by
 * one, so one that Gmail refuses doesn't stop the rest: {@code deletedIds}
 * are gone from both InboxIQ and the Gmail inbox, {@code failed} counts the
 * ones still there, which the caller leaves on screen.
 */
public record BulkDeleteResultDto(List<UUID> deletedIds, int failed) {}
