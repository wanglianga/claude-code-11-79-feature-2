package com.ctrial.model;

import com.ctrial.model.SafetyEnums.EpisodeStrategy;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One reconstruction pass of adverse-event episodes under a chosen strategy,
 * coding edition and merge threshold. A stats version is <b>immutable</b>: when
 * the terminology mapping changes, a new run + stats version is produced; prior
 * versions are never rewritten, and nothing here can touch a parent-task
 * delivery or eligibility decision.
 *
 * <p>{@code aggregateDigest} is computed over the approved-scope aggregate only,
 * so two runs are comparable without exposing any participant key.
 */
@Entity
@Table(name = "safety_episode_run", uniqueConstraints = {
        @UniqueConstraint(name = "uq_safety_run_code", columnNames = {"code"})
})
@Getter
@Setter
@NoArgsConstructor
public class SafetyEpisodeRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EpisodeStrategy strategy;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "terminology_version_id")
    private TerminologyVersion terminologyVersion;

    /** Probabilistic merge threshold used (0 for the deterministic run). */
    @Column(name = "merge_threshold")
    private Double mergeThreshold;

    /** Probabilistic lower band: below this a pair is unrelated; inside it is an ambiguous candidate. */
    @Column(name = "candidate_threshold")
    private Double candidateThreshold;

    /** Size of the reviewed sample the probabilistic thresholds were calibrated on. */
    @Column(name = "calibration_sample_size")
    private Integer calibrationSampleSize;

    /** Agreement achieved on the reviewed sample at those thresholds (0..1). */
    @Column(name = "calibration_agreement")
    private Double calibrationAgreement;

    /** Human-readable description of how the thresholds were calibrated. */
    @Column(name = "calibration_note", length = 1000)
    private String calibrationNote;

    @Column(name = "analysis_day", nullable = false)
    private int analysisDay;

    @Column(name = "analysis_at", nullable = false)
    private Instant analysisAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "observation_count", nullable = false)
    private int observationCount;

    @Column(name = "episode_count", nullable = false)
    private int episodeCount;

    /** Number of ambiguous candidate edges retained (never force-merged). */
    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "aggregate_digest", nullable = false, length = 64)
    private String aggregateDigest;

    @Column(name = "supersedes_run_id")
    private Long supersedesRunId;

    @Column(length = 1000)
    private String note;
}
