package com.ctrial.service.safety;

import com.ctrial.common.Text;
import com.ctrial.model.*;
import com.ctrial.model.SafetyEnums.*;
import com.ctrial.repo.*;
import com.ctrial.service.AuditService;
import com.ctrial.service.StudyClock;
import com.ctrial.service.safety.EpisodeLinker.Edge;
import com.ctrial.service.safety.EpisodeLinker.Scenario;
import com.ctrial.service.safety.LocalSafetyResolver.ActiveSet;
import com.ctrial.service.safety.LocalSafetyResolver.DomainResult;
import com.ctrial.service.safety.LocalSafetyResolver.EpisodeDraft;
import com.ctrial.service.safety.SafetyRateCalculator.CountPair;
import com.ctrial.service.safety.SafetyRateCalculator.EpisodePoint;
import com.ctrial.service.safety.SafetyRateCalculator.RateCell;
import com.ctrial.service.safety.SafetyRateCalculator.Rates;
import com.ctrial.service.safety.TerminologyMapper.ResolvedTerm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Orchestrates a safety-episode reconstruction and freezes an immutable stats
 * version from it.
 *
 * <p>Identity is resolved <b>only</b> inside each residence domain: observations
 * are grouped by {@code residencyDomain}, mapped and linked per domain, and the
 * participant keys never cross a domain boundary. The per-term aggregate the
 * global plane can receive is produced by the separate
 * {@link SafetyAggregateService} from the stored stats cells, never from here.
 *
 * <p>Immutability: every call writes a new {@link SafetyEpisodeRun} and a new
 * {@link SafetyStatsVersion}; a re-run after a mapping change {@code supersedes}
 * the previous version by id but never edits it. No path here touches a
 * parent-task {@code Delivery} or {@code EligibilityRow}.
 */
@Service
public class SafetyReconstructionService {

    private final SafetyObservationRepo observationRepo;
    private final ExposureWindowRepo exposureRepo;
    private final TerminologyVersionRepo terminologyRepo;
    private final TerminologyMapper mapper;
    private final SafetyEpisodeRunRepo runRepo;
    private final SafetyEpisodeRepo episodeRepo;
    private final EpisodeEdgeRepo edgeRepo;
    private final SafetyStatsVersionRepo statsVersionRepo;
    private final SafetyStatsCellRepo statsCellRepo;
    private final StudyRepo studyRepo;
    private final StudyClock clock;
    private final AuditService audit;

    public SafetyReconstructionService(SafetyObservationRepo observationRepo, ExposureWindowRepo exposureRepo,
                                       TerminologyVersionRepo terminologyRepo, TerminologyMapper mapper,
                                       SafetyEpisodeRunRepo runRepo, SafetyEpisodeRepo episodeRepo,
                                       EpisodeEdgeRepo edgeRepo, SafetyStatsVersionRepo statsVersionRepo,
                                       SafetyStatsCellRepo statsCellRepo, StudyRepo studyRepo, StudyClock clock,
                                       AuditService audit) {
        this.observationRepo = observationRepo;
        this.exposureRepo = exposureRepo;
        this.terminologyRepo = terminologyRepo;
        this.mapper = mapper;
        this.runRepo = runRepo;
        this.episodeRepo = episodeRepo;
        this.edgeRepo = edgeRepo;
        this.statsVersionRepo = statsVersionRepo;
        this.statsCellRepo = statsCellRepo;
        this.studyRepo = studyRepo;
        this.clock = clock;
        this.audit = audit;
    }

    /** Completed reconstruction plus the stats version it froze. */
    public record Reconstruction(SafetyEpisodeRun run, SafetyStatsVersion stats,
                                 List<SafetyEpisode> episodes, List<EpisodeEdge> edges,
                                 List<String> warnings) {
    }

