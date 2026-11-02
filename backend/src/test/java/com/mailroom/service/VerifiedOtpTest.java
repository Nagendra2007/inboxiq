package com.mailroom.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check that stands between a model's answer and someone's clipboard.
 *
 * <p>Finding the code is the model's job and it is good at it. The job here
 * is making invention impossible: a language model asked for a six-digit
 * code will sometimes produce six plausible digits rather than say there
 * were none, and a wrong code beside a Copy button gets pasted before
 * anybody reads it.
 */
class VerifiedOtpTest {

    @Test
    void keepsACodeThatIsReallyInTheBody() {
        assertThat(EmailAnalysisService.verifiedOtp("418293", "Sign in", "Your code is 418293, valid 10 minutes"))
                .isEqualTo("418293");
    }

    @Test
    void keepsACodeThatIsOnlyInTheSubject() {
        assertThat(EmailAnalysisService.verifiedOtp("552913", "552913 is your code", "Open the app to continue"))
                .isEqualTo("552913");
    }

    @Test
    void rejectsACodeThatAppearsNowhere() {
        // The failure this whole method exists for.
        assertThat(EmailAnalysisService.verifiedOtp("123456", "Sign in", "Your code is 418293")).isNull();
    }

    @Test
    void rejectsACodeTheModelTidiedUp() {
        // "4 1 8 2 9 3" in the mail, returned as "418293": close enough to
        // look right, not close enough to paste.
        assertThat(EmailAnalysisService.verifiedOtp("418293", null, "Your code is 4 1 8 2 9 3")).isNull();
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(EmailAnalysisService.verifiedOtp("  418293 ", null, "code 418293")).isEqualTo("418293");
    }

    @Test
    void rejectsSomethingTooLongToBeTypedIn() {
        String long20 = "ABCDEFGHIJ0123456789";
        assertThat(EmailAnalysisService.verifiedOtp(long20, null, "token " + long20)).isNull();
    }

    @Test
    void handlesNoCodeAndEmptyText() {
        assertThat(EmailAnalysisService.verifiedOtp(null, "s", "b")).isNull();
        assertThat(EmailAnalysisService.verifiedOtp("", "s", "b")).isNull();
        assertThat(EmailAnalysisService.verifiedOtp("   ", "s", "b")).isNull();
        assertThat(EmailAnalysisService.verifiedOtp("418293", null, null)).isNull();
    }
}
