package com.inboxiq.repository;

import com.inboxiq.entity.EmailAnalysis;
import com.inboxiq.entity.Priority;
import com.inboxiq.entity.RiskLevel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailAnalysisRepository extends JpaRepository<EmailAnalysis, UUID> {

    Optional<EmailAnalysis> findByEmailId(UUID emailId);

    /** Analyses queued but not finished — e.g. interrupted by a restart. Newest emails first. */
    @Query("""
            select new com.inboxiq.repository.AnalysisTarget(e.id, a2.user.id)
            from EmailAnalysis a join a.email e join e.mailAccount a2
            where a.analysisStatus = com.inboxiq.entity.AnalysisStatus.PENDING
            order by e.receivedAt desc
            """)
    List<AnalysisTarget> findPendingTargets(Pageable pageable);

    /**
     * Failed analyses worth another automatic attempt: recently synced
     * emails that haven't used up their attempts, last tried a while ago.
     */
    @Query("""
            select new com.inboxiq.repository.AnalysisTarget(e.id, a2.user.id)
            from EmailAnalysis a join a.email e join e.mailAccount a2
            where a.analysisStatus = com.inboxiq.entity.AnalysisStatus.FAILED
              and a.aiAttempts < :maxAttempts
              and a.updatedAt < :lastTriedBefore
              and e.createdAt > :syncedAfter
            order by e.receivedAt desc
            """)
    List<AnalysisTarget> findRetryableFailedTargets(@Param("maxAttempts") int maxAttempts,
                                                     @Param("lastTriedBefore") Instant lastTriedBefore,
                                                     @Param("syncedAfter") Instant syncedAfter,
                                                     Pageable pageable);

    long countByEmail_MailAccountIdAndPriority(UUID mailAccountId, Priority priority);

    long countByEmail_MailAccountIdAndRiskLevel(UUID mailAccountId, RiskLevel riskLevel);

    long countByEmail_MailAccountIdAndRequiresReplyTrue(UUID mailAccountId);
}
