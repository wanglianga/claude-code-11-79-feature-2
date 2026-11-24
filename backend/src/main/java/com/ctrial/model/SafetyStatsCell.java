package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One preferred-term row of a {@link SafetyStatsVersion}. Counts, a denominator
 * and a rate per 100 person-days plus a rate interval whose width reflects both
 * sampling noise and linkage ambiguity (how many borderline pairs could shift
 * the episode count up or down). Contains no participant identifiers, so it is
 * safe to show on the global side once the k-anonymity floor is met.
 */
@Entity
@Table(name = "safety_stats_cell", indexes = {
        @Index(name = "idx_stats_cell_version", columnList = "version_id")
})
@Getter
@Setter
@NoArgsConstructor
public class SafetyStatsCell {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "version_id")
    private SafetyStatsVersion version;

    @Column(name = "preferred_term", nullable = false)
    private String preferredTerm;

    @Column(nullable = false)
    private String soc;

    @Column(name = "index_count", nullable = false)
    private int indexCount;

    @Column(name = "recurrence_count", nullable = false)
    private int recurrenceCount;

    /** Distinct participants contributing at least one episode for this term. */
    @Column(name = "subject_count", nullable = false)
    private int subjectCount;

    /** Observable treatment days for the participants contributing to this cell. */
    @Column(name = "exposure_days", nullable = false)
    private long exposureDays;

    @Column(name = "index_rate_per_100_pd", nullable = false)
    private double indexRatePer100Pd;

    @Column(name = "recurrence_rate_per_100_pd", nullable = false)
    private double recurrenceRatePer100Pd;

    /** Rate interval endpoints (per 100 person-days), widened by linkage ambiguity. */
    @Column(name = "index_rate_lower", nullable = false)
    private double indexRateLower;

    @Column(name = "index_rate_upper", nullable = false)
    private double indexRateUpper;

    @Column(name = "recurrence_rate_lower", nullable = false)
    private double recurrenceRateLower;

    @Column(name = "recurrence_rate_upper", nullable = false)
    private double recurrenceRateUpper;

    /** How many episodes could be removed if every ambiguous edge merged (lower count). */
    @Column(name = "ambiguity_down", nullable = false)
    private int ambiguityDown;

    /** How many episodes could be added if every ambiguous edge stayed split (upper count). */
    @Column(name = "ambiguity_up", nullable = false)
    private int ambiguityUp;

    /** Comma-separated approved sites that contributed (for local review; not projected globally). */
    @Column(name = "site_codes", nullable = false, length = 300)
    private String siteCodes;
}
