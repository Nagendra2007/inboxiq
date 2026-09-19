package com.inboxiq.repository;

import com.inboxiq.entity.EmailMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailRepository extends JpaRepository<EmailMessage, UUID>, JpaSpecificationExecutor<EmailMessage> {

    Optional<EmailMessage> findByMailAccountIdAndProviderMessageId(UUID mailAccountId, String providerMessageId);

    boolean existsByMailAccountIdAndProviderMessageId(UUID mailAccountId, String providerMessageId);

    List<EmailMessage> findByMailAccountIdAndProviderMessageIdIn(UUID mailAccountId, Collection<String> providerMessageIds);

    /** The user an email belongs to — used to route realtime events without loading lazy associations. */
    @Query("select a.user.id from EmailMessage e join e.mailAccount a where e.id = :emailId")
    Optional<UUID> findOwnerUserId(@Param("emailId") UUID emailId);

    /**
     * One page of the inbox, in one query: just the listed columns plus the
     * analysis, never the message bodies. See {@link EmailSummaryRow} for why
     * the list deliberately doesn't go through entities.
     */
    @Query(value = """
           select new com.inboxiq.repository.EmailSummaryRow(
                  e.id, e.sender, e.subject, e.snippet, e.receivedAt, e.read, e.hasAttachments, a)
           from EmailMessage e
           left join e.analysis a
           where e.mailAccount.id = :mailAccountId
           order by e.receivedAt desc
           """,
           countQuery = "select count(e) from EmailMessage e where e.mailAccount.id = :mailAccountId")
    Page<EmailSummaryRow> findSummaries(@Param("mailAccountId") UUID mailAccountId, Pageable pageable);

    /**
     * Search results still come back as entities (the filters run against the
     * analysis join), but the analysis is fetched along with them rather than
     * one extra query per row.
     */
    @Override
    @EntityGraph(attributePaths = "analysis")
    Page<EmailMessage> findAll(Specification<EmailMessage> spec, Pageable pageable);

    /** The three email totals on the dashboard, in a single round trip. */
    @Query("""
           select new com.inboxiq.repository.EmailCounts(
                  count(e),
                  count(case when e.read = false then 1 end),
                  count(case when e.receivedAt > :since then 1 end))
           from EmailMessage e
           where e.mailAccount.id = :mailAccountId
           """)
    EmailCounts countsFor(@Param("mailAccountId") UUID mailAccountId, @Param("since") Instant since);

    long countByMailAccountId(UUID mailAccountId);
}
