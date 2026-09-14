package com.inboxiq.service;

import com.inboxiq.gmail.GmailHistoryPage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folds a chronological list of Gmail history changes into the net effect a
 * sync pass has to apply locally:
 *
 *  - messages that arrived in (or were moved back to) the inbox — to store
 *    if not stored yet;
 *  - read/unread changes — to mirror on stored messages (no re-analysis:
 *    Gmail never changes a message's content, only its labels);
 *  - messages deleted, or moved to Trash or Spam — to remove locally.
 *
 * Archiving (only INBOX removed) deliberately keeps the local copy, so the
 * user doesn't lose its summary and to-dos by tidying up Gmail.
 */
final class HistoryDelta {

    private static final String INBOX = "INBOX";
    private static final String UNREAD = "UNREAD";
    private static final String DRAFT = "DRAFT";
    private static final Set<String> REMOVAL_LABELS = Set.of("TRASH", "SPAM");

    private final LinkedHashSet<String> toStore = new LinkedHashSet<>();
    private final Map<String, Boolean> readStates = new LinkedHashMap<>();
    private final LinkedHashSet<String> toRemove = new LinkedHashSet<>();

    private HistoryDelta() {}

    static HistoryDelta of(List<GmailHistoryPage.Change> changes) {
        HistoryDelta delta = new HistoryDelta();
        for (GmailHistoryPage.Change change : changes) {
            delta.apply(change);
        }
        return delta;
    }

    private void apply(GmailHistoryPage.Change change) {
        String id = change.messageId();
        List<String> labels = change.labelIds();
        switch (change.type()) {
            case MESSAGE_ADDED -> {
                if (labels.contains(INBOX) && !labels.contains(DRAFT)) markForStore(id);
            }
            case LABELS_ADDED -> {
                if (labels.stream().anyMatch(REMOVAL_LABELS::contains)) markForRemoval(id);
                else if (labels.contains(INBOX)) markForStore(id);
                if (labels.contains(UNREAD)) readStates.put(id, false);
            }
            case LABELS_REMOVED -> {
                if (labels.contains(UNREAD)) readStates.put(id, true);
            }
            case MESSAGE_DELETED -> {
                markForRemoval(id);
                readStates.remove(id);
            }
        }
    }

    private void markForStore(String id) {
        toRemove.remove(id);
        toStore.remove(id); // re-insert so the order reflects the latest arrival
        toStore.add(id);
    }

    private void markForRemoval(String id) {
        toStore.remove(id);
        toRemove.add(id);
    }

    /** Messages to store, oldest arrival first; at most {@code limit}, keeping the newest. */
    List<String> messagesToStore(int limit) {
        List<String> all = new ArrayList<>(toStore);
        return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
    }

    int candidateCount() {
        return toStore.size();
    }

    /** Gmail message id → read, for messages whose unread state changed. */
    Map<String, Boolean> readStates() {
        Map<String, Boolean> result = new LinkedHashMap<>(readStates);
        toRemove.forEach(result::remove);
        return Collections.unmodifiableMap(result);
    }

    Set<String> messagesToRemove() {
        return Collections.unmodifiableSet(toRemove);
    }
}
