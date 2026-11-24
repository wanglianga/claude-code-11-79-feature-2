package com.ctrial.service.safety;

import com.ctrial.model.*;
import com.ctrial.model.SafetyEnums.*;
import com.ctrial.repo.*;
import com.ctrial.service.StudyClock;
import com.ctrial.service.safety.EpisodeLinker.Edge;
import com.ctrial.service.safety.LocalSafetyResolver.ActiveSet;
import com.ctrial.service.safety.LocalSafetyResolver.DomainResult;
import com.ctrial.service.safety.LocalSafetyResolver.EpisodeDraft;
import com.ctrial.service.safety.TerminologyMapper.ResolvedTerm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Read model for the safety screens. Everything that names a participant is
 * <b>domain-local</b>: a caller asks for one residence domain and only sees that
 * domain's observations, episodes and edges. There is no method here that returns
 * per-participant data across domains — the cross-domain view is the k-anonymised
 * aggregate produced by {@link SafetyAggregateService}.
 */
@Service
public class SafetyQueryService {

    private final SafetyObservationRepo observationRepo;
    private final ExposureWindowRepo exposureRepo;
    private final SafetyEpisodeRunRepo runRepo;
    private final SafetyEpisodeRepo episodeRepo;
    private final EpisodeEdgeRepo edgeRepo;
    private final TerminologyVersionRepo terminologyRepo;
    private final TerminologyMapper mapper;
    private final StudyClock clock;
    private final SafetyStatsVersionRepo statsVersionRepo;
    private final SafetyStatsCellRepo statsCellRepo;

    public SafetyQueryService(SafetyObservationRepo observationRepo, ExposureWindowRepo exposureRepo,
                              SafetyEpisodeRunRepo runRepo, SafetyEpisodeRepo episodeRepo,
                              EpisodeEdgeRepo edgeRepo, TerminologyVersionRepo terminologyRepo,
                              TerminologyMapper mapper, StudyClock clock,
                              SafetyStatsVersionRepo statsVersionRepo, SafetyStatsCellRepo statsCellRepo) {
        this.observationRepo = observationRepo;
        this.exposureRepo = exposureRepo;
        this.runRepo = runRepo;
        this.episodeRepo = episodeRepo;
        this.edgeRepo = edgeRepo;
        this.terminologyRepo = terminologyRepo;
        this.mapper = mapper;
        this.clock = clock;
        this.statsVersionRepo = statsVersionRepo;
        this.statsCellRepo = statsCellRepo;
    }

    public record ObservationView(Long id, String groupKey, int revision, String participantKey,
                                  String localPseudonym, String siteCode, String residencyDomain,
                                  String sourceType, String sourceSystem, String sourceReportRef,
                                  String verbatimTerm, String codingSystem, String codingVersion,
                                  String occurredStart, String occurredEnd, int severityGrade,
                                  boolean serious, boolean resolved, boolean treatmentInterrupted,
                                  String reportedAt, boolean voided, String mappedPreferredTerm,
                                  String mappedSoc, String mappingStatus, String mappingDetail) {
    }

    public record DomainSummary(String domain, int observationCount, int participantCount,
                                List<String> sites, List<String> sources) {
    }

    /** Enumerate residence domains so the UI never has to guess one. */
    @Transactional(readOnly = true)
    public List<DomainSummary> domains() {
        List<SafetyObservation> all = observationRepo.findAllByOrderByOccurredStartAscIdAsc();
        Map<String, List<SafetyObservation>> byDomain = new TreeMap<>();
        for (SafetyObservation o : all) {
            byDomain.computeIfAbsent(o.getResidencyDomain(), k -> new ArrayList<>()).add(o);
        }
        List<DomainSummary> out = new ArrayList<>();
        for (Map.Entry<String, List<SafetyObservation>> e : byDomain.entrySet()) {
            Set<String> participants = new TreeSet<>();
            Set<String> sites = new TreeSet<>();
            Set<String> sources = new TreeSet<>();
            for (SafetyObservation o : e.getValue()) {
                participants.add(o.getParticipantKey());
                sites.add(o.getSiteCode());
                sources.add(o.getSourceType().name());
            }
            out.add(new DomainSummary(e.getKey(), e.getValue().size(), participants.size(),
                    new ArrayList<>(sites), new ArrayList<>(sources)));
        }
        return out;
    }

