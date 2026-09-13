package com.inboxiq.repository;

import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MailAccountRepository extends JpaRepository<MailAccount, UUID> {
    Optional<MailAccount> findByUserIdAndProvider(UUID userId, MailProvider provider);
    Optional<MailAccount> findByUserIdAndProviderAndActiveTrue(UUID userId, MailProvider provider);
}
