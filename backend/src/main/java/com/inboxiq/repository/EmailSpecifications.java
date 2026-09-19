package com.inboxiq.repository;

import com.inboxiq.entity.EmailAnalysis;
import com.inboxiq.entity.EmailCategory;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.Priority;
import com.inboxiq.entity.RiskLevel;
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

    public static Specification<EmailMessage> category(EmailCategory category) {
        if (category == null) return null;
        return (root, query, cb) -> {
            Join<EmailMessage, EmailAnalysis> analysis = root.join("analysis", JoinType.LEFT);
            return cb.equal(analysis.get("category"), category);
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