    /** Raw observations for one domain, with the term resolution shown beside each row. */
    @Transactional(readOnly = true)
    public List<ObservationView> observations(String domain) {
        TerminologyVersion edition = mapper.activeEdition("MedDRA");
        Map<String, TermMapping> index = edition == null ? Map.of() : mapper.mappingsFor(edition);
        List<ObservationView> out = new ArrayList<>();
        for (SafetyObservation o : observationRepo.findAllByOrderByOccurredStartAscIdAsc()) {
            if (!o.getResidencyDomain().equals(domain)) {
                continue;
            }
            ResolvedTerm rt = edition == null ? null : mapper.resolve(o, edition, index);
            out.add(new ObservationView(o.getId(), o.getObservationGroupKey(), o.getRevision(),
                    o.getParticipantKey(), o.getLocalPseudonym(), o.getSiteCode(), o.getResidencyDomain(),
                    o.getSourceType().name(), o.getSourceSystem(), o.getSourceReportRef(),
                    o.getVerbatimTerm(), o.getCodingSystem(), o.getCodingVersion(),
                    o.getOccurredStart() == null ? null : o.getOccurredStart().toString(),
                    o.getOccurredEnd() == null ? null : o.getOccurredEnd().toString(),
                    o.getSeverityGrade(), o.isSerious(), o.isResolved(), o.isTreatmentInterrupted(),
                    o.getReportedAt() == null ? null : o.getReportedAt().toString(), o.isVoided(),
                    rt == null ? null : rt.preferredTerm(), rt == null ? null : rt.soc(),
                    rt == null ? null : rt.status().name(), rt == null ? null : rt.detail()));
        }
        return out;
    }

    public record EpisodeView(String episodeCode, String participantKey, String localPseudonym,
                              String siteCode, String preferredTerm, String soc, int maxGrade,
                              boolean serious, String startAt, String endAt, boolean resolutionEvidence,
                              boolean treatmentInterruption, List<String> memberKeys,
                              List<String> sourceTypes, int occurrenceIndex, boolean recurrence) {
    }

    public record EdgeView(String leftKey, String rightKey, String kind, double score, boolean applied,
                           List<String> reasons) {
    }

    public record RunSummary(String code, String strategy, String edition, int analysisDay, int observationCount,
                             int episodeCount, int candidateCount, String aggregateDigest, String createdAt) {
    }

    public record RunDetail(RunSummary run, List<EpisodeView> episodes, List<EdgeView> edges,
                            List<String> warnings) {
    }

