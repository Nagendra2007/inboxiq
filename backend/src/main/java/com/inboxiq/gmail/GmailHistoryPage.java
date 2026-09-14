package com.inboxiq.gmail;

import java.util.List;

/**
 * One page of Gmail's mailbox history (changes after a history id), reduced
 * to what sync needs and kept in chronological order.
 *
 * @param historyId the mailbox's current history id as of this page — the
 *                  next checkpoint once every page has been applied
 */
public record GmailHistoryPage(List<Change> changes, String nextPageToken, String historyId) {

    public enum Type { MESSAGE_ADDED, MESSAGE_DELETED, LABELS_ADDED, LABELS_REMOVED }

    /**
     * @param labelIds for MESSAGE_ADDED the message's labels at that moment;
     *                 for LABELS_* the labels that were added or removed
     */
    public record Change(Type type, String messageId, List<String> labelIds) {
        public Change {
            labelIds = labelIds == null ? List.of() : List.copyOf(labelIds);
        }
    }
}
