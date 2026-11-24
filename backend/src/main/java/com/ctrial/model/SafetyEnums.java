package com.ctrial.model;

/**
 * Vocabulary for cross-source adverse-event episode reconstruction.
 *
 * <p>These enums are deliberately separate from {@link Enums} because the safety
 * pipeline is a second, independent consumer of the same local source facts: it
 * must never widen or rewrite the parent task's cross-border eligibility
 * decisions or historical deliveries.
 */
public final class SafetyEnums {

    private SafetyEnums() {
    }

    /** Where a source observation was recorded. The same clinical process may appear in several. */
    public enum SourceType {
        CENTER_SAFETY_REPORT,
        HOSPITAL_SUMMARY,
        LAB_ABNORMALITY,
        FOLLOW_UP_NOTE
    }

    /** The two reconstruction strategies required by the spec. */
    public enum EpisodeStrategy {
        DETERMINISTIC,
        PROBABILISTIC
    }

    /** How two observations were related (and whether the edge was applied). */
    public enum EdgeKind {
        /** Same participant, same canonical preferred term, explicit time overlap. */
        DETERMINISTIC_OVERLAP,
        /** Probabilistic score at or above the merge threshold; applied. */
        PROBABILISTIC_MERGE,
        /** Probabilistic score inside the uncertainty band; retained, never force-merged. */
        CANDIDATE_AMBIGUOUS
    }

    /** Outcome of mapping a raw term through a coding dictionary. */
    public enum MappingStatus {
        MAPPED,
        RECODED,
        UNMAPPED,
        OUT_OF_VALIDITY
    }

    /** Lifecycle of a terminology dictionary version. Never mutates a historical stats version. */
    public enum TerminologyStatus {
        ACTIVE,
        RETIRED
    }
}
