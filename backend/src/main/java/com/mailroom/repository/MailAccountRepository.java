package com.mailroom.repository;

import com.mailroom.entity.MailAccount;
import com.mailroom.entity.MailProvider;
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

    /** Which mailbox a Gmail push notification belongs to. */
    Optional<MailAccount> findByProviderEmailAndProviderAndActiveTrue(String providerEmail, MailProvider provider);

    /**
     * Mailboxes whose push registration has lapsed or is about to. A null
     * expiry is included on purpose: it means no watch was ever registered,
     * which needs the same call as a renewal.
     */
    @Query("""
           select a from MailAccount a
           where a.provider = :provider and a.active = true and a.reauthRequired = false
             and (a.gmailWatchExpiration is null or a.gmailWatchExpiration < :cutoff)
           """)
    List<MailAccount> findWatchesDueForRenewal(@Param("provider") MailProvider provider,
                                               @Param("cutoff") Instant cutoff);

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
