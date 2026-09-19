package com.inboxiq.dto;

import java.util.List;
import java.util.UUID;

/**
 * What a bulk action actually managed to do. Emails are handled one by one,
 * so one that Gmail refuses doesn't stop the rest.
 *
 * @param appliedIds     changed in InboxIQ — deleted, archived, or marked
 * @param failed         untouched in both places: Gmail wouldn't take them, or
 *                       they weren't the caller's
 * @param changedInGmail how many of {@code appliedIds} Gmail changed just now;
 *                       the rest were ones Gmail no longer had, so the caller
 *                       mustn't claim their mailbox changed
 */
public record BulkActionResultDto(List<UUID> appliedIds, int failed, int changedInGmail) {}
