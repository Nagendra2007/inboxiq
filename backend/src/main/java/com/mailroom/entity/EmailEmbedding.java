package com.mailroom.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * What one email means, as a vector.
 *
 * <p>Stored as bytes rather than a database vector type: the extension
 * that provides one has to exist before the column can be created, which
 * makes it a migration that fails at startup wherever it is missing. One
 * mailbox's worth fits in memory, so the comparison happens in Java.
 */
@Entity
@Table(name = "email_embeddings")
@Getter
@Setter
@NoArgsConstructor
public class EmailEmbedding {

    /** The email's own id: one vector per email, no surrogate key. */
    @Id
    @Column(name = "email_id")
    private UUID emailId;

    /**
     * Which model produced it. Vectors from different models are not
     * comparable, and comparing them produces plausible nonsense rather
     * than an error — so the model is checked, never assumed.
     */
    @Column(name = "model", nullable = false, length = 100)
    private String model;

    @Column(name = "dimensions", nullable = false)
    private int dimensions;

    @Column(name = "vector", nullable = false)
    private byte[] vector;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
