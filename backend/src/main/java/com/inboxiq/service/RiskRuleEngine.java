package com.inboxiq.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic, explainable phishing/scam heuristics, run independently of
 * the AI so a risk signal never depends solely on one model call. Combined
 * with the AI's own {@code riskScore}/{@code riskReasons} in
 * {@code EmailAnalysisService} — see that class for the blend.
 *
 * Every signal here is a *possibility*, never a verdict — reasons are phrased
 * accordingly, matching the spec's "never state as certainty" requirement.
 */
@Component
public class RiskRuleEngine {

    private static final Pattern CREDENTIAL_REQUEST = Pattern.compile(
            "\\b(verify your (account|password|identity)|confirm your (password|ssn|social security)|" +
            "reset your password immediately|update your (billing|payment) (details|information)|" +
            "suspended? (your )?account|unusual (sign[- ]?in|activity) detected|" +
            "click here to (verify|confirm|update|unlock))\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PAYMENT_REQUEST = Pattern.compile(
            "\\b(wire transfer|gift cards?|western union|bitcoin|crypto(currency)?|" +
            "urgent payment|processing fee|claim your (prize|refund|reward))\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern URGENCY = Pattern.compile(
            "\\b(act now|immediate(ly)? action|account will be (closed|suspended|terminated)|" +
            "within 24 hours|failure to (respond|comply)|final warning)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern RAW_IP_URL = Pattern.compile("https?://\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern SUSPICIOUS_TLD_OR_SHORTENER = Pattern.compile(
            "https?://[\\w.-]+\\.(zip|xyz|top|click|tk|gq)\\b|" +
            "https?://(bit\\.ly|tinyurl\\.com|t\\.co|goo\\.gl)/", Pattern.CASE_INSENSITIVE);

    private static final Pattern GENERIC_GREETING = Pattern.compile(
            "^\\s*(dear (customer|user|valued (customer|member)|sir/madam))", Pattern.CASE_INSENSITIVE);

    public record Result(int score, List<String> reasons) {}

    public Result evaluate(String sender, String subject, String bodyText) {
        List<String> reasons = new ArrayList<>();
        int score = 0;

        String combinedText = ((subject == null ? "" : subject) + "\n" + (bodyText == null ? "" : bodyText));

        if (CREDENTIAL_REQUEST.matcher(combinedText).find()) {
            score += 30;
            reasons.add("Asks the recipient to verify or confirm account/credential details");
        }
        if (PAYMENT_REQUEST.matcher(combinedText).find()) {
            score += 30;
            reasons.add("Mentions payment methods often used in scams (wire transfer, gift cards, crypto)");
        }
        if (URGENCY.matcher(combinedText).find()) {
            score += 15;
            reasons.add("Uses urgent, pressure-inducing language");
        }
        if (RAW_IP_URL.matcher(combinedText).find()) {
            score += 20;
            reasons.add("Contains a link pointing to a raw IP address rather than a domain name");
        }
        if (SUSPICIOUS_TLD_OR_SHORTENER.matcher(combinedText).find()) {
            score += 15;
            reasons.add("Contains a link using a link-shortener or an uncommon top-level domain");
        }
        if (bodyText != null && GENERIC_GREETING.matcher(bodyText.strip()).find()) {
            score += 10;
            reasons.add("Opens with a generic greeting rather than the recipient's name");
        }
        if (sender != null && looksLikeDisplayNameSpoof(sender)) {
            score += 15;
            reasons.add("Sender display name suggests a well-known brand, but the address itself looks unrelated");
        }

        return new Result(clamp(score, 0, 100), reasons);
    }

    /**
     * Flags the common spoofing pattern of "PayPal Support <random123@mailhost.com>" —
     * a trusted-sounding display name paired with an address on an unrelated domain.
     */
    private boolean looksLikeDisplayNameSpoof(String senderHeader) {
        int lt = senderHeader.indexOf('<');
        if (lt <= 0) return false; // no display name, or address only — nothing to compare
        String displayName = senderHeader.substring(0, lt).toLowerCase().replaceAll("[^a-z]", "");
        String address = senderHeader.substring(lt + 1).toLowerCase();

        String[] trustedBrands = {"paypal", "amazon", "microsoft", "apple", "google", "bank", "netflix", "irs"};
        for (String brand : trustedBrands) {
            if (displayName.contains(brand) && !address.contains(brand)) {
                return true;
            }
        }
        return false;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