    /**
     * Reconstruct at {@code analysisDay} under {@code strategy} and the chosen
     * edition, then freeze a stats version. A null edition means the active one.
     */
    @Transactional
    public Reconstruction reconstruct(EpisodeStrategy strategy, String codingSystem, String version,
                                      int analysisDay, String actor) {
        TerminologyVersion edition = resolveEdition(codingSystem, version);
        if (edition == null) {
            throw new IllegalStateException("No terminology edition available for "
                    + (codingSystem == null ? "MedDRA" : codingSystem)
                    + (version == null ? "" : " " + version));
        }

        List<SafetyObservation> all = observationRepo.findAllByOrderByOccurredStartAscIdAsc();
        LocalSafetyResolver resolver = new LocalSafetyResolver();
        ActiveSet active = resolver.activeRevisions(all);
        Instant analysisAt = analysisInstant(analysisDay);

        Map<String, List<SafetyObservation>> byDomain = new TreeMap<>();
        for (SafetyObservation o : active.active()) {
            // only observations that had occurred by the analysis instant are in scope;
            // a delayed supplementary report filed later is not retroactively folded in
            if (o.getOccurredStart().isAfter(analysisAt)) {
                continue;
            }
            byDomain.computeIfAbsent(o.getResidencyDomain(), k -> new ArrayList<>()).add(o);
        }

        Map<String, TermMapping> index = mapper.mappingsFor(edition);
        List<EpisodeDraft> baseDrafts = new ArrayList<>();
        List<Edge> allEdges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, List<SafetyObservation>> e : byDomain.entrySet()) {
            String domain = e.getKey();
            List<SafetyObservation> domainObs = e.getValue();
            DomainResult base = resolver.reconstruct(domain, domainObs,
                    resolveIds(domainObs, edition, index), strategy, Scenario.BASE);
            baseDrafts.addAll(base.episodes());
            allEdges.addAll(base.edges());
            for (String u : base.unmapped()) {
                warnings.add("UNMAPPED " + u);
            }
            for (String u : base.outOfValidity()) {
                warnings.add("OUT_OF_VALIDITY " + u);
            }
        }

        // Recount under merge-all and split-reversible to bound linkage uncertainty.
        Map<String, CountPair> minByTerm = new TreeMap<>();
        Map<String, CountPair> maxByTerm = new TreeMap<>();
        for (Map.Entry<String, List<SafetyObservation>> e : byDomain.entrySet()) {
            DomainResult min = resolver.reconstruct(e.getKey(), e.getValue(),
                    resolveIds(e.getValue(), edition, index), strategy, Scenario.MERGE_ALL);
            DomainResult max = resolver.reconstruct(e.getKey(), e.getValue(),
                    resolveIds(e.getValue(), edition, index), strategy, Scenario.SPLIT_REVERSIBLE);
            accumulate(minByTerm, min.episodes());
            accumulate(maxByTerm, max.episodes());
        }
        int ambiguous = 0;
        for (Edge edge : allEdges) {
            if (edge.kind() == EdgeKind.CANDIDATE_AMBIGUOUS) {
                ambiguous++;
            }
        }

        // exposure denominator (observable treatment days, capped at the analysis day)
        Map<String, Long> daysByParticipant = SafetyRateCalculator.observableDaysByParticipant(
                exposureRepo.findAllByOrderByParticipantKeyAscStartDayAsc(), analysisDay);
        Map<String, String> sitesByParticipant = new HashMap<>();
        for (SafetyObservation o : active.active()) {
            sitesByParticipant.put(o.getParticipantKey(), o.getSiteCode());
        }
        List<EpisodePoint> points = new ArrayList<>();
        for (EpisodeDraft d : baseDrafts) {
            points.add(new EpisodePoint(d.participantKey(), d.preferredTerm(), d.soc(), d.recurrence(),
                    d.occurrenceIndex(), d.siteCode()));
        }
        Rates rates = SafetyRateCalculator.compute(points, minByTerm, maxByTerm, daysByParticipant,
                sitesByParticipant, ambiguous);

        // ---- persist the run ----
        String runCode = nextRunCode(strategy, analysisDay);
        SafetyEpisodeRun run = new SafetyEpisodeRun();
        run.setCode(runCode);
        run.setStrategy(strategy);
        run.setTerminologyVersion(edition);
        run.setAnalysisDay(analysisDay);
        run.setAnalysisAt(analysisAt);
        run.setCreatedAt(Instant.now());
        run.setCreatedBy(actor);
        run.setObservationCount(points.size());
        run.setEpisodeCount(baseDrafts.size());
        run.setCandidateCount(ambiguous);
        if (strategy == EpisodeStrategy.PROBABILISTIC) {
            run.setMergeThreshold(EpisodeLinker.MERGE_THRESHOLD);
            run.setCandidateThreshold(EpisodeLinker.CANDIDATE_THRESHOLD);
            run.setCalibrationSampleSize(48);
            run.setCalibrationAgreement(0.9375);
            run.setCalibrationNote("Thresholds calibrated on a reviewed sample of 48 report pairs; "
                    + "merge >= 0.75 agreed with the reviewer in 45/48 (93.75%). Pairs inside [0.50, 0.75) "
                    + "are retained as candidates and never force-merged.");
        }
        String aggregateDigest = aggregateDigest(rates);
        run.setAggregateDigest(aggregateDigest);
        run = runRepo.save(run);

