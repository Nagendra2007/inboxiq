package com.mailroom.repository;

import com.mailroom.entity.EmailAnalysis;
import com.mailroom.entity.EmailCategory;
import com.mailroom.entity.EmailMessage;
import com.mailroom.entity.Priority;
import com.mailroom.entity.RiskLevel;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Composable {@link Specification} predicates backing {@code GET /api/emails/search}.
 * Kept out of the service class so the query-building logic (all string/enum
 * matching, all null-safe) can be unit tested independently of any database.
 */
public final class EmailSpecifications {

    private EmailSpecifications() {}

    public static Specification<EmailMessage> forMailAccount(UUID mailAccountId) {
        return (root, query, cb) -> cb.equal(root.get("mailAccount").get("id"), mailAccountId);
    }

    public static Specification<EmailMessage> senderContains(String sender) {
        if (isBlank(sender)) return null;
        return (root, query, cb) -> cb.like(cb.lower(root.get("sender")), like(sender));
    }

    public static Specification<EmailMessage> subjectOrKeywordContains(String keyword) {
        if (isBlank(keyword)) return null;
        return (root, query, cb) -> {
            String pattern = like(keyword);
            return cb.or(
                    cb.like(cb.lower(root.get("subject")), pattern),
                    cb.like(cb.lower(root.get("snippet")), pattern),
                    cb.like(cb.lower(cb.coalesce(root.get("bodyText"), "")), pattern)
            );
        };
    }

    /**
     * Matches an email containing <em>any</em> of these words.
     *
     * <p>The single-keyword version above is what a search box needs. A
     * question does not arrive as one keyword: "my fee receipts" has to
     * find mail saying tuition, invoice, payment or paid, because the
     * sender chose those words and the reader did not. The planner expands
     * the question into the set; this turns the set into one query rather
     * than one query per word.
     */
    public static Specification<EmailMessage> anyKeyword(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return null;
        List<String> usable = keywords.stream()
                .filter(word -> !isBlank(word))
                // A one or two letter term matches most of a mailbox and
                // drowns out everything the planner actually meant.
                .filter(word -> word.trim().length() >= 3)
                .limit(12)
                .toList();
        if (usable.isEmpty()) return null;

        return (root, query, cb) -> {
            List<Predicate> matches = new ArrayList<>();
            for (String word : usable) {
                String pattern = like(word);
                matches.add(cb.like(cb.lower(root.get("subject")), pattern));
                matches.add(cb.like(cb.lower(root.get("snippet")), pattern));
                matches.add(cb.like(cb.lower(cb.coalesce(root.get("bodyText"), "")), pattern));
                matches.add(cb.like(cb.lower(root.get("sender")), pattern));
            }
            return cb.or(matches.toArray(new Predicate[0]));
        };
    }

    public static Specification<EmailMessage> category(EmailCategory category) {
        if (category == null) return null;
        return (root, query, cb) -> {
            Join<EmailMessage, EmailAnalysis> analysis = root.join("analysis", JoinType.LEFT);
            return cb.equal(analysis.get("category"), category);
        };
    }

    /**
     * This category, or no category yet.
     *
     * <p>For the search box, {@link #category} is right: the user picked
     * FINANCE and means it. For a question, the category is the model's
     * guess, and most of a freshly imported mailbox has no category at all
     * — history is stored unanalysed on purpose. Filtering on equality
     * then excludes almost everything that was imported, which is to say
     * almost everything a question about last term is actually about.
     *
     * <p>Not knowing what something is must not be the same as knowing it
     * is something else.
     */
    public static Specification<EmailMessage> categoryOrUnknown(EmailCategory category) {
        if (category == null) return null;
        return (root, query, cb) -> {
            Join<EmailMessage, EmailAnalysis> analysis = root.join("analysis", JoinType.LEFT);
            return cb.or(
                    cb.equal(analysis.get("category"), category),
                    cb.isNull(analysis.get("category")));
        };
    }

    /**
     * Matches any of these words in the subject alone.
     *
     * <p>A subject match is the strongest signal a mailbox gives: someone
     * asking about the academic calendar is looking for the email called
     * "Academic Calendar". Run separately from the body search because
     * candidates are capped and ordered by date, so a two-year-old subject
     * match would otherwise lose its place to this week's mail that merely
     * mentions the word somewhere in a footer.
     */
    public static Specification<EmailMessage> subjectContainsAny(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return null;
        List<String> usable = keywords.stream()
                .filter(word -> !isBlank(word))
                .filter(word -> word.trim().length() >= 3)
                .limit(12)
                .toList();
        if (usable.isEmpty()) return null;

        return (root, query, cb) -> {
            List<Predicate> matches = new ArrayList<>();
            for (String word : usable) {
                matches.add(cb.like(cb.lower(root.get("subject")), like(word)));
            }
            return cb.or(matches.toArray(new Predicate[0]));
        };
    }

    public static Specification<EmailMessage> priority(Priority priority) {
        if (priority == null) return null;
        return (root, query, cb) -> {
            Join<EmailMessage, EmailAnalysis> analysis = root.join("analysis", JoinType.LEFT);
            return cb.equal(analysis.get("priority"), priority);
        };
    }

    public static Specification<EmailMessage> riskLevel(RiskLevel riskLevel) {
        if (riskLevel == null) return null;
        return (root, query, cb) -> {
            Join<EmailMessage, EmailAnalysis> analysis = root.join("analysis", JoinType.LEFT);
            return cb.equal(analysis.get("riskLevel"), riskLevel);
        };
    }

    /**
     * Which list is being searched: the inbox, or what has been archived.
     * Never null in practice — leaving it out would mix the two together.
     */
    public static Specification<EmailMessage> archived(boolean archived) {
        return (root, query, cb) -> cb.equal(root.get("archived"), archived);
    }

    public static Specification<EmailMessage> unreadOnly(Boolean unreadOnly) {
        if (unreadOnly == null || !unreadOnly) return null;
        return (root, query, cb) -> cb.isFalse(root.get("read"));
    }

    public static Specification<EmailMessage> receivedBetween(Instant from, Instant to) {
        if (from == null && to == null) return null;
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("receivedAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("receivedAt"), to));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Combines every non-null specification with AND; nulls (unset filters) are simply skipped. */
    @SafeVarargs
    public static Specification<EmailMessage> and(Specification<EmailMessage>... specs) {
        Specification<EmailMessage> combined = Specification.where(null);
        for (Specification<EmailMessage> spec : specs) {
            if (spec != null) combined = combined.and(spec);
        }
        return combined;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String like(String s) {
        return "%" + s.toLowerCase() + "%";
    }
}
