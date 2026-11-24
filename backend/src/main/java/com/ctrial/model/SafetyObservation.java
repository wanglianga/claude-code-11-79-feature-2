package com.ctrial.model;

import com.ctrial.model.SafetyEnums.SourceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One source observation of a possible adverse event, as recorded by one source
 * system. The platform keeps the raw fact intact: who (participant local key),
 * which source, which verbatim term, the coding edition the source used, the
 * occurrence time range (start .. end, end may be open), severity and the
 * report revision.
 *
 * <p>This table is a <b>local</b> artifact. {@code participantKey} is a
 * residence-domain local key and must never be aggregated globally as an
 * identifier — the global plane only ever sees per-stratum counts that have
 * already been checked against the k-anonymity floor.
 *
 * <p>Revisions never overwrite: a corrected report is a new row with a higher
 * {@code revision} for the same {@code observationGroupKey}, superseding the
 * earlier one, which stays visible for audit.
 */
@Entity
@Table(name = "safety_observation", uniqueConstraints = {
        @UniqueConstraint(name = "uq_observation_revision",
                columnNames = {"observation_group_key", "revision"})
}, indexes = {
        @Index(name = "idx_obs_participant", columnList = "participant_key"),
        @Index(name = "idx_obs_occurred", columnList = "occurred_start")
})
@Getter
@Setter
@NoArgsConstructor
public class SafetyObservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable identity of the clinical observation across revisions (e.g. "AE-P11-NAUSEA"). */
    @Column(name = "observation_group_key", nullable = false)
    private String observationGroupKey;

    @Column(nullable = false)
    private int revision = 1;

    /** Residence-domain local participant key (e.g. "L-CN-01-011"). Never a global identifier. */
    @Column(name = "participant_key", nullable = false)
    private String participantKey;

    /** Local pseudonym used elsewhere in the platform; kept for joining to the subject registry. */
    @Column(name = "local_pseudonym", nullable = false)
    private String localPseudonym;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(name = "residency_domain", nullable = false)
    private String residencyDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    /** Source system that produced this observation (e.g. "CN-SAFETY", "CN-HOSP-SUM", "CN-LAB"). */
    @Column(name = "source_system", nullable = false)
    private String sourceSystem;

    /** Source-side report identifier (free text, retained verbatim). */
    @Column(name = "source_report_ref", nullable = false)
    private String sourceReportRef;

    @Column(name = "verbatim_term", nullable = false)
    private String verbatimTerm;

    /** Coding system + edition the source applied when filing this observation. */
    @Column(name = "coding_system", nullable = false)
    private String codingSystem;

    @Column(name = "coding_version", nullable = false)
    private String codingVersion;

    @Column(name = "occurred_start", nullable = false)
    private Instant occurredStart;

    /** End of the occurrence window; null = still ongoing at the time of the report. */
    @Column(name = "occurred_end")
    private Instant occurredEnd;

    /** 1..5 CTCAE-style grade. */
    @Column(name = "severity_grade", nullable = false)
    private int severityGrade;

    @Column(nullable = false)
    private boolean serious;

    /** True when the report states the event resolved (a resolution/relief statement). */
    @Column(name = "resolved_flag", nullable = false)
    private boolean resolved;

    /** True when a treatment interruption is recorded alongside this observation. */
    @Column(name = "treatment_interrupted", nullable = false)
    private boolean treatmentInterrupted;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(nullable = false)
    private boolean voided;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    public String naturalKey() {
        return observationGroupKey + "#" + revision;
    }

    public boolean overlaps(SafetyObservation other) {
        Instant aStart = occurredStart;
        Instant aEnd = occurredEnd == null ? Instant.MAX : occurredEnd;
        Instant bStart = other.occurredStart;
        Instant bEnd = other.occurredEnd == null ? Instant.MAX : other.occurredEnd;
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }
}
