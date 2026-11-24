package com.ctrial.config;

import com.ctrial.common.EventHash;
import com.ctrial.model.*;
import com.ctrial.model.SafetyEnums.*;
import com.ctrial.repo.*;
import com.ctrial.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Seeds the cross-source adverse-event reconstruction scenario. Study days use
 * the same epoch as {@link DemoSeeder} (day 1 = 2026-03-02T09:00Z).
 *
 * <p>The headline case, P11, is one clinical process spread across three
 * sources and then a genuine recurrence:
 * <pre>
 *  D03  Center safety report:  nausea, grade 2, started 09:00, no end recorded yet
 *  D04  Hospital summary:      nausea, grade 2, documented 19:00 (overlaps the D03 range)
 *  D07  Center safety report:  nausea, explicitly resolved (end recorded), treatment interruption
 *  D20  Center safety report:  nausea recurs, grade 2 (a NEW episode — the D07 report resolved)
 * </pre>
 * The first three reports must collapse to <b>one</b> episode (index); the D20
 * report must be a <b>second, recurrence</b> episode. A second participant P12
 * has the same term and overlapping time but is a different person, so it must
 * never be linked to P11. A third participant P13 exercises a same-SOC,
 * different-PT pair (probabilistic only) and a delayed supplementary report that
 * arrives without overlapping time (split under the deterministic strategy).
 */
@Component
@Order(20)
public class SafetyDemoSeeder implements ApplicationRunner {

    /** P11 exposure: dosing on days 1..25, with an unobservable gap of 3 days (dose hold, no follow-up). */
    private static final int P11_START = 1;
    private static final int P11_END = 25;
    private static final int P11_GAP = 3;

    private final StudyRepo studyRepo;
    private final SafetyObservationRepo observationRepo;
    private final ExposureWindowRepo exposureRepo;
    private final TerminologyVersionRepo terminologyRepo;
    private final TermMappingRepo mappingRepo;
    private final AuditService audit;
    private final boolean seed;

    public SafetyDemoSeeder(StudyRepo studyRepo, SafetyObservationRepo observationRepo,
                            ExposureWindowRepo exposureRepo, TerminologyVersionRepo terminologyRepo,
                            TermMappingRepo mappingRepo, AuditService audit,
                            @Value("${app.demo.seed-on-startup:true}") boolean seed) {
        this.studyRepo = studyRepo;
        this.observationRepo = observationRepo;
        this.exposureRepo = exposureRepo;
        this.terminologyRepo = terminologyRepo;
        this.mappingRepo = mappingRepo;
        this.audit = audit;
        this.seed = seed;
    }

    public static Instant day(int d) {
        return DemoSeeder.day(d);
    }

    public static Instant hour(int d, int h) {
        return DemoSeeder.day(d).plus(h, ChronoUnit.HOURS);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seed || studyRepo.count() == 0 || observationRepo.count() > 0) {
            return;
        }

        // ---------------- terminology ----------------
        TerminologyVersion meddra25 = terminologyRepo.save(new TerminologyVersion("MedDRA", "25.1",
                day(1), null,
                "Active edition for the reconstruction. Validity window starts on study day 1."));
        TerminologyVersion meddra26 = terminologyRepo.save(new TerminologyVersion("MedDRA", "26.0",
                day(28), null,
                "Next edition, effective day 28. A run pinned on 26.0 supersedes a 25.1 run; it never rewrites it."));

        // v25.1 mappings
        mapping(meddra25, "nausea", "10028813", "Nausea", "10028813", "Gastrointestinal signs and symptoms",
                "Gastrointestinal disorders", day(1), null);
        mapping(meddra25, "vomiting", "10047749", "Vomiting", "10028813", "Gastrointestinal signs and symptoms",
                "Gastrointestinal disorders", day(1), null);
        mapping(meddra25, "neutropenia", "10029354", "Neutropenia", "10029354",
                "White blood cell and differential count decreased", "Blood and lymphatic system disorders",
                day(1), null);
        mapping(meddra25, "febrile neutropenia", "10016288", "Febrile neutropenia", "10016288",
                "White blood cell and differential count decreased", "Blood and lymphatic system disorders",
                day(1), null);
        mapping(meddra25, "rash", "10037844", "Rash", "10037844", "Rashes, eruptions and exanthems NEC",
                "Skin and subcutaneous tissue disorders", day(1), null);
        mapping(meddra25, "dermatitis", "10012431", "Dermatitis", "10037844",
                "Rashes, eruptions and exanthems NEC", "Skin and subcutaneous tissue disorders", day(1), null);

