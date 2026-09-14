package com.inboxiq.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A single extracted to-do/deadline from an email, surfaced on the dashboard. */
@Entity
@Table(name = "action_items")
@Getter
@Setter
@NoArgsConstructor
public class ActionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "email_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) // matches the ON DELETE CASCADE in V1__init_schema.sql
    private EmailMessage email;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "deadline")
    private LocalDate deadline;

    @Column(name = "completed", nullable = false)
    private boolean completed = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
