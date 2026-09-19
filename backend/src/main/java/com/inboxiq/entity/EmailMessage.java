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
 * A synced Gmail message. {@code providerMessageId} is Gmail's own message id
 * and, together with {@code mailAccount}, is unique — re-syncing never
 * creates duplicate rows (see {@code EmailRepository#findByMailAccountIdAndProviderMessageId}).
 *
 * Body storage is intentionally prunable: {@link #bodyPurged} lets a
 * retention job (or an explicit user request) blank out {@link #bodyText}/
 * {@link #bodyHtml} for old messages while keeping the lightweight metadata
 * (subject, snippet, sender, analysis) that powers the inbox list and
 * dashboard. See README "Privacy" section.
 */
@Entity
@Table(
    name = "emails",
    uniqueConstraints = @UniqueConstraint(columnNames = {"mail_account_id", "provider_message_id"})
)
@Getter
@Setter
@NoArgsConstructor
public class EmailMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_account_id", nullable = false)
    private MailAccount mailAccount;

    @Column(name = "provider_message_id", nullable = false)
    private String providerMessageId;

    @Column(name = "thread_id")
    private String threadId;

    @Column(name = "sender")
    private String sender;

    /** Comma-joined "To" addresses (a message can have more than one recipient). */
    @Column(name = "recipient")
    private String recipient;

    @Column(name = "cc_recipient")
    private String ccRecipient;

    @Column(name = "subject")
    private String subject;

    @Column(name = "snippet", length = 500)
    private String snippet;

    @Column(name = "body_text")
    private String bodyText;

    @Column(name = "body_html")
    private String bodyHtml;

    /** Small JSON array of {filename, mimeType, sizeBytes} — metadata only, never file contents. */
    @Column(name = "attachments_metadata")
    private String attachmentsMetadataJson;

    @Column(name = "has_attachments", nullable = false)
    private boolean hasAttachments = false;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    /**
     * Out of the inbox but still here, with everything derived from it.
     * Mirrors Gmail's INBOX label: archiving in either place archives in
     * both, and neither loses the message.
     */
    @Column(name = "archived", nullable = false)
    private boolean archived = false;

    /** True once the retention job (or a user request) has cleared the body fields above. */
    @Column(name = "body_purged", nullable = false)
    private boolean bodyPurged = false;

    /**
     * Read-only inverse side, purely so repository search queries can join
     * and filter on analysis fields (category/priority/risk) in one query.
     * {@link EmailAnalysis} rows are still created/updated exclusively
     * through {@code EmailAnalysisRepository}, never cascaded from here.
     */
    @OneToOne(mappedBy = "email", fetch = FetchType.LAZY)
    private EmailAnalysis analysis;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
