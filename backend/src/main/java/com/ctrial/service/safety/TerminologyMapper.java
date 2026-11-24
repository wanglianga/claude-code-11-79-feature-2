package com.ctrial.service.safety;

import com.ctrial.model.SafetyEnums.MappingStatus;
import com.ctrial.model.SafetyObservation;
import com.ctrial.model.TermMapping;
import com.ctrial.model.TerminologyVersion;
import com.ctrial.repo.TermMappingRepo;
import com.ctrial.repo.TerminologyVersionRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Maps verbatim source terms to a comparable preferred term using a specific
 * coding-dictionary edition, and exposes the PT -> SOC hierarchy.
 *
 * <p>Rules that matter for the reconstruction:
 * <ul>
 *   <li>A mapping is only usable when the observation's occurrence falls inside
 *       the mapping's validity window. An occurrence outside every window is
 *       reported as {@code OUT_OF_VALIDITY} and is <b>not</b> silently mapped to
 *       a newer term — that is the whole reason a new dictionary edition
 *       produces a new statistics version instead of mutating history.</li>
 *   <li>The mapper is deterministic and side-effect free; callers pass the
 *       edition they want, and the same observation + edition always yields the
 *       same answer. This is what makes a stats version reproducible.</li>
 * </ul>
 */
@Service
public class TerminologyMapper {

    private final TerminologyVersionRepo versionRepo;
    private final TermMappingRepo mappingRepo;

    public TerminologyMapper(TerminologyVersionRepo versionRepo, TermMappingRepo mappingRepo) {
        this.versionRepo = versionRepo;
        this.mappingRepo = mappingRepo;
    }

    /** One resolved term. {@code status} explains exactly how it was resolved. */
    public record ResolvedTerm(
            String verbatimTerm,
            MappingStatus status,
            String preferredTerm,
            String termCode,
            String hlt,
            String hlgt,
            String soc,
            String edition,
            String detail
    ) {
        public boolean usable() {
            return status == MappingStatus.MAPPED || status == MappingStatus.RECODED;
        }
    }

    /** Load every mapping in an edition, keyed by normalised source term. */
    public Map<String, TermMapping> mappingsFor(TerminologyVersion edition) {
        Map<String, TermMapping> idx = new HashMap<>();
        for (TermMapping m : mappingRepo.findByTerminologyVersionIdOrderBySourceTermAsc(edition.getId())) {
            idx.put(normalise(m.getSourceTerm()), m);
        }
        return idx;
    }

    /**
     * Resolve one observation against one edition.
     *
     * @param edition the dictionary edition the stats run pins
     * @param obs     the source observation
     * @param index   pre-loaded mappings for {@code edition} (so a run loads once)
     */
    public ResolvedTerm resolve(SafetyObservation obs, TerminologyVersion edition,
                                Map<String, TermMapping> index) {
        String term = normalise(obs.getVerbatimTerm());
        TermMapping m = index.get(term);
        String editionLabel = edition.label();

        if (m == null) {
            return new ResolvedTerm(obs.getVerbatimTerm(), MappingStatus.UNMAPPED, null, null,
                    null, null, null, editionLabel,
                    "Verbatim term '" + obs.getVerbatimTerm() + "' is not present in " + editionLabel
                            + "; it is excluded from comparable counts rather than guessed.");
        }
        if (!m.validAt(obs.getOccurredStart())) {
            return new ResolvedTerm(obs.getVerbatimTerm(), MappingStatus.OUT_OF_VALIDITY,
                    m.getPreferredTerm(), m.getTermCode(), m.getHlt(), m.getHlgt(), m.getSoc(), editionLabel,
                    "Mapping for '" + obs.getVerbatimTerm() + "' is not valid at occurrence "
                            + obs.getOccurredStart() + " under " + editionLabel
                            + " (valid from " + m.getValidFrom() + "); a new edition must be pinned instead.");
        }

        // A term the source coded under a different edition than the run pins is still
        // comparable only if the run's edition explicitly carries a valid mapping for it;
        // we record that as RECODED so a reviewer can see the term crossed editions.
        boolean crossEdition = obs.getCodingVersion() != null
                && !obs.getCodingVersion().equals(edition.getVersion());
        MappingStatus status = crossEdition ? MappingStatus.RECODED : MappingStatus.MAPPED;
        String detail = crossEdition
                ? "Source coded as " + obs.getCodingSystem() + " " + obs.getCodingVersion()
                        + "; recoded to " + editionLabel + " for comparability."
                : "Mapped directly under " + editionLabel + ".";
        return new ResolvedTerm(obs.getVerbatimTerm(), status, m.getPreferredTerm(), m.getTermCode(),
                m.getHlt(), m.getHlgt(), m.getSoc(), editionLabel, detail);
    }

    /** The active edition for a coding system (highest validFrom among ACTIVE editions). */
    public TerminologyVersion activeEdition(String codingSystem) {
        return versionRepo.findByCodingSystemOrderByVersionAsc(codingSystem).stream()
                .filter(v -> v.getStatus() == com.ctrial.model.SafetyEnums.TerminologyStatus.ACTIVE)
                .max(Comparator.comparing(TerminologyVersion::getValidFrom))
                .orElse(null);
    }

    /** Most recent edition strictly newer than the given one for the same coding system. */
    public TerminologyVersion nextEditionAfter(TerminologyVersion current) {
        if (current == null) {
            return activeEdition("MedDRA");
        }
        return versionRepo.findByCodingSystemOrderByVersionAsc(current.getCodingSystem()).stream()
                .filter(v -> v.getStatus() == com.ctrial.model.SafetyEnums.TerminologyStatus.ACTIVE)
                .filter(v -> v.getValidFrom().isAfter(current.getValidFrom()))
                .min(Comparator.comparing(TerminologyVersion::getValidFrom))
                .orElse(null);
    }

    public TerminologyVersion editionByLabel(String codingSystem, String version) {
        return versionRepo.findByCodingSystemAndVersion(codingSystem, version).orElse(null);
    }

    public static String normalise(String term) {
        return term == null ? "" : term.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Counts usable vs unusable mappings for a quick coverage read-out. */
    public Map<String, Integer> coverage(Collection<ResolvedTerm> resolved) {
        Map<String, Integer> out = new TreeMap<>();
        for (ResolvedTerm r : resolved) {
            out.merge(r.status().name(), 1, Integer::sum);
        }
        return out;
    }

    public static Instant dayHint() {
        return Instant.EPOCH;
    }
}
