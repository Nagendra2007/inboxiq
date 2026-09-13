package com.inboxiq.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskRuleEngineTest {

    private final RiskRuleEngine engine = new RiskRuleEngine();

    @Test
    void flagsCredentialVerificationLanguage() {
        RiskRuleEngine.Result result = engine.evaluate(
                "Security Team <noreply@example.com>",
                "Unusual sign-in activity detected",
                "We noticed unusual activity. Please verify your account immediately by clicking the link below.");

        assertThat(result.score()).isGreaterThan(0);
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    void flagsCommonScamPaymentMethods() {
        RiskRuleEngine.Result result = engine.evaluate(
                "someone@example.com", "Urgent payment needed",
                "Please send payment via gift cards or wire transfer as soon as possible.");

        assertThat(result.score()).isGreaterThan(0);
    }

    @Test
    void flagsRawIpAddressLinks() {
        RiskRuleEngine.Result result = engine.evaluate(
                "someone@example.com", "Check this out",
                "Click here: http://192.168.1.50/login to continue.");

        assertThat(result.score()).isGreaterThan(0);
    }

    @Test
    void flagsDisplayNameBrandSpoofing() {
        RiskRuleEngine.Result spoofed = engine.evaluate(
                "PayPal Support <random392@totallyunrelated-mail.com>", "Account notice", "Please review your account.");
        RiskRuleEngine.Result legit = engine.evaluate(
                "PayPal Support <service@paypal.com>", "Account notice", "Please review your account.");

        assertThat(spoofed.score()).isGreaterThan(legit.score());
    }

    @Test
    void anOrdinaryEmailScoresLow() {
        RiskRuleEngine.Result result = engine.evaluate(
                "friend@example.com", "Lunch on Saturday?",
                "Hey, are you free for lunch this Saturday around noon? Let me know!");

        assertThat(result.score()).isLessThan(20);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void scoreIsAlwaysClampedToZeroToHundred() {
        RiskRuleEngine.Result result = engine.evaluate(
                "Bank Security <fraud123@random-domain.net>",
                "URGENT: verify your account or it will be suspended",
                "Dear customer, act now within 24 hours. Wire transfer or gift cards accepted. " +
                        "Click here to verify: http://203.0.113.5/login or use http://bit.ly/abc123");

        assertThat(result.score()).isBetween(0, 100);
    }
}
