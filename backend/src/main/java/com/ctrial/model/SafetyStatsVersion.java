package com.ctrial.model;

import com.ctrial.model.SafetyEnums.EpisodeStrategy;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An immutable statistical version computed from one {@link SafetyEpisodeRun}.
 *
 * <p>When the terminology mapping changes (a new dictionary edition or a
 * recoded term), the pipeline does <b>not</b> mutate this row: it produces a
 * new run and a new stats version that {@code supersedesVersionId} points back
 * at the old one. Nothing here is allowed to write back to the parent task's
 * eligibility decisions or historical deliveries — this is a separate, additive
 * statistics ledger.
 */
@Entity
@Table(name = "safety_stats_version", uniqueConstraints = {
        @UniqueConstraint(name = "uq_stats_version_code", columnNames = {"code"})
})
@Getter
@Setter
@NoArgsConstructor
public class SafetyStatsVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "run_id")
    private SafetyEpisodeRun run;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "terminology_version_id")
    private TerminologyVersion terminologyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EpisodeStrategy strategy;

    @Column(name = "analysis_day", nullable = false)
    private int analysisDay;

    @Column(name = "participant_count", nullable = false)
    private int participantCount;

    /** Denominator: observable treatment days actually covered by exposure windows. */
    @Column(name = "observable_treatment_days", nullable = false)
    private long observableTreatmentDays;

    @Column(name = "index_episode_count", nullable = false)
    private int indexEpisodeCount;

    @Column(name = "recurrence_count", nullable = false)
    private int recurrenceCount;

    @Column(name = "cell_count", nullable = false)
    private int cellCount;

    /** Number of ambiguous candidate edges that fed the uncertainty interval. */
    @Column(name = "ambiguous_candidate_count", nullable = false)
    private int ambiguousCandidateCount;

    @Column(name = "aggregate_digest", nullable = false, length = 64)
    private String aggregateDigest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "supersedes_version_id")
    private Long supersedesVersionId;

    @Column(length = 2000)
    private String note;
}
