package com.mailroom.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One browser's push endpoint. A person has several — phone, laptop, work
 * machine — and each is subscribed separately, so a notification fans out
 * to all of them.
 */
@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The push service URL the browser handed us. Long, and not ours to shorten. */
    @Column(name = "endpoint", nullable = false, columnDefinition = "text")
    private String endpoint;

    @Column(name = "p256dh", nullable = false, length = 255)
    private String p256dh;

    @Column(name = "auth", nullable = false, length = 255)
    private String auth;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /** Last time a push to this endpoint was accepted, for pruning dead ones. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;
}
