package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The only safety artifact the global plane ever receives: an aggregate count
 * and uncertainty interval computed strictly inside one approved data-use
 * envelope (sites, observation window, protocol versions, field group). It
 * carries <b>no</b> participant key, local pseudonym or per-subject row — cells
 * below the k-anonymity floor are suppressed and only their total is reported.
 *
 * <p>Receiving an aggregate never changes a parent-task delivery or eligibility
 * row; the digest is over the aggregate content only.
 */
@Entity
@Table(name = "safety_global_aggregate", uniqueConstraints = {
        @UniqueConstraint(name = "uq_global_aggregate_code", columnNames = {"code"})
})
@Getter
@Setter
@NoArgsConstructor
public class SafetyGlobalAggregate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "stats_version_id")
    private SafetyStatsVersion statsVersion;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "approval_id")
    private DataUseApproval approval;

    @Column(name = "sites", nullable = false, length = 300)
    private String sites;

    @Column(name = "observation_from", nullable = false)
    private Instant observationFrom;

    @Column(name = "observation_to", nullable = false)
    private Instant observationTo;

    @Column(name = "k_floor", nullable = false)
    private int kFloor;

    @Column(name = "total_index_episodes", nullable = false)
    private int totalIndexEpisodes;

    @Column(name = "total_recurrences", nullable = false)
    private int totalRecurrences;

    @Column(name = "total_exposure_days", nullable = false)
    private long totalExposureDays;

    @Column(name = "published_cells", nullable = false)
    private int publishedCells;

    @Column(name = "suppressed_cells", nullable = false)
    private int suppressedCells;

    @Column(name = "suppressed_episodes", nullable = false)
    private int suppressedEpisodes;

    /** k-anonymised cells: {term, soc, index, recurrence, subjects, exposureDays, rates, intervals}. */
    @Column(name = "cells_json", columnDefinition = "text", nullable = false)
    private String cellsJson;

    @Column(name = "received_digest", nullable = false, length = 64)
    private String receivedDigest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(length = 1000)
    private String note;
}
