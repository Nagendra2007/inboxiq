package com.inboxiq.dto;

import java.util.List;
import java.util.UUID;

/**
 * What a "delete selected" actually managed to do. Emails are deleted one by
 * one, so one that Gmail refuses doesn't stop the rest.
 *
 * @param deletedIds   gone from InboxIQ
 * @param failed       still in both places — Gmail wouldn't take them, or they
 *                     weren't the caller's to delete
 * @param movedToTrash how many of {@code deletedIds} Gmail moved to Trash just
 *                     now; the rest were ones Gmail no longer had, so the
 *                     caller mustn't claim their mailbox changed
 */
public record BulkDeleteResultDto(List<UUID> deletedIds, int failed, int movedToTrash) {}