        // v26.0 recodes "vomiting": under 26.0 it moves to a different HLT, and "nausea" stays.
        // This is what makes a new stats version differ from the old one without rewriting it.
        mapping(meddra26, "nausea", "10028813", "Nausea", "10028813",
                "Gastrointestinal signs and symptoms", "Gastrointestinal disorders", day(28), null);
        mapping(meddra26, "vomiting", "10047749", "Vomiting", "10028814",
                "Nausea and vomiting symptoms", "Gastrointestinal disorders", day(28), null);
        mapping(meddra26, "neutropenia", "10029354", "Neutropenia", "10029354",
                "White blood cell and differential count decreased", "Blood and lymphatic system disorders",
                day(28), null);
        mapping(meddra26, "febrile neutropenia", "10016288", "Febrile neutropenia", "10016288",
                "White blood cell and differential count decreased", "Blood and lymphatic system disorders",
                day(28), null);
        mapping(meddra26, "rash", "10037844", "Rash", "10037844", "Rashes, eruptions and exanthems NEC",
                "Skin and subcutaneous tissue disorders", day(28), null);
        mapping(meddra26, "dermatitis", "10012431", "Dermatitis", "10037844",
                "Rashes, eruptions and exanthems NEC", "Skin and subcutaneous tissue disorders", day(28), null);

        // ---------------- exposure windows ----------------
        exposure("L-CN-01-011", "PSN-P11", "CN-01", "CN", P11_START, P11_END, P11_GAP,
                "Dose hold days 12..14 with no safety follow-up; those days earn no exposure credit.");
        exposure("L-CN-01-012", "PSN-P12", "CN-02", "CN", 1, 21, 0,
                "Continuous dosing to day 21.");
        exposure("L-CN-01-013", "PSN-P13", "CN-01", "CN", 1, 30, 5,
                "Two short holds totalling 5 unobservable days.");

        // ---------------- P11: one episode across three sources, then a recurrence ----------------
        obs("OBS-P11-NAU-A", 1, "L-CN-01-011", "PSN-P11", "CN-01", "CN",
                SourceType.CENTER_SAFETY_REPORT, "CN-SAFETY", "SAE-P11-001",
                "nausea", "MedDRA", "25.1", hour(3, 9), null, 2, false, false, false,
                hour(3, 10));
        obs("OBS-P11-NAU-B", 1, "L-CN-01-011", "PSN-P11", "CN-01", "CN",
                SourceType.HOSPITAL_SUMMARY, "CN-HOSP-SUM", "HSUM-P11-77",
                "nausea", "MedDRA", "25.1", hour(4, 8), null, 2, false, false, false,
                hour(4, 21));
        obs("OBS-P11-NAU-C", 1, "L-CN-01-011", "PSN-P11", "CN-01", "CN",
                SourceType.CENTER_SAFETY_REPORT, "CN-SAFETY", "SAE-P11-014",
                "nausea", "MedDRA", "25.1", hour(3, 9), hour(7, 18), 2, false, true, true,
                hour(7, 19));
        obs("OBS-P11-NAU-D", 1, "L-CN-01-011", "PSN-P11", "CN-01", "CN",
                SourceType.CENTER_SAFETY_REPORT, "CN-SAFETY", "SAE-P11-061",
                "nausea", "MedDRA", "25.1", hour(20, 8), null, 2, false, false, false,
                hour(20, 9));

