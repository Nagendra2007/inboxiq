package com.inboxiq.repository;

import com.inboxiq.entity.EmailAnalysis;
import com.inboxiq.entity.Priority;
import com.inboxiq.entity.RiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailAnalysisRepository extends JpaRepository<EmailAnalysis, UUID> {

    Optional<EmailAnalysis> findByEmailId(UUID emailId);

    long countByEmail_MailAccountIdAndPriority(UUID mailAccountId, Priority priority);

    long countByEmail_MailAccountIdAndRiskLevel(UUID mailAccountId, RiskLevel riskLevel);

    long countByEmail_MailAccountIdAndRequiresReplyTrue(UUID mailAccountId);
}
