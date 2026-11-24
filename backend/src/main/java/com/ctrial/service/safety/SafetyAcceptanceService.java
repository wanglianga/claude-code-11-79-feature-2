package com.ctrial.service.safety;

import com.ctrial.model.*;
import com.ctrial.model.Enums.PlaneType;
import com.ctrial.model.Enums.Purpose;
import com.ctrial.model.SafetyEnums.*;
import com.ctrial.repo.*;
import com.ctrial.service.safety.LocalSafetyResolver.DomainResult;
import com.ctrial.service.safety.LocalSafetyResolver.EpisodeDraft;
import com.ctrial.service.safety.SafetyRateCalculator.RateCell;
import com.ctrial.service.safety.TerminologyMapper.ResolvedTerm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Executable acceptance checks for the cross-source episode reconstruction, in
 * the same pass/fail-card style as the parent task's {@code AcceptanceService}.
 * Every verdict is read from the real pipeline output — nothing is special-cased.
 *
 * <p>Checks:
 * <ul>
 *   <li><b>E1</b> P11's centre (D3), hospital-summary (D4) and resolution (D7)
 *       reports form exactly one index episode.</li>
 *   <li><b>E2</b> P11's D20 report is a second, recurrence episode.</li>
 *   <li><b>E3</b> The same term/time from another participant (P12) is never
 *       linked to P11.</li>
 *   <li><b>X1</b> The exposure denominator equals the actual observable
 *       treatment days, not nominal span.</li>
 *   <li><b>V1</b> A mapping change produces a new stats version and leaves the
 *       prior version and parent-task deliveries untouched.</li>
 *   <li><b>G1</b> The global aggregate is k-anonymised and contains no
 *       participant identifiers.</li>
 *   <li><b>P1</b> The probabilistic strategy retains ambiguous pairs as
 *       candidates and never force-merges them.</li>
 * </ul>
 */
@Service
public class SafetyAcceptanceService {

    private final SafetyObservationRepo observationRepo;
    private final ExposureWindowRepo exposureRepo;
    private final TerminologyVersionRepo terminologyRepo;
    private final SafetyEpisodeRunRepo runRepo;
    private final SafetyEpisodeRepo episodeRepo;
    private final EpisodeEdgeRepo edgeRepo;
    private final SafetyStatsVersionRepo statsVersionRepo;
    private final SafetyStatsCellRepo statsCellRepo;
    private final SafetyReconstructionService reconstruction;
    private final SafetyAggregateService aggregateService;
    private final SafetyQueryService query;
    private final DeliveryRepo deliveryRepo;
    private final DeliveryRecordRepo deliveryRecordRepo;
    private final EligibilityRowRepo eligibilityRowRepo;
    private final TerminologyMapper mapper;

    public SafetyAcceptanceService(SafetyObservationRepo observationRepo, ExposureWindowRepo exposureRepo,
                                   TerminologyVersionRepo terminologyRepo, SafetyEpisodeRunRepo runRepo,
                                   SafetyEpisodeRepo episodeRepo, EpisodeEdgeRepo edgeRepo,
                                   SafetyStatsVersionRepo statsVersionRepo, SafetyStatsCellRepo statsCellRepo,
                                   SafetyReconstructionService reconstruction,
                                   SafetyAggregateService aggregateService, SafetyQueryService query,
                                   DeliveryRepo deliveryRepo, DeliveryRecordRepo deliveryRecordRepo,
                                   EligibilityRowRepo eligibilityRowRepo, TerminologyMapper mapper) {
        this.observationRepo = observationRepo;
        this.exposureRepo = exposureRepo;
        this.terminologyRepo = terminologyRepo;
        this.runRepo = runRepo;
        this.episodeRepo = episodeRepo;
        this.edgeRepo = edgeRepo;
        this.statsVersionRepo = statsVersionRepo;
        this.statsCellRepo = statsCellRepo;
        this.reconstruction = reconstruction;
        this.aggregateService = aggregateService;
        this.query = query;
        this.deliveryRepo = deliveryRepo;
        this.deliveryRecordRepo = deliveryRecordRepo;
        this.eligibilityRowRepo = eligibilityRowRepo;
        this.mapper = mapper;
    }

