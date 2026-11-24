package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An observable treatment interval for one participant, expressed in study
 * days. The exposure denominator is the count of <b>observable</b> treatment
 * days (interval length minus gaps), not calendar days from first dose and not
 * the nominal planned duration: a gap in which no data could be observed earns
 * no exposure credit.
 *
 * <p>A participant may have several intervals (dose holds, restarts). Days that
 * are not covered by any interval are not counted in the denominator.
 */
@Entity
@Table(name = "exposure_window", indexes = {
        @Index(name = "idx_exposure_participant", columnList = "participant_key")
})
@Getter
@Setter
@NoArgsConstructor
public class ExposureWindow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_key", nullable = false)
    private String participantKey;

    @Column(name = "local_pseudonym", nullable = false)
    private String localPseudonym;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(name = "residency_domain", nullable = false)
    private String residencyDomain;

    /** First treatment day (study-day number; day 1 = epoch). */
    @Column(name = "start_day", nullable = false)
    private int startDay;

    /** Last treatment day, inclusive. */
    @Column(name = "end_day", nullable = false)
    private int endDay;

    /**
     * Days inside [startDay, endDay] that are not observable (dose interruption
     * with no safety follow-up). Subtracted from the denominator.
     */
    @Column(name = "unobservable_days", nullable = false)
    private int unobservableDays;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Observable days contributed by this interval (clamped at zero). */
    public int observableDays() {
        int span = endDay - startDay + 1;
        return Math.max(0, span - Math.max(0, unobservableDays));
    }
}