        // ---------------- P12: same term, overlapping time, DIFFERENT participant ----------------
        obs("OBS-P12-NAU-A", 1, "L-CN-01-012", "PSN-P12", "CN-02", "CN",
                SourceType.CENTER_SAFETY_REPORT, "CN-SAFETY", "SAE-P12-003",
                "nausea", "MedDRA", "25.1", hour(3, 10), null, 2, false, false, false,
                hour(3, 12));
        obs("OBS-P12-NAU-B", 1, "L-CN-01-012", "PSN-P12", "CN-02", "CN",
                SourceType.HOSPITAL_SUMMARY, "CN-HOSP-SUM", "HSUM-P12-19",
                "nausea", "MedDRA", "25.1", hour(4, 9), hour(6, 12), 2, false, true, false,
                hour(6, 13));

        // ---------------- P13: same SOC different PT + a delayed supplementary report ----------------
        obs("OBS-P13-RASH-A", 1, "L-CN-01-013", "PSN-P13", "CN-01", "CN",
                SourceType.CENTER_SAFETY_REPORT, "CN-SAFETY", "SAE-P13-002",
                "rash", "MedDRA", "25.1", hour(5, 9), hour(5, 18), 2, false, false, false,
                hour(5, 19));
        obs("OBS-P13-DERM-B", 1, "L-CN-01-013", "PSN-P13", "CN-01", "CN",
                SourceType.HOSPITAL_SUMMARY, "CN-HOSP-SUM", "HSUM-P13-05",
                "dermatitis", "MedDRA", "25.1", hour(5, 14), null, 2, false, false, false,
                hour(5, 20));
        // Delayed supplementary report for the same rash, filed later with a time stamp that
        // does NOT overlap the day-5 range. The deterministic strategy (same term + explicit
        // overlap) splits it into a second rash episode; the probabilistic strategy recognises
        // the same participant + exact term + 1-day gap and merges it. This is the documented
        // trade-off: deterministic is easy to audit but splits delayed back-reports.
        obs("OBS-P13-RASH-C", 1, "L-CN-01-013", "PSN-P13", "CN-01", "CN",
                SourceType.FOLLOW_UP_NOTE, "CN-SAFETY", "SAE-P13-021",
                "rash", "MedDRA", "25.1", hour(7, 8), null, 2, false, false, false,
                hour(16, 9));

        // ---------------- P14: laboratory abnormality escalation (neutropenia -> febrile neutropenia) --------
        obs("OBS-P14-NEU-A", 1, "L-CN-01-014", "PSN-P14", "CN-01", "CN",
                SourceType.LAB_ABNORMALITY, "CN-LAB", "LAB-P14-ANC",
                "neutropenia", "MedDRA", "25.1", hour(6, 7), null, 3, true, false, true,
                hour(6, 9));
        obs("OBS-P14-FEB-B", 1, "L-CN-01-014", "PSN-P14", "CN-01", "CN",
                SourceType.CENTER_SAFETY_REPORT, "CN-SAFETY", "SAE-P14-009",
                "febrile neutropenia", "MedDRA", "25.1", hour(6, 20), null, 4, true, false, false,
                hour(6, 22));

        // P14 exposure
        exposure("L-CN-01-014", "PSN-P14", "CN-01", "CN", 1, 18, 0, "Continuous dosing to day 18.");

        // ---------------- additional nausea participants so a term clears the k-anonymity floor -------
        // P21 (CN-01), P22 (SG-01, a different residency domain) and P23 (CN-02) all report nausea, so
        // the nausea cell has >= 3 participants and may be published, while single-participant terms
        // (rash, neutropenia, ...) stay suppressed. This demonstrates the global aggregate's floor.
        exposure("L-CN-01-021", "PSN-P21", "CN-01", "CN", 1, 20, 0, "Continuous dosing to day 20.");
        exposure("L-SG-01-022", "PSN-P22", "SG-01", "SG", 1, 22, 0, "Continuous dosing to day 22 (SEA site).");
        exposure("L-CN-02-023", "PSN-P23", "CN-02", "CN", 1, 19, 0, "Continuous dosing to day 19.");

