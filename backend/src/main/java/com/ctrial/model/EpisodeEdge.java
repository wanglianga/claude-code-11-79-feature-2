package com.ctrial.model;

import com.ctrial.model.SafetyEnums.EdgeKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A candidate association between two source observations, with the score and
 * the explicit reasons that produced it. Every edge — merged or merely
 * candidate — is retained so the reconstruction is reviewable: a reviewer can
 * see exactly why two reports were considered the same episode, or why a
 * borderline pair was left as an unresolved candidate rather than merged.
 */
@Entity
@Table(name = "episode_edge", indexes = {
        @Index(name = "idx_edge_run", columnList = "run_id")
})
@Getter
@Setter
@NoArgsConstructor
public class EpisodeEdge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "run_id")
    private SafetyEpisodeRun run;

    /** Natural keys of the two observations ("group#revision"). */
    @Column(name = "left_key", nullable = false)
    private String leftKey;

    @Column(name = "right_key", nullable = false)
    private String rightKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EdgeKind kind;

    @Column(nullable = false)
    private double score;

    /** True when this edge was used to merge the two observations into one episode. */
    @Column(nullable = false)
    private boolean applied;

    /** Human-readable reasons, comma-separated (e.g. "same_participant,term_exact,time_overlap"). */
    @Column(nullable = false, length = 800)
    private String reasons;

    /** When this edge is ambiguous, the episode code it might belong to once resolved. */
    @Column(name = "left_episode_code")
    private String leftEpisodeCode;

    @Column(name = "right_episode_code")
    private String rightEpisodeCode;
}
