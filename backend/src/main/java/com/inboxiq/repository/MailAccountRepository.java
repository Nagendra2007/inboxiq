package com.inboxiq.repository;

import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MailAccountRepository extends JpaRepository<MailAccount, UUID> {
    Optional<MailAccount> findByUserIdAndProvider(UUID userId, MailProvider provider);
    Optional<MailAccount> findByUserIdAndProviderAndActiveTrue(UUID userId, MailProvider provider);

    /**
     * Mailboxes the background poller should check while their owner is away:
     * connected, not waiting on a reconnect, and not checked since
     * {@code checkedBefore}. {@code activeSince} leaves out accounts nobody has
     * used in a long time (an abandoned deployment shouldn't keep spending
     * Gmail quota and AI credits forever) — signing in again revives them.
     * Least recently synced first, so a capped batch still gets round to
     * everyone.
     */
    @Query("""
           select a.id from MailAccount a
           where a.active = true
             and a.provider = :provider
             and a.reauthRequired = false
             and (a.lastSyncAt is null or a.lastSyncAt < :checkedBefore)
             and (a.lastSyncAt is null or a.lastSyncAt > :activeSince)
           order by a.lastSyncAt asc nulls first
           """)
    List<UUID> findIdsDueForBackgroundSync(@Param("provider") MailProvider provider,
                                           @Param("checkedBefore") Instant checkedBefore,
                                           @Param("activeSince") Instant activeSince,
                                           Pageable limit);
}