    /** Episodes for one run, restricted to one residence domain. Identity stays local. */
    @Transactional(readOnly = true)
    public RunDetail runDetail(String runCode, String domain) {
        SafetyEpisodeRun run = runRepo.findByCode(runCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown safety run " + runCode));
        List<EpisodeView> episodes = new ArrayList<>();
        for (SafetyEpisode e : episodeRepo.findByRunIdOrderByParticipantKeyAscOccurrenceIndexAsc(run.getId())) {
            if (domain != null && !domain.isBlank() && !e.getResidencyDomain().equals(domain)) {
                continue;
            }
            episodes.add(new EpisodeView(e.getEpisodeCode(), e.getParticipantKey(), e.getLocalPseudonym(),
                    e.getSiteCode(), e.getPreferredTerm(), e.getSoc(), e.getMaxGrade(), e.isSerious(),
                    e.getStartAt() == null ? null : e.getStartAt().toString(),
                    e.getEndAt() == null ? null : e.getEndAt().toString(),
                    e.isResolutionEvidence(), e.isTreatmentInterruption(),
                    splitCsv(e.getMemberObservationKeys()), splitCsv(e.getSourceTypes()),
                    e.getOccurrenceIndex(), e.isRecurrence()));
        }
        List<EdgeView> edges = new ArrayList<>();
        for (EpisodeEdge edge : edgeRepo.findByRunIdOrderByScoreDesc(run.getId())) {
            edges.add(new EdgeView(edge.getLeftKey(), edge.getRightKey(), edge.getKind().name(),
                    edge.getScore(), edge.isApplied(), splitCsv(edge.getReasons())));
        }
        return new RunDetail(summary(run), episodes, edges, List.of());
    }

    @Transactional(readOnly = true)
    public List<RunSummary> runs() {
        List<RunSummary> out = new ArrayList<>();
        for (SafetyEpisodeRun run : runRepo.findAllByOrderByCreatedAtDesc()) {
            out.add(summary(run));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<RunSummary> latestRun(EpisodeStrategy strategy) {
        return runRepo.findByStrategyOrderByCreatedAtDesc(strategy).stream().findFirst().map(this::summary);
    }

    private RunSummary summary(SafetyEpisodeRun run) {
        return new RunSummary(run.getCode(), run.getStrategy().name(), run.getTerminologyVersion().label(),
                run.getAnalysisDay(), run.getObservationCount(), run.getEpisodeCount(), run.getCandidateCount(),
                run.getAggregateDigest(), run.getCreatedAt() == null ? null : run.getCreatedAt().toString());
    }

    public record ExposureView(String participantKey, String localPseudonym, String siteCode,
                               String residencyDomain, int startDay, int endDay, int unobservableDays,
                               int observableDays, String note) {
    }

    public record StatsSummary(String code, String strategy, String edition, int analysisDay,
                               int participantCount, long observableTreatmentDays, int indexEpisodeCount,
                               int recurrenceCount, int cellCount, int ambiguousCandidateCount,
                               String aggregateDigest, String createdAt, Long supersedesVersionId) {
    }

    public record StatsCellView(String preferredTerm, String soc, int indexCount, int recurrenceCount,
                                int subjectCount, long exposureDays, double indexRate, double recurrenceRate,
                                double indexLower, double indexUpper, double recurrenceLower,
                                double recurrenceUpper, int ambiguityDown, int ambiguityUp, String siteCodes) {
    }

    public record StatsDetail(StatsSummary version, List<StatsCellView> cells) {
    }

    @Transactional(readOnly = true)
    public List<StatsSummary> statsVersions() {
        List<StatsSummary> out = new ArrayList<>();
        for (SafetyStatsVersion v : statsVersionRepo.findAllByOrderByCreatedAtDesc()) {
            out.add(statsSummary(v));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<StatsDetail> statsDetail(String code) {
        return statsVersionRepo.findByCode(code).map(v -> {
            List<StatsCellView> cells = new ArrayList<>();
            for (SafetyStatsCell c : statsCellRepo.findByVersionIdOrderByPreferredTermAsc(v.getId())) {
                cells.add(new StatsCellView(c.getPreferredTerm(), c.getSoc(), c.getIndexCount(),
                        c.getRecurrenceCount(), c.getSubjectCount(), c.getExposureDays(),
                        c.getIndexRatePer100Pd(), c.getRecurrenceRatePer100Pd(), c.getIndexRateLower(),
                        c.getIndexRateUpper(), c.getRecurrenceRateLower(), c.getRecurrenceRateUpper(),
                        c.getAmbiguityDown(), c.getAmbiguityUp(), c.getSiteCodes()));
            }
            return new StatsDetail(statsSummary(v), cells);
        });
    }

    private StatsSummary statsSummary(SafetyStatsVersion v) {
        return new StatsSummary(v.getCode(), v.getStrategy().name(), v.getTerminologyVersion().label(),
                v.getAnalysisDay(), v.getParticipantCount(), v.getObservableTreatmentDays(),
                v.getIndexEpisodeCount(), v.getRecurrenceCount(), v.getCellCount(),
                v.getAmbiguousCandidateCount(), v.getAggregateDigest(),
                v.getCreatedAt() == null ? null : v.getCreatedAt().toString(), v.getSupersedesVersionId());
    }

    @Transactional(readOnly = true)
    public List<ExposureView> exposures(String domain, int analysisDay) {
        List<ExposureView> out = new ArrayList<>();
        for (ExposureWindow w : exposureRepo.findAllByOrderByParticipantKeyAscStartDayAsc()) {
            if (domain != null && !domain.isBlank() && !w.getResidencyDomain().equals(domain)) {
                continue;
            }
            long observable = SafetyRateCalculator.observableDaysThrough(w, analysisDay);
            out.add(new ExposureView(w.getParticipantKey(), w.getLocalPseudonym(), w.getSiteCode(),
                    w.getResidencyDomain(), w.getStartDay(), w.getEndDay(), w.getUnobservableDays(),
                    (int) observable, w.getNote()));
        }
        return out;
    }

    public record TerminologyView(String codingSystem, String version, String validFrom, String validTo,
                                  String status, int mappingCount, List<TermView> terms) {
    }

    public record TermView(String sourceTerm, String preferredTerm, String termCode, String hlt, String hlgt,
                           String soc, String validFrom, String validTo, boolean active) {
    }

    @Transactional(readOnly = true)
    public List<TerminologyView> terminologies() {
        List<TerminologyView> out = new ArrayList<>();
        for (TerminologyVersion v : terminologyRepo.findAllByOrderByCodingSystemAscVersionAsc()) {
            List<TermView> terms = new ArrayList<>();
            for (TermMapping m : mapper.mappingsFor(v).values()) {
                terms.add(new TermView(m.getSourceTerm(), m.getPreferredTerm(), m.getTermCode(), m.getHlt(),
                        m.getHlgt(), m.getSoc(), m.getValidFrom().toString(),
                        m.getValidTo() == null ? null : m.getValidTo().toString(), m.isActive()));
            }
            terms.sort(Comparator.comparing(TermView::sourceTerm));
            out.add(new TerminologyView(v.getCodingSystem(), v.getVersion(), v.getValidFrom().toString(),
                    v.getValidTo() == null ? null : v.getValidTo().toString(), v.getStatus().name(),
                    terms.size(), terms));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ResolvedTerm> termCoverage(String domain) {
        TerminologyVersion edition = mapper.activeEdition("MedDRA");
        if (edition == null) {
            return List.of();
        }
        Map<String, TermMapping> index = mapper.mappingsFor(edition);
        List<ResolvedTerm> out = new ArrayList<>();
        for (SafetyObservation o : observationRepo.findAllByOrderByOccurredStartAscIdAsc()) {
            if (domain == null || o.getResidencyDomain().equals(domain)) {
                out.add(mapper.resolve(o, edition, index));
            }
        }
        return out;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