        obs("OBS-P21-NAU-A", 1, "L-CN-01-021", "PSN-P21", "CN-01", "CN",
                SourceType.CENTER_SAFETY_REPORT, "CN-SAFETY", "SAE-P21-001",
                "nausea", "MedDRA", "25.1", hour(6, 9), hour(6, 20), 2, false, true, false, hour(6, 21));
        obs("OBS-P22-NAU-A", 1, "L-SG-01-022", "PSN-P22", "SG-01", "SG",
                SourceType.HOSPITAL_SUMMARY, "SEA-HOSP-SUM", "HSUM-P22-04",
                "nausea", "MedDRA", "25.1", hour(8, 10), hour(8, 22), 2, false, true, false, hour(8, 23));
        obs("OBS-P23-NAU-A", 1, "L-CN-02-023", "PSN-P23", "CN-02", "CN",
                SourceType.CENTER_SAFETY_REPORT, "CN-SAFETY", "SAE-P23-002",
                "nausea", "MedDRA", "25.1", hour(10, 11), hour(10, 21), 2, false, true, false, hour(10, 22));

        audit.record("system:seed", "SAFETY_DEMO_SEEDED", "Study",
                studyRepo.findAll().stream().findFirst().map(Study::getCode).orElse("CT-2026-017"),
                "Safety reconstruction scenario seeded: P11 (3-source single episode + day-20 recurrence), "
                        + "P12 (same term, different participant — must not link), P13 (same-SOC pair + delayed "
                        + "supplementary report), P14 (ANC escalation). MedDRA 25.1 active; 26.0 effective day 28.");
    }

    private void mapping(TerminologyVersion version, String sourceTerm, String code, String pt, String hltCode,
                         String hlt, String soc, Instant validFrom, Instant validTo) {
        TermMapping m = new TermMapping();
        m.setTerminologyVersion(version);
        m.setSourceTerm(sourceTerm);
        m.setTermCode(code);
        m.setPreferredTerm(pt);
        m.setHltCode(hltCode);
        m.setHlt(hlt);
        m.setHlgt(hlt);
        m.setSoc(soc);
        m.setValidFrom(validFrom);
        m.setValidTo(validTo);
        m.setActive(true);
        mappingRepo.save(m);
    }

    private void exposure(String participantKey, String pseudonym, String site, String domain,
                          int startDay, int endDay, int unobservable, String note) {
        ExposureWindow w = new ExposureWindow();
        w.setParticipantKey(participantKey);
        w.setLocalPseudonym(pseudonym);
        w.setSiteCode(site);
        w.setResidencyDomain(domain);
        w.setStartDay(startDay);
        w.setEndDay(endDay);
        w.setUnobservableDays(unobservable);
        w.setNote(note);
        w.setCreatedAt(Instant.now());
        exposureRepo.save(w);
    }

    private void obs(String groupKey, int revision, String participantKey, String pseudonym, String site,
                     String domain, SourceType sourceType, String sourceSystem, String reportRef,
                     String verbatimTerm, String codingSystem, String codingVersion,
                     Instant occurredStart, Instant occurredEnd, int grade, boolean serious,
                     boolean resolved, boolean treatmentInterrupted, Instant reportedAt) {
        SafetyObservation o = new SafetyObservation();
        o.setObservationGroupKey(groupKey);
        o.setRevision(revision);
        o.setParticipantKey(participantKey);
        o.setLocalPseudonym(pseudonym);
        o.setSiteCode(site);
        o.setResidencyDomain(domain);
        o.setSourceType(sourceType);
        o.setSourceSystem(sourceSystem);
        o.setSourceReportRef(reportRef);
        o.setVerbatimTerm(verbatimTerm);
        o.setCodingSystem(codingSystem);
        o.setCodingVersion(codingVersion);
        o.setOccurredStart(occurredStart);
        o.setOccurredEnd(occurredEnd);
        o.setSeverityGrade(grade);
        o.setSerious(serious);
        o.setResolved(resolved);
        o.setTreatmentInterrupted(treatmentInterrupted);
        o.setReportedAt(reportedAt);
        o.setVoided(false);
        o.setContentHash(EventHash.of(groupKey, revision, "AE", occurredStart, codingVersion,
                sourceType.name(), verbatimTerm + "|" + grade + "|" + resolved, false));
        observationRepo.save(o);
    }
}
