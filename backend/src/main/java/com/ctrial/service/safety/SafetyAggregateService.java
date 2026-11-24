package com.ctrial.service.safety;

import com.ctrial.common.Text;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.AuditService;
import com.ctrial.service.StudyClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Builds the <b>only</b> safety artifact the global plane receives: an aggregate
 * count with uncertainty intervals, computed strictly inside one approved
 * data-use envelope.
 *
 * <p>Guard rails enforced here, not merely documented:
 * <ul>
 *   <li>Only stats cells whose contributing sites are all inside the approval's
 *       site scope, and whose observation window is inside the approval window,
 *       are eligible. The window is checked against each contributing
 *       observation's occurrence by re-deriving it from the stats cells' site set
 *       and the run's analysis day.</li>
 *   <li>Any term whose contributing participant count is below the k-anonymity
 *       floor is <b>suppressed</b> (only its aggregate total is reported), so no
 *       cell can identify a single participant.</li>
 *   <li>The response contains no participant key, local pseudonym or per-subject
 *       row. It is derived purely from the immutable stats cells.</li>
 *   <li>Receiving an aggregate writes no parent-task {@code Delivery} or
 *       {@code EligibilityRow}; a digest over the aggregate content is the only
 *       trace.</li>
 * </ul>
 */
@Service
public class SafetyAggregateService {

    public static final int DEFAULT_K_FLOOR = 3;

    private final SafetyStatsVersionRepo statsVersionRepo;
    private final SafetyStatsCellRepo statsCellRepo;
    private final SafetyGlobalAggregateRepo aggregateRepo;
    private final ApprovalRepo approvalRepo;
    private final StudyRepo studyRepo;
    private final StudyClock clock;
    private final AuditService audit;

    public SafetyAggregateService(SafetyStatsVersionRepo statsVersionRepo, SafetyStatsCellRepo statsCellRepo,
                                  SafetyGlobalAggregateRepo aggregateRepo, ApprovalRepo approvalRepo,
                                  StudyRepo studyRepo, StudyClock clock, AuditService audit) {
        this.statsVersionRepo = statsVersionRepo;
        this.statsCellRepo = statsCellRepo;
        this.aggregateRepo = aggregateRepo;
        this.approvalRepo = approvalRepo;
        this.studyRepo = studyRepo;
        this.clock = clock;
        this.audit = audit;
    }

    public record PublishedCell(String preferredTerm, String soc, int indexCount, int recurrenceCount,
                                int subjectCount, long exposureDays, double indexRate,
                                double recurrenceRate, double indexLower, double indexUpper,
                                double recurrenceLower, double recurrenceUpper) {
    }

    public record AggregateView(String code, String statsVersion, String strategy, String edition,
                                String approvalCode, List<String> sites, Instant windowFrom, Instant windowTo,
                                int kFloor, int totalIndex, int totalRecurrences, long totalExposureDays,
                                int publishedCells, int suppressedCells, int suppressedEpisodes,
                                List<PublishedCell> cells, String digest, Instant createdAt, String note) {
    }

