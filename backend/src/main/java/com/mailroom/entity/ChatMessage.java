package com.mailroom.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** One turn in a conversation: a question, or an answer and what it cited. */
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ChatConversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private ChatRole role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /**
     * The emails this answer was built from, as a JSON array of ids.
     *
     * <p>Stored with the answer rather than recomputed, because the point
     * of a citation is that it is the mail the model actually saw — a
     * second search later would be a different set and would quietly make
     * the answer look better sourced than it was.
     */
    @Column(name = "cited_email_ids", columnDefinition = "text")
    private String citedEmailIdsJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
