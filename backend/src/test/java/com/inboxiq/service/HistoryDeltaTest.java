package com.inboxiq.service;

import com.inboxiq.gmail.GmailHistoryPage.Change;
import com.inboxiq.gmail.GmailHistoryPage.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryDeltaTest {

    private static Change added(String id, String... labels) {
        return new Change(Type.MESSAGE_ADDED, id, List.of(labels));
    }

    private static Change labelsAdded(String id, String... labels) {
        return new Change(Type.LABELS_ADDED, id, List.of(labels));
    }

    private static Change labelsRemoved(String id, String... labels) {
        return new Change(Type.LABELS_REMOVED, id, List.of(labels));
    }

    private static Change deleted(String id) {
        return new Change(Type.MESSAGE_DELETED, id, List.of());
    }

    @Test
    void archivingInGmailTakesTheMessageOutOfTheInboxListWithoutDeletingIt() {
        HistoryDelta delta = HistoryDelta.of(List.of(labelsRemoved("filed", "INBOX")));

        assertThat(delta.archiveStates()).containsExactly(Map.entry("filed", true));
        // Kept: the summary and to-dos survive tidying up in Gmail.
        assertThat(delta.messagesToRemove()).isEmpty();
    }

    @Test
    void puttingAMessageBackInTheGmailInboxUnarchivesItHere() {
        HistoryDelta delta = HistoryDelta.of(List.of(
                labelsRemoved("m", "INBOX"),
                labelsAdded("m", "INBOX")));

        assertThat(delta.archiveStates()).containsExactly(Map.entry("m", false));
    }

    @Test
    void aDeletedMessageNeedsNoArchiveChange() {
        HistoryDelta delta = HistoryDelta.of(List.of(
                labelsRemoved("gone", "INBOX"),
                deleted("gone")));

        assertThat(delta.archiveStates()).isEmpty();
        assertThat(delta.messagesToRemove()).containsExactly("gone");
    }

    @Test
    void onlyNewInboxMessagesAreStored() {
        HistoryDelta delta = HistoryDelta.of(List.of(
                added("a", "INBOX", "UNREAD"),
                added("sent", "SENT"),
                added("draft", "DRAFT", "INBOX"),
                added("b", "INBOX")));

        assertThat(delta.messagesToStore(100)).containsExactly("a", "b");
        assertThat(delta.messagesToRemove()).isEmpty();
    }

    @Test
    void readStateChangesAreMirroredWithoutRefetching() {
        HistoryDelta delta = HistoryDelta.of(List.of(
                labelsRemoved("old1", "UNREAD"),
                labelsAdded("old2", "UNREAD"),
                labelsRemoved("old2", "UNREAD")));

        assertThat(delta.messagesToStore(100)).isEmpty();
        assertThat(delta.readStates()).isEqualTo(Map.of("old1", true, "old2", true));
    }

    @Test
    void deletedTrashedOrSpamMessagesAreRemoved() {
        HistoryDelta delta = HistoryDelta.of(List.of(
                deleted("gone"),
                labelsAdded("trashed", "TRASH"),
                labelsAdded("spam", "SPAM")));

        assertThat(delta.messagesToRemove()).containsExactlyInAnyOrder("gone", "trashed", "spam");
    }

    @Test
    void aMessageThatArrivesAndIsTrashedInTheSameWindowIsNeitherStoredNorKept() {
        HistoryDelta delta = HistoryDelta.of(List.of(
                added("x", "INBOX", "UNREAD"),
                labelsAdded("x", "TRASH")));

        assertThat(delta.messagesToStore(100)).isEmpty();
        assertThat(delta.messagesToRemove()).containsExactly("x");
    }

    @Test
    void aMessageRestoredToTheInboxIsStoredAgain() {
        HistoryDelta delta = HistoryDelta.of(List.of(
                labelsAdded("x", "TRASH"),
                labelsAdded("x", "INBOX")));

        assertThat(delta.messagesToRemove()).isEmpty();
        assertThat(delta.messagesToStore(100)).containsExactly("x");
    }

    @Test
    void archivingKeepsTheLocalCopy() {
        HistoryDelta delta = HistoryDelta.of(List.of(labelsRemoved("x", "INBOX")));

        assertThat(delta.messagesToRemove()).isEmpty();
        assertThat(delta.messagesToStore(100)).isEmpty();
    }

    @Test
    void theCapKeepsTheNewestArrivals() {
        HistoryDelta delta = HistoryDelta.of(List.of(
                added("1", "INBOX"), added("2", "INBOX"), added("3", "INBOX"), added("4", "INBOX")));

        assertThat(delta.candidateCount()).isEqualTo(4);
        assertThat(delta.messagesToStore(2)).containsExactly("3", "4");
    }
}