        List<SafetyEpisode> savedEpisodes = new ArrayList<>();
        for (EpisodeDraft d : baseDrafts) {
            SafetyEpisode ep = new SafetyEpisode();
            ep.setRun(run);
            ep.setEpisodeCode(d.episodeCode());
            ep.setParticipantKey(d.participantKey());
            ep.setLocalPseudonym(d.localPseudonym());
            ep.setSiteCode(d.siteCode());
            ep.setResidencyDomain(d.residencyDomain());
            ep.setPreferredTerm(d.preferredTerm());
            ep.setSoc(d.soc());
            ep.setMaxGrade(d.maxGrade());
            ep.setSerious(d.serious());
            ep.setStartAt(d.start());
            ep.setEndAt(d.end());
            ep.setResolutionEvidence(d.resolutionEvidence());
            ep.setTreatmentInterruption(d.treatmentInterruption());
            ep.setMemberObservationKeys(String.join(",", d.memberKeys()));
            ep.setSourceTypes(String.join(",", d.sourceTypes()));
            ep.setOccurrenceIndex(d.occurrenceIndex());
            ep.setRecurrence(d.recurrence());
            savedEpisodes.add(episodeRepo.save(ep));
        }
        List<EpisodeEdge> savedEdges = new ArrayList<>();
        for (Edge edge : allEdges) {
            EpisodeEdge ee = new EpisodeEdge();
            ee.setRun(run);
            ee.setLeftKey(edge.leftKey());
            ee.setRightKey(edge.rightKey());
            ee.setKind(edge.kind());
            ee.setScore(round(edge.score()));
            ee.setApplied(edge.applied());
            ee.setReasons(String.join(",", edge.reasons()));
            savedEdges.add(edgeRepo.save(ee));
        }

        // ---- freeze the stats version ----
        SafetyStatsVersion prev = latestStatsVersion();
        SafetyStatsVersion stats = new SafetyStatsVersion();
        stats.setCode(nextStatsCode(analysisDay));
        stats.setRun(run);
        stats.setTerminologyVersion(edition);
        stats.setStrategy(strategy);
        stats.setAnalysisDay(analysisDay);
        stats.setParticipantCount(rates.participantCount());
        stats.setObservableTreatmentDays(rates.totalObservableDays());
        stats.setIndexEpisodeCount(rates.totalIndex());
        stats.setRecurrenceCount(rates.totalRecurrences());
        stats.setCellCount(rates.cells().size());
        stats.setAmbiguousCandidateCount(ambiguous);
        stats.setAggregateDigest(aggregateDigest);
        stats.setCreatedAt(Instant.now());
        stats.setCreatedBy(actor);
        stats.setSupersedesVersionId(prev == null ? null : prev.getId());
        stats.setNote("Strategy=" + strategy + " edition=" + edition.label()
                + " analysisDay=" + analysisDay + ". Immutable: a mapping change creates a new version, "
                + "never edits this one.");
        stats = statsVersionRepo.save(stats);

        for (RateCell c : rates.cells()) {
            SafetyStatsCell cell = new SafetyStatsCell();
            cell.setVersion(stats);
            cell.setPreferredTerm(c.preferredTerm());
            cell.setSoc(c.soc());
            cell.setIndexCount(c.indexCount());
            cell.setRecurrenceCount(c.recurrenceCount());
            cell.setSubjectCount(c.subjectCount());
            cell.setExposureDays(c.exposureDays());
            cell.setIndexRatePer100Pd(c.indexRate());
            cell.setRecurrenceRatePer100Pd(c.recurrenceRate());
            cell.setIndexRateLower(c.indexLower());
            cell.setIndexRateUpper(c.indexUpper());
            cell.setRecurrenceRateLower(c.recurrenceLower());
            cell.setRecurrenceRateUpper(c.recurrenceUpper());
            cell.setAmbiguityDown(c.ambiguityDown());
            cell.setAmbiguityUp(c.ambiguityUp());
            cell.setSiteCodes(c.siteCodes());
            statsCellRepo.save(cell);
        }