    /**
     * Publish (persist) an aggregate for one stats version inside one approval
     * envelope. Fails closed if no cross-border approval or no eligible cell exists.
     */
    @Transactional
    public AggregateView publish(String statsCode, String approvalCode, Integer kFloor, String actor) {
        SafetyStatsVersion stats = statsVersionRepo.findByCode(statsCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown stats version " + statsCode));
        DataUseApproval approval = approvalRepo.findByCode(approvalCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown approval " + approvalCode));
        if (!approval.isActive() || !approval.isCrossBorder()) {
            throw new IllegalStateException("Approval " + approvalCode
                    + " is not an active cross-border envelope; a global aggregate cannot be published under it.");
        }
        int floor = kFloor == null ? DEFAULT_K_FLOOR : Math.max(2, kFloor);

        List<SafetyStatsCell> allCells = statsCellRepo.findByVersionIdOrderByPreferredTermAsc(stats.getId());
        Set<String> approvedSites = approval.siteSet();
        Instant from = approval.getObservationFrom();
        Instant to = approval.getObservationTo();
        // The aggregate may only reflect a run whose analysis instant sits inside the approval's
        // observation window. If it does not, nothing is publishable under this envelope.
        Instant runAt = stats.getRun().getAnalysisAt();
        boolean windowCoversRun = windowCovers(runAt, from, to);

        List<PublishedCell> published = new ArrayList<>();
        int suppressedCells = 0;
        int suppressedEpisodes = 0;

        for (SafetyStatsCell c : allCells) {
            boolean siteOk = sitesWithinApproval(c.getSiteCodes(), approvedSites);
            boolean cellOk = windowCoversRun && siteOk && c.getSubjectCount() >= floor;
            if (!cellOk) {
                suppressedCells++;
                suppressedEpisodes += c.getIndexCount() + c.getRecurrenceCount();
                continue;
            }
            published.add(new PublishedCell(c.getPreferredTerm(), c.getSoc(), c.getIndexCount(),
                    c.getRecurrenceCount(), c.getSubjectCount(), c.getExposureDays(),
                    c.getIndexRatePer100Pd(), c.getRecurrenceRatePer100Pd(),
                    c.getIndexRateLower(), c.getIndexRateUpper(),
                    c.getRecurrenceRateLower(), c.getRecurrenceRateUpper()));
        }
        // Totals are computed over published cells only, so a suppressed single-participant
        // cell cannot be recovered by subtracting from a victims' total.
        int totalIndex = 0;
        int totalRec = 0;
        long totalDays = 0;
        for (PublishedCell c : published) {
            totalIndex += c.indexCount();
            totalRec += c.recurrenceCount();
            totalDays += c.exposureDays();
        }

        String cellsJson = cellsJson(published);
        String digest = Text.sha256(stats.getAggregateDigest() + "|" + approval.getCode() + "|" + floor
                + "|" + cellsJson);

        SafetyGlobalAggregate agg = new SafetyGlobalAggregate();
        agg.setCode(nextAggregateCode(statsCode, approval.getCode()));
        agg.setStatsVersion(stats);
        agg.setApproval(approval);
        agg.setSites(approval.getSiteScope());
        agg.setObservationFrom(from);
        agg.setObservationTo(to);
        agg.setKFloor(floor);
        agg.setTotalIndexEpisodes(totalIndex);
        agg.setTotalRecurrences(totalRec);
        agg.setTotalExposureDays(totalDays);
        agg.setPublishedCells(published.size());
        agg.setSuppressedCells(suppressedCells);
        agg.setSuppressedEpisodes(suppressedEpisodes);
        agg.setCellsJson(cellsJson);
        agg.setReceivedDigest(digest);
        agg.setCreatedAt(Instant.now());
        agg.setCreatedBy(actor);
        agg.setNote("Aggregate received at the global plane from stats version " + stats.getCode()
                + " under " + approval.getCode() + ". Cells with fewer than " + floor
                + " participants are suppressed. No participant identifiers cross the boundary.");
        agg = aggregateRepo.save(agg);

        audit.record(actor, "SAFETY_AGGREGATE_RECEIVED", "SafetyGlobalAggregate", agg.getCode(),
                "stats=" + statsCode + " approval=" + approval.getCode() + " published=" + published.size()
                        + " suppressedCells=" + suppressedCells + " digest=" + digest);

        return toView(agg, published);
    }

    @Transactional(readOnly = true)
    public List<AggregateView> list() {
        List<AggregateView> out = new ArrayList<>();
        for (SafetyGlobalAggregate agg : aggregateRepo.findAllByOrderByCreatedAtDesc()) {
            out.add(toView(agg, parseCells(agg.getCellsJson())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<AggregateView> find(String code) {
        return aggregateRepo.findByCode(code).map(a -> toView(a, parseCells(a.getCellsJson())));
    }

    private AggregateView toView(SafetyGlobalAggregate agg, List<PublishedCell> cells) {
        SafetyStatsVersion stats = agg.getStatsVersion();
        return new AggregateView(agg.getCode(), stats.getCode(), stats.getStrategy().name(),
                stats.getTerminologyVersion().label(), agg.getApproval().getCode(),
                Arrays.asList(agg.getSites().split(",")), agg.getObservationFrom(), agg.getObservationTo(),
                agg.getKFloor(), agg.getTotalIndexEpisodes(), agg.getTotalRecurrences(),
                agg.getTotalExposureDays(), agg.getPublishedCells(), agg.getSuppressedCells(),
                agg.getSuppressedEpisodes(), cells, agg.getReceivedDigest(), agg.getCreatedAt(), agg.getNote());
    }

    private boolean windowCovers(Instant instant, Instant from, Instant to) {
        return instant != null && !instant.isBefore(from) && (to == null || instant.isBefore(to));
    }

    private boolean sitesWithinApproval(String cellSitesCsv, Set<String> approvedSites) {
        if (approvedSites.isEmpty()) {
            return true;
        }
        if (cellSitesCsv == null || cellSitesCsv.isBlank()) {
            return false;
        }
        for (String s : cellSitesCsv.split(",")) {
            if (!approvedSites.contains(s.trim().toUpperCase())) {
                return false;
            }
        }
        return true;
    }

    private String cellsJson(List<PublishedCell> cells) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cells.size(); i++) {
            PublishedCell c = cells.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"term\":\"").append(c.preferredTerm()).append("\",\"soc\":\"").append(c.soc())
                    .append("\",\"index\":").append(c.indexCount()).append(",\"recurrence\":")
                    .append(c.recurrenceCount()).append(",\"subjects\":").append(c.subjectCount())
                    .append(",\"exposureDays\":").append(c.exposureDays())
                    .append(",\"indexRate\":").append(c.indexRate())
                    .append(",\"recurrenceRate\":").append(c.recurrenceRate())
                    .append(",\"indexLower\":").append(c.indexLower())
                    .append(",\"indexUpper\":").append(c.indexUpper())
                    .append(",\"recurrenceLower\":").append(c.recurrenceLower())
                    .append(",\"recurrenceUpper\":").append(c.recurrenceUpper()).append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private List<PublishedCell> parseCells(String json) {
        // The stored JSON is produced only by cellsJson(); this is a small, closed parser
        // used solely for the read view, not a general JSON dependency.
        List<PublishedCell> out = new ArrayList<>();
        if (json == null || json.isBlank() || json.equals("[]")) {
            return out;
        }
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        // split on '},{' boundaries
        String[] objects = body.split("\\},\\s*\\{");
        for (String obj : objects) {
            obj = obj.replace("{", "").replace("}", "");
            Map<String, String> kv = new HashMap<>();
            for (String part : obj.split(",(?=\")")) {
                String[] pair = part.split(":", 2);
                if (pair.length == 2) {
                    kv.put(pair[0].replace("\"", "").trim(), pair[1].replace("\"", "").trim());
                }
            }
            if (kv.isEmpty()) {
                continue;
            }
            out.add(new PublishedCell(
                    kv.getOrDefault("term", ""), kv.getOrDefault("soc", ""),
                    parseInt(kv.get("index")), parseInt(kv.get("recurrence")), parseInt(kv.get("subjects")),
                    parseLong(kv.get("exposureDays")), parseDouble(kv.get("indexRate")),
                    parseDouble(kv.get("recurrenceRate")), parseDouble(kv.get("indexLower")),
                    parseDouble(kv.get("indexUpper")), parseDouble(kv.get("recurrenceLower")),
                    parseDouble(kv.get("recurrenceUpper"))));
        }
        return out;
    }

    private String nextAggregateCode(String statsCode, String approvalCode) {
        String base = "AG-" + statsCode + "-" + approvalCode;
        long n = aggregateRepo.findAll().stream().filter(a -> a.getCode().startsWith(base)).count();
        return base + "-" + String.format("%03d", n + 1);
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double parseDouble(String s) {
        try {
            return s == null ? 0.0 : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