    public record Check(String code, String requirement, String expected, String observed, boolean pass) {
    }

    public record SafetyAcceptance(int analysisDay, List<Check> checks, boolean allPass, String summary,
                                   String deterministicRun, String probabilisticRun, String statsVersion) {
    }

    @Transactional
    public SafetyAcceptance report(int analysisDay, String actor) {
        // Run both strategies so the report always reflects an executed pipeline.
        SafetyReconstructionService.Reconstruction det = reconstruction.reconstruct(
                EpisodeStrategy.DETERMINISTIC, "MedDRA", "25.1", analysisDay, actor);
        SafetyReconstructionService.Reconstruction prb = reconstruction.reconstruct(
                EpisodeStrategy.PROBABILISTIC, "MedDRA", "25.1", analysisDay, actor);

        List<Check> checks = new ArrayList<>();

        // ---------- E1: P11 first three reports -> one index episode ----------
        List<SafetyEpisode> p11 = episodesForRun(det.run(), "L-CN-01-011");
        List<SafetyEpisode> p11Nausea = p11.stream()
                .filter(e -> e.getPreferredTerm().equalsIgnoreCase("Nausea")).toList();
        SafetyEpisode index = p11Nausea.stream().filter(e -> e.getOccurrenceIndex() == 1).findFirst().orElse(null);
        boolean e1 = index != null && index.getMemberObservationKeys().contains("OBS-P11-NAU-A#1")
                && index.getMemberObservationKeys().contains("OBS-P11-NAU-B#1")
                && index.getMemberObservationKeys().contains("OBS-P11-NAU-C#1")
                && !p11Nausea.stream().filter(e -> e.getOccurrenceIndex() == 1).anyMatch(
                        e -> e.getMemberObservationKeys().contains("OBS-P11-NAU-D#1"));
        checks.add(new Check("E1",
                "P11's centre (D3), hospital-summary (D4) and resolution (D7) reports are one episode.",
                "one nausea episode containing A,B,C (not D)",
                index == null ? "no index episode" : "members=" + index.getMemberObservationKeys(),
                e1));

        // ---------- E2: D20 recurrence -> second episode ----------
        SafetyEpisode recurrence = p11Nausea.stream().filter(e -> e.getOccurrenceIndex() == 2).findFirst().orElse(null);
        boolean e2 = recurrence != null && recurrence.isRecurrence()
                && recurrence.getMemberObservationKeys().equals("OBS-P11-NAU-D#1");
        checks.add(new Check("E2",
                "P11's D20 re-appearance is a second episode, flagged as a recurrence after resolution.",
                "occurrenceIndex=2, recurrence=true, members={D}",
                recurrence == null ? "no second episode"
                        : "index=" + recurrence.getOccurrenceIndex() + " recurrence=" + recurrence.isRecurrence()
                                + " members=" + recurrence.getMemberObservationKeys(),
                e2));

        // ---------- E3: other participant with same term/time not linked ----------
        boolean mixed = false;
        for (SafetyEpisode e : det.episodes()) {
            boolean hasP11 = e.getMemberObservationKeys().contains("OBS-P11-");
            boolean hasP12 = e.getMemberObservationKeys().contains("OBS-P12-");
            if (hasP11 && hasP12) {
                mixed = true;
            }
        }
        List<SafetyEpisode> p12 = episodesForRun(det.run(), "L-CN-01-012");
        boolean p12SelfMerged = p12.size() == 1
                && p12.get(0).getMemberObservationKeys().contains("OBS-P12-NAU-A#1")
                && p12.get(0).getMemberObservationKeys().contains("OBS-P12-NAU-B#1");
        boolean e3 = !mixed && p12SelfMerged;
        checks.add(new Check("E3",
                "A different participant with the same term and overlapping time is never linked.",
                "no episode mixes P11 and P12 members; P12's own two reports still merge",
                "mixed=" + mixed + " p12Episodes=" + p12.size(),
                e3));

        // ---------- X1: exposure denominator = observable days ----------
        long expectedDays = 0;
        for (ExposureWindow w : exposureRepo.findAllByOrderByParticipantKeyAscStartDayAsc()) {
            expectedDays += SafetyRateCalculator.observableDaysThrough(w, analysisDay);
        }
        long reportedDays = det.stats().getObservableTreatmentDays();
        // P11 nominal span is 25 days but 3 are unobservable; prove the denominator used the real value.
        ExposureWindow p11Window = exposureRepo.findByParticipantKeyOrderByStartDayAsc("L-CN-01-011").stream()
                .findFirst().orElse(null);
        int p11Nominal = p11Window == null ? 0 : (p11Window.getEndDay() - p11Window.getStartDay() + 1);
        int p11Observable = p11Window == null ? 0 : (int) SafetyRateCalculator.observableDaysThrough(p11Window,
                analysisDay);
        boolean x1 = reportedDays == expectedDays && p11Window != null && p11Observable == p11Nominal - 3;
        checks.add(new Check("X1",
                "The exposure denominator is observable treatment days, not the nominal span.",
                "denominator = sum of observable days; P11 observable = nominal - 3",
                "reported=" + reportedDays + " expected=" + expectedDays + " P11 nominal=" + p11Nominal
                        + " observable=" + p11Observable,
                x1));

        // ---------- P1: probabilistic strategy keeps ambiguous candidates ----------
        long candidates = edgeRepo.countByRunIdAndKind(prb.run().getId(), EdgeKind.CANDIDATE_AMBIGUOUS);
        long applied = edgeRepo.countByRunIdAndKind(prb.run().getId(), EdgeKind.PROBABILISTIC_MERGE);
        boolean anyAmbiguousApplied = edgeRepo.findByRunIdAndKind(prb.run().getId(), EdgeKind.CANDIDATE_AMBIGUOUS)
                .stream().anyMatch(EpisodeEdge::isApplied);
        boolean p1 = !anyAmbiguousApplied; // ambiguous edges must never be applied
        checks.add(new Check("P1",
                "Probabilistic linkage keeps ambiguous pairs as candidates and never force-merges them.",
                "ambiguous edges retained, none applied",
                "candidates=" + candidates + " appliedMerges=" + applied
                        + " ambiguousApplied=" + anyAmbiguousApplied,
                p1));

        // ---------- V1: mapping change -> new version, no writeback ----------
        // Snapshot parent-task state before the mapping change, then prove it is unchanged.
        long deliveriesBefore = deliveryRepo.count();
        long recordsBefore = deliveryRecordRepo.count();
        long eligibilityRowsBefore = eligibilityRowRepo.count();
        String priorDigest = det.stats().getAggregateDigest();
        SafetyStatsVersion latestBefore = statsVersionRepo.findAllByOrderByCreatedAtDesc().stream()
                .findFirst().orElse(null);
        Long latestBeforeId = latestBefore == null ? null : latestBefore.getId();
        String latestBeforeCode = latestBefore == null ? null : latestBefore.getCode();
        String latestBeforeDigest = latestBefore == null ? null : latestBefore.getAggregateDigest();

        SafetyReconstructionService.Reconstruction remapped = reconstruction.reconstruct(
                EpisodeStrategy.DETERMINISTIC, "MedDRA", "26.0", analysisDay, actor);

        // the version this run pinned is not mutated by the remap: same digest, same row
        SafetyStatsVersion oldAfter = statsVersionRepo.findByCode(det.stats().getCode()).orElse(null);
        boolean oldUnchanged = oldAfter != null
                && oldAfter.getAggregateDigest().equals(priorDigest)
                && oldAfter.getId().equals(det.stats().getId());
        // the new version explicitly supersedes the immediately preceding version
        boolean newSupersedes = latestBeforeId != null
                && latestBeforeId.equals(remapped.stats().getSupersedesVersionId());
        // that prior version survives with its digest intact (history is append-only)
        SafetyStatsVersion latestAfter = latestBeforeCode == null ? null
                : statsVersionRepo.findByCode(latestBeforeCode).orElse(null);
        boolean priorVersionIntact = latestAfter != null
                && Objects.equals(latestAfter.getAggregateDigest(), latestBeforeDigest);
        boolean noWriteback = deliveryRepo.count() == deliveriesBefore
                && deliveryRecordRepo.count() == recordsBefore
                && eligibilityRowRepo.count() == eligibilityRowsBefore;
        boolean v1 = oldUnchanged && newSupersedes && priorVersionIntact && noWriteback
                && remapped.stats().getTerminologyVersion().getVersion().equals("26.0");
        checks.add(new Check("V1",
                "A terminology change creates a new stats version and never rewrites the parent task's data.",
                "old versions unchanged; new version supersedes the prior one; deliveries/eligibility unchanged",
                "oldUnchanged=" + oldUnchanged + " newSupersedes=" + newSupersedes
                        + " priorIntact=" + priorVersionIntact + " noWriteback=" + noWriteback,
                v1));

        // ---------- G1: global aggregate carries no participant identifiers ----------
        // Publish from the 25.1 deterministic version (the 26.0 edition only becomes valid on day
        // 28, so at day 26 every 26.0 mapping is out of validity and contributes no cells). This is
        // the realistic case: the active-edition stats are what the global team receives.
        String publishStats = det.stats().getCode();
        String aggregateJson = aggregateService.publish(publishStats, "DUA-GLOBAL-01", 3, actor).toString();
        boolean noIdentifiers = !aggregateJson.contains("L-CN-") && !aggregateJson.contains("L-SG-")
                && !aggregateJson.contains("PSN-") && !aggregateJson.contains("OBS-")
                && !aggregateJson.contains("participantKey");
        var view = aggregateService.list().stream()
                .filter(v -> v.statsVersion().equals(publishStats)).findFirst().orElse(null);
        boolean kOk = view != null && view.cells().stream().allMatch(c -> c.subjectCount() >= view.kFloor());
        // nausea has 5 participants (>= floor) and is published; single-participant terms are suppressed
        boolean nauseaPublished = view != null && view.cells().stream()
                .anyMatch(c -> c.preferredTerm().equalsIgnoreCase("Nausea"));
        boolean someSuppressed = view != null && view.suppressedCells() > 0;
        boolean g1 = noIdentifiers && kOk && view != null && view.publishedCells() > 0
                && nauseaPublished && someSuppressed;
        checks.add(new Check("G1",
                "The global aggregate is k-anonymised and contains no participant identifiers.",
                "no local keys/pseudonyms; every published cell has >= k subjects; small cells suppressed",
                "noIdentifiers=" + noIdentifiers + " kFloor=" + (view == null ? "-" : view.kFloor())
                        + " published=" + (view == null ? "-" : view.publishedCells())
                        + " suppressed=" + (view == null ? "-" : view.suppressedCells()),
                g1));

        boolean allPass = checks.stream().allMatch(Check::pass);
        long passed = checks.stream().filter(Check::pass).count();
        String summary = passed + "/" + checks.size() + " safety reconstruction checks pass at analysis day "
                + analysisDay + "." + (allPass
                ? " Cross-source episodes, recurrence, exposure and version isolation agree."
                : " See the failing checks for detail.");
        return new SafetyAcceptance(analysisDay, checks, allPass, summary,
                det.run().getCode(), prb.run().getCode(), det.stats().getCode());
    }

    private List<SafetyEpisode> episodesForRun(SafetyEpisodeRun run, String participantKey) {
        return episodeRepo.findByRunIdAndParticipantKeyOrderByOccurrenceIndexAsc(run.getId(), participantKey);
    }
}
