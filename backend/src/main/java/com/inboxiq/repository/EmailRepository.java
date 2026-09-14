package com.inboxiq.repository;

import com.inboxiq.entity.EmailMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    Page<EmailMessage> findByMailAccountIdOrderByReceivedAtDesc(UUID mailAccountId, Pageable pageable);

    long countByMailAccountId(UUID mailAccountId);

    long countByMailAccountIdAndReadFalse(UUID mailAccountId);

    long countByMailAccountIdAndReceivedAtAfter(UUID mailAccountId, Instant since);
}
