package com.inboxiq.service;

import com.inboxiq.entity.EmailCategory;
import com.inboxiq.entity.Priority;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityEngineTest {

    private final PriorityEngine engine = new PriorityEngine();

    @Test
    void resultPriorityAlwaysMatchesItsOwnScoreBand() {
        // HIGH: 80-100, MEDIUM: 40-79, LOW: 0-39 (fixed by the spec) — checked
        // as an invariant against the engine's own output across a spread of
        // inputs, rather than duplicating its internal weighting here.
        List<PriorityEngine.Result> results = List.of(
                engine.score(Priority.HIGH, EmailCategory.SECURITY, true, true, List.of(LocalDate.now().toString()), "URGENT", "asap"),
                engine.score(Priority.MEDIUM, EmailCategory.WORK, true, false, List.of(), null, null),
                engine.score(Priority.LOW, EmailCategory.MARKETING, false, false, List.of(), null, null),
                engine.score(Priority.LOW, EmailCategory.NEWSLETTER, false, false, List.of(), null, null)
        );

        for (PriorityEngine.Result result : results) {
            if (result.score() >= 80) {
                assertThat(result.priority()).isEqualTo(Priority.HIGH);
            } else if (result.score() >= 40) {
                assertThat(result.priority()).isEqualTo(Priority.MEDIUM);
            } else {
                assertThat(result.priority()).isEqualTo(Priority.LOW);
            }
        }
    }

    @Test
    void actionRequiredAndRequiresReplyIncreaseTheScore() {
        PriorityEngine.Result baseline = engine.score(Priority.MEDIUM, EmailCategory.OTHER, false, false, List.of(), null, null);
        PriorityEngine.Result withSignals = engine.score(Priority.MEDIUM, EmailCategory.OTHER, true, true, List.of(), null, null);

        assertThat(withSignals.score()).isGreaterThan(baseline.score());
    }

    @Test
    void anUpcomingDeadlineWithinThreeDaysBoostsPriorityMoreThanAFarOffDate() {
        String soon = LocalDate.now().plusDays(1).toString();
        String farOff = LocalDate.now().plusMonths(6).toString();

        PriorityEngine.Result soonResult = engine.score(Priority.MEDIUM, EmailCategory.OTHER, false, false, List.of(soon), null, null);
        PriorityEngine.Result farResult = engine.score(Priority.MEDIUM, EmailCategory.OTHER, false, false, List.of(farOff), null, null);

        assertThat(soonResult.score()).isGreaterThan(farResult.score());
    }

    @Test
    void marketingCategorySuppressesPriorityEvenWithAHighAiHint() {
        PriorityEngine.Result marketing = engine.score(Priority.HIGH, EmailCategory.MARKETING, false, false, List.of(), null, null);
        PriorityEngine.Result work = engine.score(Priority.HIGH, EmailCategory.WORK, false, false, List.of(), null, null);

        assertThat(marketing.score()).isLessThan(work.score());
    }

    @Test
    void urgentLanguageInTheSubjectIncreasesTheScore() {
        PriorityEngine.Result urgent = engine.score(Priority.MEDIUM, EmailCategory.OTHER, false, false, List.of(),
                "URGENT: action required immediately", null);
        PriorityEngine.Result calm = engine.score(Priority.MEDIUM, EmailCategory.OTHER, false, false, List.of(),
                "Weekly newsletter", null);

        assertThat(urgent.score()).isGreaterThan(calm.score());
    }

    @Test
    void scoreIsAlwaysClampedToZeroToHundred() {
        PriorityEngine.Result maxed = engine.score(Priority.HIGH, EmailCategory.SECURITY, true, true,
                List.of(LocalDate.now().toString()), "URGENT deadline today", "action required immediately");
        assertThat(maxed.score()).isLessThanOrEqualTo(100);

        PriorityEngine.Result minned = engine.score(Priority.LOW, EmailCategory.MARKETING, false, false, List.of(), null, null);
        assertThat(minned.score()).isGreaterThanOrEqualTo(0);
    }
}
