package com.inboxiq.repository;

import com.inboxiq.entity.EmailMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailRepository extends JpaRepository<EmailMessage, UUID>, JpaSpecificationExecutor<EmailMessage> {

    Optional<EmailMessage> findByMailAccountIdAndProviderMessageId(UUID mailAccountId, String providerMessageId);

    Page<EmailMessage> findByMailAccountIdOrderByReceivedAtDesc(UUID mailAccountId, Pageable pageable);

    long countByMailAccountId(UUID mailAccountId);

    long countByMailAccountIdAndReadFalse(UUID mailAccountId);

    long countByMailAccountIdAndReceivedAtAfter(UUID mailAccountId, Instant since);
}
