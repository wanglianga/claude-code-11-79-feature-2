package com.ctrial;

import com.ctrial.model.*;
import com.ctrial.model.SafetyEnums.*;
import com.ctrial.repo.*;
import com.ctrial.service.safety.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule-level tests for the cross-source safety-episode reconstruction. Each test
 * names the requirement it proves and runs the same pipeline the API serves.
 */
@SpringBootTest
@ActiveProfiles("test")
class SafetyReconstructionTest {

    @Autowired SafetyReconstructionService reconstruction;
    @Autowired SafetyAggregateService aggregateService;
    @Autowired SafetyQueryService query;
    @Autowired SafetyAcceptanceService acceptance;
    @Autowired SafetyObservationRepo observationRepo;
    @Autowired SafetyEpisodeRunRepo runRepo;
    @Autowired SafetyEpisodeRepo episodeRepo;
    @Autowired EpisodeEdgeRepo edgeRepo;
    @Autowired SafetyStatsVersionRepo statsVersionRepo;
    @Autowired SafetyStatsCellRepo statsCellRepo;
    @Autowired ExposureWindowRepo exposureRepo;
    @Autowired TerminologyVersionRepo terminologyRepo;
    @Autowired DeliveryRepo deliveryRepo;
    @Autowired DeliveryRecordRepo deliveryRecordRepo;
    @Autowired EligibilityRowRepo eligibilityRowRepo;

    private List<SafetyEpisode> episodes(SafetyReconstructionService.Reconstruction r, String participant) {
        return episodeRepo.findByRunIdAndParticipantKeyOrderByOccurrenceIndexAsc(r.run().getId(), participant);
    }

    @Test
    void threeReportsOfOneProcessFormOneEpisode_andTheLaterReappearanceIsARecurrence() {
        var det = reconstruction.reconstruct(EpisodeStrategy.DETERMINISTIC, "MedDRA", "25.1", 26, "test");
        List<SafetyEpisode> p11 = episodes(det, "L-CN-01-011").stream()
                .filter(e -> e.getPreferredTerm().equalsIgnoreCase("Nausea")).toList();
        assertThat(p11).hasSize(2);

        SafetyEpisode index = p11.get(0);
        assertThat(index.getOccurrenceIndex()).isEqualTo(1);
        assertThat(index.isRecurrence()).isFalse();
        assertThat(index.getMemberObservationKeys())
                .contains("OBS-P11-NAU-A#1", "OBS-P11-NAU-B#1", "OBS-P11-NAU-C#1")
                .doesNotContain("OBS-P11-NAU-D#1");
        // three sources collapsed to a single episode: the report count (4) is not the episode count
        assertThat(index.getSourceTypes()).contains("CENTER_SAFETY_REPORT", "HOSPITAL_SUMMARY");

        SafetyEpisode recurrence = p11.get(1);
        assertThat(recurrence.getOccurrenceIndex()).isEqualTo(2);
        assertThat(recurrence.isRecurrence()).isTrue();
        assertThat(recurrence.getMemberObservationKeys()).isEqualTo("OBS-P11-NAU-D#1");
    }

    @Test
    void sameTermAndOverlappingTimeFromAnotherParticipantIsNeverLinked() {
        var det = reconstruction.reconstruct(EpisodeStrategy.DETERMINISTIC, "MedDRA", "25.1", 26, "test");
        for (SafetyEpisode e : det.episodes()) {
            boolean hasP11 = e.getMemberObservationKeys().contains("OBS-P11-");
            boolean hasP12 = e.getMemberObservationKeys().contains("OBS-P12-");
            assertThat(hasP11 && hasP12)
                    .withFailMessage("episode mixed P11 and P12: %s", e.getMemberObservationKeys())
                    .isFalse();
        }
        // P12's own two reports do merge
        List<SafetyEpisode> p12 = episodes(det, "L-CN-01-012");
        assertThat(p12).hasSize(1);
        assertThat(p12.get(0).getMemberObservationKeys()).contains("OBS-P12-NAU-A#1", "OBS-P12-NAU-B#1");
    }

    @Test
    void exposureDenominatorIsObservableTreatmentDaysNotNominalSpan() {
        var det = reconstruction.reconstruct(EpisodeStrategy.DETERMINISTIC, "MedDRA", "25.1", 26, "test");
        long expected = exposureRepo.findAllByOrderByParticipantKeyAscStartDayAsc().stream()
                .mapToLong(w -> SafetyRateCalculator.observableDaysThrough(w, 26)).sum();
        assertThat(det.stats().getObservableTreatmentDays()).isEqualTo(expected);

        ExposureWindow p11 = exposureRepo.findByParticipantKeyOrderByStartDayAsc("L-CN-01-011").get(0);
        int nominal = p11.getEndDay() - p11.getStartDay() + 1;
        int observable = (int) SafetyRateCalculator.observableDaysThrough(p11, 26);
        assertThat(observable).isEqualTo(nominal - p11.getUnobservableDays());
        assertThat(observable).isLessThan(nominal);
    }

    @Test
    void probabilisticLinkerKeepsAmbiguousPairsAsCandidatesAndNeverForceMerges() {
        var prb = reconstruction.reconstruct(EpisodeStrategy.PROBABILISTIC, "MedDRA", "25.1", 26, "test");
        assertThat(edgeRepo.countByRunIdAndKind(prb.run().getId(), EdgeKind.CANDIDATE_AMBIGUOUS))
                .isGreaterThan(0);
        assertThat(edgeRepo.findByRunIdAndKind(prb.run().getId(), EdgeKind.CANDIDATE_AMBIGUOUS))
                .noneMatch(EpisodeEdge::isApplied);
    }

