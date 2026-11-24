package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Maps a verbatim source term to a comparable preferred term and its hierarchy
 * (PT -> HLT -> HLGT -> SOC) for one dictionary edition. The hierarchy is what
 * lets the probabilistic linker recognise "related but not identical" terms
 * without pretending they are the same canonical term.
 */
@Entity
@Table(name = "term_mapping", uniqueConstraints = {
        @UniqueConstraint(name = "uq_term_mapping", columnNames = {"terminology_version_id", "source_term"})
}, indexes = {
        @Index(name = "idx_term_mapping_pt", columnList = "preferred_term")
})
@Getter
@Setter
@NoArgsConstructor
public class TermMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "terminology_version_id")
    private TerminologyVersion terminologyVersion;

    /** Normalised verbatim term (lower-case, trimmed). */
    @Column(name = "source_term", nullable = false)
    private String sourceTerm;

    @Column(name = "term_code", nullable = false)
    private String termCode;

    @Column(name = "preferred_term", nullable = false)
    private String preferredTerm;

    /** Hierarchy: HLT, HLGT, SOC. Codes optional; names are what the linker compares. */
    @Column(name = "hlt_code")
    private String hltCode;

    @Column(name = "hlt")
    private String hlt;

    @Column(name = "hlgt")
    private String hlgt;

    @Column(name = "soc")
    private String soc;

    /** Mapping-level validity (usually equal to the edition window). */
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(nullable = false)
    private boolean active = true;

    public boolean validAt(Instant instant) {
        return active
                && !instant.isBefore(validFrom)
                && (validTo == null || instant.isBefore(validTo));
    }
}
