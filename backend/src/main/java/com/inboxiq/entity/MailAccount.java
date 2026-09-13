package com.inboxiq.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A linked mailbox (today: always Gmail) for a {@link User}.
 *
 * IMPORTANT: {@code encryptedAccessToken} / {@code encryptedRefreshToken}
 * never hold a plaintext OAuth token. They are populated exclusively via
 * {@code TokenEncryptionService#encrypt} in {@code MailAccountService}, and
 * must be decrypted the same way before use. Never add a plain getter that
 * bypasses this, and never log these fields (see {@code MailAccount#toString}).
 */
@Entity
@Table(name = "mail_accounts")
@Getter
@Setter
@NoArgsConstructor
public class MailAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MailProvider provider = MailProvider.GOOGLE;

    @Column(name = "provider_email", nullable = false)
    private String providerEmail;

    @Column(name = "encrypted_access_token")
    private String encryptedAccessToken;

    @Column(name = "encrypted_refresh_token")
    private String encryptedRefreshToken;

    @Column(name = "token_expiry")
    private Instant tokenExpiry;

    /** Gmail's mailbox-wide history id, used as the incremental-sync checkpoint. */
    @Column(name = "last_history_id")
    private String lastHistoryId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Override
    public String toString() {
        // Deliberately excludes both token fields, even encrypted, from
        // logs/toString to keep them out of stray log.debug(account) calls.
        return "MailAccount{id=%s, provider=%s, providerEmail=%s, active=%s}"
                .formatted(id, provider, providerEmail, active);
    }
}