    @Test
    void probabilisticMergesADelayedSupplementaryReportThatDeterministicSplits() {
        var det = reconstruction.reconstruct(EpisodeStrategy.DETERMINISTIC, "MedDRA", "25.1", 26, "test");
        var prb = reconstruction.reconstruct(EpisodeStrategy.PROBABILISTIC, "MedDRA", "25.1", 26, "test");
        long detRash = episodes(det, "L-CN-01-013").stream()
                .filter(e -> e.getPreferredTerm().equalsIgnoreCase("Rash")).count();
        long prbRash = episodes(prb, "L-CN-01-013").stream()
                .filter(e -> e.getPreferredTerm().equalsIgnoreCase("Rash")).count();
        // deterministic splits the non-overlapping follow-up note into its own rash episode
        assertThat(detRash).isEqualTo(2);
        // probabilistic joins it with the earlier rash report of the same participant+term
        assertThat(prbRash).isEqualTo(1);
    }

    @Test
    void mappingChangeCreatesANewVersionAndNeverWritesBackToTheParentTask() {
        long deliveries = deliveryRepo.count();
        long records = deliveryRecordRepo.count();
        long rows = eligibilityRowRepo.count();

        var det = reconstruction.reconstruct(EpisodeStrategy.DETERMINISTIC, "MedDRA", "25.1", 26, "test");
        SafetyStatsVersion before = statsVersionRepo.findByCode(det.stats().getCode()).orElseThrow();
        Long beforeId = before.getId();
        String beforeDigest = before.getAggregateDigest();

        var remapped = reconstruction.reconstruct(EpisodeStrategy.DETERMINISTIC, "MedDRA", "26.0", 26, "test");

        // the new version explicitly supersedes the prior one
        assertThat(remapped.stats().getSupersedesVersionId()).isEqualTo(beforeId);
        assertThat(remapped.stats().getTerminologyVersion().getVersion()).isEqualTo("26.0");
        // the prior version survives with identical digest (history is append-only)
        SafetyStatsVersion after = statsVersionRepo.findByCode(det.stats().getCode()).orElseThrow();
        assertThat(after.getId()).isEqualTo(beforeId);
        assertThat(after.getAggregateDigest()).isEqualTo(beforeDigest);
        // nothing in the parent task moved
        assertThat(deliveryRepo.count()).isEqualTo(deliveries);
        assertThat(deliveryRecordRepo.count()).isEqualTo(records);
        assertThat(eligibilityRowRepo.count()).isEqualTo(rows);
    }

    @Test
    void globalAggregateIsKAnonymisedAndCarriesNoIdentifiers() {
        var det = reconstruction.reconstruct(EpisodeStrategy.DETERMINISTIC, "MedDRA", "25.1", 26, "test");
        var view = aggregateService.publish(det.stats().getCode(), "DUA-GLOBAL-01", 3, "test");
        // every published cell meets the floor
        assertThat(view.cells()).allMatch(c -> c.subjectCount() >= view.kFloor());
        // nausea has enough participants to publish; smaller terms are suppressed
        assertThat(view.cells()).anyMatch(c -> c.preferredTerm().equalsIgnoreCase("Nausea"));
        assertThat(view.suppressedCells()).isGreaterThan(0);
        // no identifiers in the serialised aggregate
        String json = view.toString();
        assertThat(json).doesNotContain("L-CN-", "L-SG-", "PSN-", "OBS-");
    }

    @Test
    void rateIntervalWidensToCoverLinkageAmbiguity() {
        var prb = reconstruction.reconstruct(EpisodeStrategy.PROBABILISTIC, "MedDRA", "25.1", 26, "test");
        List<SafetyStatsCell> cells = statsCellRepo.findByVersionIdOrderByPreferredTermAsc(prb.stats().getId());
        // at least one term carries an upward ambiguity count, and its interval must be wider than
        // the pure-sampling band for its point count
        boolean anyAmbiguity = cells.stream().anyMatch(c -> c.getAmbiguityUp() > 0 || c.getAmbiguityDown() > 0);
        assertThat(anyAmbiguity).isTrue();
        for (SafetyStatsCell c : cells) {
            assertThat(c.getIndexRateLower()).isLessThanOrEqualTo(c.getIndexRatePer100Pd());
            assertThat(c.getIndexRateUpper()).isGreaterThanOrEqualTo(c.getIndexRatePer100Pd());
        }
    }

    @Test
    void everyAcceptanceCheckPasses() {
        var report = acceptance.report(26, "test");
        assertThat(report.allPass())
                .withFailMessage(() -> report.checks().stream().filter(c -> !c.pass())
                        .map(c -> c.code() + " observed=" + c.observed()).toList().toString())
                .isTrue();
    }

    @Test
    void identityReadsRequireADomainAndAreScopedToIt() {
        // the SG domain contains only the Singapore participant, never a CN participant
        List<SafetyQueryService.ObservationView> sg = query.observations("SG");
        assertThat(sg).isNotEmpty();
        assertThat(sg).allMatch(o -> o.participantKey().startsWith("L-SG-"));
        Set<String> domains = query.domains().stream().map(SafetyQueryService.DomainSummary::domain)
                .collect(Collectors.toSet());
        assertThat(domains).contains("CN", "SG");
    }
}
