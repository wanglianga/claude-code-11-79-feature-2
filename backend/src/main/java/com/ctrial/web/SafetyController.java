package com.ctrial.web;

import com.ctrial.model.SafetyEnums.EpisodeStrategy;
import com.ctrial.service.safety.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Safety reconstruction API. Identity-bearing reads are domain-scoped (a
 * residence domain is required); the only cross-domain read is the k-anonymised
 * aggregate. Command endpoints that recompute episodes are gated to analyst /
 * admin roles in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/safety")
public class SafetyController {

    private final SafetyQueryService query;
    private final SafetyReconstructionService reconstruction;
    private final SafetyAggregateService aggregateService;
    private final SafetyAcceptanceService acceptance;

    public SafetyController(SafetyQueryService query, SafetyReconstructionService reconstruction,
                            SafetyAggregateService aggregateService, SafetyAcceptanceService acceptance) {
        this.query = query;
        this.reconstruction = reconstruction;
        this.aggregateService = aggregateService;
        this.acceptance = acceptance;
    }

    private String actor(Authentication auth) {
        return auth == null ? "anonymous" : auth.getName();
    }

    // ---------------- local (identity-bearing) reads ----------------

    @GetMapping("/domains")
    public List<SafetyQueryService.DomainSummary> domains() {
        return query.domains();
    }

    @GetMapping("/observations")
    public ResponseEntity<?> observations(@RequestParam String domain) {
        if (domain == null || domain.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "A residence domain is required; per-participant data is never returned undeclared."));
        }
        return ResponseEntity.ok(query.observations(domain));
    }

    @GetMapping("/exposures")
    public List<SafetyQueryService.ExposureView> exposures(@RequestParam String domain,
                                                           @RequestParam(defaultValue = "26") int day) {
        return query.exposures(domain, day);
    }

    @GetMapping("/terminologies")
    public List<SafetyQueryService.TerminologyView> terminologies() {
        return query.terminologies();
    }

    @GetMapping("/runs")
    public List<SafetyQueryService.RunSummary> runs() {
        return query.runs();
    }

    @GetMapping("/runs/{code}")
    public ResponseEntity<?> run(@PathVariable String code, @RequestParam(required = false) String domain) {
        try {
            return ResponseEntity.ok(query.runDetail(code, domain));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ---------------- commands ----------------

    public record ReconstructRequest(String strategy, String codingSystem, String version, Integer day) {
    }

    @PostMapping("/reconstruct")
    public ResponseEntity<?> reconstruct(@RequestBody(required = false) ReconstructRequest req,
                                         Authentication auth) {
        ReconstructRequest r = req == null ? new ReconstructRequest(null, null, null, null) : req;
        EpisodeStrategy strategy = parseStrategy(r.strategy());
        int day = r.day() == null ? 26 : r.day();
        SafetyReconstructionService.Reconstruction result;
        try {
            result = reconstruction.reconstruct(strategy, r.codingSystem(), r.version(), day, actor(auth));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", result.run());
        out.put("statsVersion", result.stats());
        out.put("episodeCount", result.episodes().size());
        out.put("candidateCount", result.run().getCandidateCount());
        out.put("edges", result.edges().size());
        out.put("warnings", result.warnings());
        return ResponseEntity.ok(out);
    }

    public record PublishRequest(String statsCode, String approvalCode, Integer kFloor) {
    }

    @PostMapping("/aggregates/publish")
    public ResponseEntity<?> publish(@RequestBody PublishRequest req, Authentication auth) {
        if (req == null || req.statsCode() == null || req.approvalCode() == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "statsCode and approvalCode are required to publish a global aggregate."));
        }
        try {
            return ResponseEntity.ok(aggregateService.publish(req.statsCode(), req.approvalCode(),
                    req.kFloor(), actor(auth)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/aggregates")
    public List<SafetyAggregateService.AggregateView> aggregates() {
        return aggregateService.list();
    }

    @GetMapping("/stats")
    public List<SafetyQueryService.StatsSummary> stats() {
        return query.statsVersions();
    }

    @GetMapping("/stats/{code}")
    public ResponseEntity<?> statsDetail(@PathVariable String code) {
        return query.statsDetail(code).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/aggregates/{code}")
    public ResponseEntity<?> aggregate(@PathVariable String code) {
        return aggregateService.find(code).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---------------- acceptance ----------------

    @GetMapping("/acceptance")
    public SafetyAcceptanceService.SafetyAcceptance acceptance(@RequestParam(defaultValue = "26") int day,
                                                               Authentication auth) {
        return acceptance.report(day, actor(auth));
    }

    private EpisodeStrategy parseStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return EpisodeStrategy.DETERMINISTIC;
        }
        try {
            return EpisodeStrategy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown strategy " + raw + "; use DETERMINISTIC or PROBABILISTIC.");
        }
    }
}
