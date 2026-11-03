package com.mailroom.service;

import com.mailroom.ai.dto.EmailAnalysisAiResult.ActionItemAiResult;
import com.mailroom.entity.EmailCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is allowed onto a to-do list.
 *
 * <p>The list was filling with things nobody would ever tick off — "enter
 * the verification code", "read more" — and a list like that stops being
 * read. One wrong item costs more than ten missing ones, so these are the
 * cases where restraint is not left to the model's judgement.
 */
class ActionItemFilterTest {

    private static ActionItemAiResult task(String description) {
        return new ActionItemAiResult(description, null);
    }

    private static ActionItemAiResult task(String description, String deadline) {
        return new ActionItemAiResult(description, deadline);
    }

    private static List<String> kept(List<ActionItemAiResult> items, String otp, EmailCategory category) {
        return EmailAnalysisService.worthTracking(items, otp, category).stream()
                .map(ActionItemAiResult::description)
                .toList();
    }

    @Test
    void anEmailCarryingACodeHasNothingToTrack() {
        List<ActionItemAiResult> items = List.of(
                task("Enter the verification code 418293 to sign in"),
                task("Contact support if this was not you"));

        // Typing a code is finished in the ten seconds it takes to read the
        // email, and the code has expired by the time anyone looks at a
        // list. Neither item survives the email being a code email.
        assertThat(kept(items, "418293", EmailCategory.SECURITY)).isEmpty();
    }

    @Test
    void arealTaskSurvives() {
        List<ActionItemAiResult> items = List.of(task("Pay the hostel fee of 12,000", "2026-11-20"));

        assertThat(kept(items, null, EmailCategory.FINANCE))
                .containsExactly("Pay the hostel fee of 12,000");
    }

    @Test
    void waysOfSayingThereIsNothingToDoAreNotThingsToDo() {
        List<ActionItemAiResult> items = List.of(
                task("No action required"), task("None"), task("N/A"), task("Nothing to do."));

        assertThat(kept(items, null, EmailCategory.WORK)).isEmpty();
    }

    @Test
    void readingTheEmailIsNotATask() {
        List<ActionItemAiResult> items = List.of(
                task("Read more"), task("Learn more"), task("Click here"), task("Open the attachment"));

        assertThat(kept(items, null, EmailCategory.WORK)).isEmpty();
    }

    @Test
    void aRealTaskThatBeginsWithOneOfThoseWordsIsKept() {
        List<ActionItemAiResult> items = List.of(
                task("Read the tenancy agreement before signing it"),
                task("Open the attachment and check the invoice total"));

        // Matched whole, never as a substring: the difference between a
        // button and an instruction is the rest of the sentence.
        assertThat(kept(items, null, EmailCategory.WORK)).hasSize(2);
    }

    @Test
    void advertisingNeedsADeadlineToCount() {
        List<ActionItemAiResult> items = List.of(
                task("Browse the new arrivals"),
                task("Claim the discount", "2026-11-10"));

        // Marketing asks you to do something in every line. A stated date
        // is what makes one of them a commitment rather than a pitch.
        assertThat(kept(items, null, EmailCategory.MARKETING)).containsExactly("Claim the discount");
    }

    @Test
    void anOrdinaryEmailWithoutADeadlineIsStillKept() {
        List<ActionItemAiResult> items = List.of(task("Send the signed form to the registrar"));

        // The deadline rule applies to advertising only. Most real tasks
        // arrive without a date attached.
        assertThat(kept(items, null, EmailCategory.EDUCATION)).hasSize(1);
    }

    @Test
    void emptyAndMissingInputAreBothFine() {
        assertThat(EmailAnalysisService.worthTracking(null, null, EmailCategory.WORK)).isEmpty();
        assertThat(EmailAnalysisService.worthTracking(List.of(), null, EmailCategory.WORK)).isEmpty();
        assertThat(kept(List.of(task("   ")), null, EmailCategory.WORK)).isEmpty();
    }
}
