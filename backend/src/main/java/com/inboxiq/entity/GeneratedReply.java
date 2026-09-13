package com.inboxiq.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * An audit trail of AI-generated reply/compose drafts, including whichever
 * one-line prompt the user typed. A row is written whether or not the draft
 * was ever sent — {@link #sent} + {@link #sentAt} record the outcome so
 * "sent" drafts never get silently deleted alongside a data retention pass
 * over drafts.
 *
 * {@link #email} is only set for a reply to an existing synced message; a
 * draft composed from scratch (no source email) has it null, which is why
 * {@link #mailAccount} exists as its own direct link — ownership of ANY
 * draft is verified through {@code mailAccount}, never by navigating
 * through email (which may not exist).
 */
@Entity
@Table(name = "generated_replies")
@Getter
@Setter
@NoArgsConstructor
public class GeneratedReply {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_account_id", nullable = false)
    private MailAccount mailAccount;

    /** Null for a from-scratch compose draft — see class javadoc. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "email_id", nullable = true)
    private EmailMessage email;

    /** Recipient address, only meaningfully used for compose drafts (a reply's recipient comes from the source email). */
    @Column(name = "to_address")
    private String toAddress;

    /** Subject line, only meaningfully used for compose drafts. */
    @Column(name = "draft_subject")
    private String draftSubject;

    @Column(name = "user_prompt", nullable = false, length = 1000)
    private String userPrompt;

    @Column(name = "generated_content", nullable = false)
    private String generatedContent;

    @Column(name = "tone_adjustment")
    private String toneAdjustment;

    @Column(name = "sent", nullable = false)
    private boolean sent = false;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "gmail_message_id")
    private String gmailMessageId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
