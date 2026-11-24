package com.ctrial.model;

import com.ctrial.model.SafetyEnums.TerminologyStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One edition of a medical coding dictionary (e.g. MedDRA 25.1). The
 * {@code validFrom}/{@code validTo} pair is the dictionary's validity window:
 * a mapping may only be used for an observation whose occurrence falls inside
 * it. A stats version pins the dictionary edition it was computed under, so a
 * later edition produces a new stats version rather than rewriting the old one.
 */
@Entity
@Table(name = "terminology_version", uniqueConstraints = {
        @UniqueConstraint(name = "uq_terminology_edition", columnNames = {"coding_system", "version"})
})
@Getter
@Setter
@NoArgsConstructor
public class TerminologyVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coding_system", nullable = false)
    private String codingSystem;

    @Column(nullable = false)
    private String version;

    /** Dictionary validity window (half-open). */
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TerminologyStatus status = TerminologyStatus.ACTIVE;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(length = 1000)
    private String note;

    public TerminologyVersion(String codingSystem, String version, Instant validFrom, Instant validTo, String note) {
        this.codingSystem = codingSystem;
        this.version = version;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.note = note;
        this.publishedAt = Instant.now();
    }

    public boolean validAt(Instant instant) {
        return instant != null
                && !instant.isBefore(validFrom)
                && (validTo == null || instant.isBefore(validTo));
    }

    public String label() {
        return codingSystem + " " + version;
    }
}
