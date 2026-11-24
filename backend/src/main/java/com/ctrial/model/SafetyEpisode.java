package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A reconstructed adverse-event episode: the same clinical process seen across
 * one or more source observations. Episodes belong to exactly one run, so a new
 * run never mutates a previous reconstruction.
 *
 * <p>{@code participantKey} is local-only. The episode's contribution to the
 * global aggregate is expressed solely through counts attached to the run; the
 * key is never projected globally.
 */
@Entity
@Table(name = "safety_episode", indexes = {
        @Index(name = "idx_episode_run", columnList = "run_id"),
        @Index(name = "idx_episode_participant", columnList = "participant_key")
})
@Getter
@Setter
@NoArgsConstructor
public class SafetyEpisode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "run_id")
    private SafetyEpisodeRun run;

    @Column(name = "episode_code", nullable = false)
    private String episodeCode;

    @Column(name = "participant_key", nullable = false)
    private String participantKey;

    @Column(name = "local_pseudonym", nullable = false)
    private String localPseudonym;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(name = "residency_domain", nullable = false)
    private String residencyDomain;

    @Column(name = "preferred_term", nullable = false)
    private String preferredTerm;

    @Column(name = "soc", nullable = false)
    private String soc;

    /** Highest severity grade across the member observations. */
    @Column(name = "max_grade", nullable = false)
    private int maxGrade;

    @Column(nullable = false)
    private boolean serious;

    /** Reconstructed episode start/end (union of the member observation ranges). */
    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    /** True when any member observation explicitly records resolution. */
    @Column(name = "resolution_evidence", nullable = false)
    private boolean resolutionEvidence;

    /** True when any member observation records a treatment interruption. */
    @Column(name = "treatment_interruption", nullable = false)
    private boolean treatmentInterruption;

    /** Sorted, comma-separated natural keys of the member observations (local-only audit detail). */
    @Column(name = "member_observation_keys", nullable = false, length = 1000)
    private String memberObservationKeys;

    /** Source types that contributed (distinct, sorted). */
    @Column(name = "source_types", nullable = false, length = 200)
    private String sourceTypes;

    /** Ordinal within the participant+term series (1 = index event, 2+ = recurrence). */
    @Column(name = "occurrence_index", nullable = false)
    private int occurrenceIndex;

    /** True when this episode is a recurrence of an earlier episode after documented resolution. */
    @Column(name = "recurrence", nullable = false)
    private boolean recurrence;

    /** Contribution to the aggregate confidence interval; carried from the run's calibration. */
    @Column(name = "ambiguity_weight", nullable = false)
    private double ambiguityWeight = 1.0;
}
