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
 * The stored result of running an {@link EmailMessage} through the analysis
 * pipeline (rule-based signals + a single structured LLM call). One row per
 * email, upserted on re-analysis rather than duplicated.
 *
 * {@code priority}/{@code priorityScore} come from the hybrid
 * {@code PriorityEngine} (rules + the AI's classification as one input),
 * not directly from the LLM — see that class for the scoring breakdown.
 */
@Entity
@Table(name = "email_analysis")
@Getter
@Setter
@NoArgsConstructor
public class EmailAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "email_id", nullable = false, unique = true)
    private EmailMessage email;

    @Column(name = "summary")
    private String summary;

    /** JSON array of short bullet strings, e.g. ["Exams begin Sept 21", ...]. */
    @Column(name = "key_points")
    private String keyPointsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "category")
    private EmailCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority")
    private Priority priority;

    @Column(name = "priority_score")
    private Integer priorityScore;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level")
    private RiskLevel riskLevel;

    /** JSON array of short human-readable reasons behind the risk score. */
    @Column(name = "risk_reasons")
    private String riskReasonsJson;

    @Column(name = "requires_reply")
    private Boolean requiresReply;

    @Column(name = "action_required")
    private Boolean actionRequired;

    /** JSON array of ISO-8601 date strings mentioned in the email. */
    @Column(name = "important_dates")
    private String importantDatesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false)
    private AnalysisStatus analysisStatus = AnalysisStatus.PENDING;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "ai_model_used")
    private String aiModelUsed;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