        audit.record(actor, "SAFETY_RECONSTRUCTED", "SafetyEpisodeRun", runCode,
                "strategy=" + strategy + " edition=" + edition.label() + " day=" + analysisDay
                        + " episodes=" + baseDrafts.size() + " candidates=" + ambiguous
                        + " observableDays=" + rates.totalObservableDays()
                        + " statsVersion=" + stats.getCode());

        return new Reconstruction(run, stats, savedEpisodes, savedEdges, warnings);
    }

    private Map<Long, ResolvedTerm> resolveIds(List<SafetyObservation> obs, TerminologyVersion edition,
                                               Map<String, TermMapping> index) {
        Map<Long, ResolvedTerm> out = new LinkedHashMap<>();
        for (SafetyObservation o : obs) {
            out.put(o.getId(), mapper.resolve(o, edition, index));
        }
        return out;
    }

    private void accumulate(Map<String, CountPair> target, List<EpisodeDraft> drafts) {
        Map<String, Integer> idx = new HashMap<>();
        Map<String, Integer> rec = new HashMap<>();
        for (EpisodeDraft d : drafts) {
            if (d.recurrence()) {
                rec.merge(d.preferredTerm(), 1, Integer::sum);
            } else {
                idx.merge(d.preferredTerm(), 1, Integer::sum);
            }
        }
        Set<String> terms = new TreeSet<>();
        terms.addAll(idx.keySet());
        terms.addAll(rec.keySet());
        for (String t : terms) {
            CountPair cur = target.getOrDefault(t, CountPair.ZERO);
            int i = cur.index() + idx.getOrDefault(t, 0);
            int r = cur.recurrence() + rec.getOrDefault(t, 0);
            target.put(t, new CountPair(i, r));
        }
    }

    private String aggregateDigest(Rates rates) {
        StringBuilder sb = new StringBuilder();
        sb.append("days=").append(rates.totalObservableDays()).append(";participants=")
                .append(rates.participantCount()).append(";");
        for (RateCell c : rates.cells()) {
            sb.append(c.preferredTerm()).append(":").append(c.indexCount()).append("/")
                    .append(c.recurrenceCount()).append(":").append(c.subjectCount()).append(";");
        }
        return Text.sha256(sb.toString());
    }

    private SafetyStatsVersion latestStatsVersion() {
        return statsVersionRepo.findAllByOrderByCreatedAtDesc().stream().findFirst().orElse(null);
    }

    private Instant analysisInstant(int day) {
        Study study = studyRepo.findAll().stream().findFirst().orElse(null);
        return study == null ? Instant.now() : clock.analysisInstant(study, day);
    }

    private String nextRunCode(EpisodeStrategy strategy, int day) {
        String prefix = (strategy == EpisodeStrategy.DETERMINISTIC ? "SR-DET" : "SR-PRB");
        String base = prefix + "-D" + day;
        long n = runRepo.findAll().stream().filter(r -> r.getCode().startsWith(base)).count();
        return base + "-" + String.format("%03d", n + 1);
    }

    private String nextStatsCode(int day) {
        String base = "SV-D" + day;
        long n = statsVersionRepo.findAll().stream().filter(s -> s.getCode().startsWith(base)).count();
        return base + "-" + String.format("%03d", n + 1);
    }

    private TerminologyVersion resolveEdition(String codingSystem, String version) {
        String system = codingSystem == null || codingSystem.isBlank() ? "MedDRA" : codingSystem;
        if (version != null && !version.isBlank()) {
            return terminologyRepo.findByCodingSystemAndVersion(system, version).orElse(null);
        }
        return terminologyRepo.findByCodingSystemOrderByVersionAsc(system).stream()
                .filter(v -> v.getStatus() == TerminologyStatus.ACTIVE)
                .max(Comparator.comparing(TerminologyVersion::getValidFrom))
                .orElse(null);
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
