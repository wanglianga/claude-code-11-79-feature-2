package com.ctrial.service.safety;

import com.ctrial.model.SafetyEnums.EdgeKind;
import com.ctrial.model.SafetyEnums.EpisodeStrategy;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Pure, side-effect-free episode linker over one residence domain's observations.
 *
 * <p>Two strategies, as the spec requires:
 * <ul>
 *   <li><b>DETERMINISTIC</b> — cluster only when two observations share the
 *       participant, share the canonical preferred term and explicitly overlap
 *       in time. Easy to audit, but a delayed supplementary report that arrives
 *       with a non-overlapping time stamp for the same symptom is split into two
 *       episodes.</li>
 *   <li><b>PROBABILISTIC</b> — score every same-participant pair on term
 *       similarity (exact PT / same HLT / same SOC), time proximity, documented
 *       resolution (which argues for a <em>new</em> episode, not a duplicate) and
 *       treatment interruption (which supports one continuous episode). Pairs at
 *       or above the merge threshold merge; pairs inside the ambiguity band are
 *       retained as candidates and are <b>never</b> force-merged.</li>
 * </ul>
 *
 * <p>Participant identity is a hard gate: observations from different
 * participants never produce an edge, even with an identical term and identical
 * time.
 */
public final class EpisodeLinker {
    public static final double MERGE_THRESHOLD = 0.75;
    public static final double CANDIDATE_THRESHOLD = 0.50;
    /** Merges scored within this margin above the threshold are "reversible" for the upper bound. */
    public static final double REVERSIBLE_MARGIN = 0.10;

    private EpisodeLinker() {
    }

    /** A single observation projected to what the linker needs. Local keys only. */
    public record ObsView(
            String key,
            String participantKey,
            String preferredTerm,
            String hlt,
            String soc,
            Instant start,
            Instant end,
            boolean resolved,
            boolean treatmentInterrupted,
            int severity,
            boolean serious,
            String sourceType
    ) {
        Instant effectiveEnd() {
            return end == null ? Instant.MAX : end;
        }
    }

    public record Pair(String left, String right, double score, List<String> reasons) {
    }

    public record Edge(String leftKey, String rightKey, EdgeKind kind, double score, boolean applied,
                       List<String> reasons) {
    }

    /** A connected component of observations — becomes one episode. */
    public record Cluster(List<ObsView> members) {
    }

    public record LinkResult(List<Cluster> clusters, List<Edge> edges) {
    }

    /** A recount scenario used to bound linkage uncertainty. */
    public enum Scenario {
        /** Merge only scores at/above the merge threshold (the point decision). */
        BASE,
        /** Also merge ambiguous candidates — the fewest possible episodes. */
        MERGE_ALL,
        /** Split reversible near-threshold merges — the most possible episodes. */
        SPLIT_REVERSIBLE
    }

    public static LinkResult link(List<ObsView> observations, EpisodeStrategy strategy, Scenario scenario) {
        List<Pair> pairs = strategy == EpisodeStrategy.DETERMINISTIC
                ? deterministicPairs(observations)
                : probabilisticPairs(observations);

        double threshold = switch (scenario) {
            case BASE, MERGE_ALL -> strategy == EpisodeStrategy.DETERMINISTIC ? 1.0 : MERGE_THRESHOLD;
            case SPLIT_REVERSIBLE -> strategy == EpisodeStrategy.DETERMINISTIC
                    ? 1.0 : MERGE_THRESHOLD + REVERSIBLE_MARGIN;
        };
        if (scenario == Scenario.MERGE_ALL && strategy == EpisodeStrategy.PROBABILISTIC) {
            threshold = CANDIDATE_THRESHOLD;
        }

        UnionFind uf = new UnionFind();
        for (ObsView o : observations) {
            uf.add(o.key());
        }
        for (Pair p : pairs) {
            if (p.score() >= threshold) {
                uf.union(p.left(), p.right());
            }
        }
        Map<String, ObsView> byKey = new LinkedHashMap<>();
        for (ObsView o : observations) {
            byKey.put(o.key(), o);
        }
        Map<String, List<ObsView>> groups = new TreeMap<>();
        for (ObsView o : observations) {
            groups.computeIfAbsent(uf.find(o.key()), k -> new ArrayList<>()).add(o);
        }
        List<Cluster> clusters = new ArrayList<>();
        for (List<ObsView> g : groups.values()) {
            g.sort(Comparator.comparing(ObsView::start));
            clusters.add(new Cluster(g));
        }

        // Edges are recorded only for the point decision (BASE); the recount scenarios
        // are analytical and do not create new persisted edges.
        List<Edge> edges = new ArrayList<>();
        if (scenario == Scenario.BASE) {
            EdgeKind appliedKind = strategy == EpisodeStrategy.DETERMINISTIC
                    ? EdgeKind.DETERMINISTIC_OVERLAP : EdgeKind.PROBABILISTIC_MERGE;
            for (Pair p : pairs) {
                boolean applied = p.score() >= threshold;
                boolean candidate = strategy == EpisodeStrategy.PROBABILISTIC
                        && !applied && p.score() >= CANDIDATE_THRESHOLD;
                if (applied) {
                    edges.add(new Edge(p.left(), p.right(), appliedKind, p.score(), true, p.reasons()));
                } else if (candidate) {
                    edges.add(new Edge(p.left(), p.right(), EdgeKind.CANDIDATE_AMBIGUOUS, p.score(), false,
                            appendReason(p.reasons(), "ambiguity_band")));
                }
            }
        }
        return new LinkResult(clusters, edges);
    }

    // ------------------------------------------------------------------
    private static List<Pair> deterministicPairs(List<ObsView> obs) {
        List<Pair> out = new ArrayList<>();
        for (int i = 0; i < obs.size(); i++) {
            for (int j = i + 1; j < obs.size(); j++) {
                ObsView a = obs.get(i);
                ObsView b = obs.get(j);
                if (!a.participantKey().equals(b.participantKey())) {
                    continue;
                }
                if (!a.preferredTerm().equalsIgnoreCase(b.preferredTerm())) {
                    continue;
                }
                if (overlaps(a, b)) {
                    out.add(new Pair(a.key(), b.key(), 1.0,
                            List.of("same_participant", "term_exact", "time_overlap")));
                }
            }
        }
        return out;
    }

    private static List<Pair> probabilisticPairs(List<ObsView> obs) {
        List<Pair> out = new ArrayList<>();
        for (int i = 0; i < obs.size(); i++) {
            for (int j = i + 1; j < obs.size(); j++) {
                ObsView a = obs.get(i);
                ObsView b = obs.get(j);
                if (!a.participantKey().equals(b.participantKey())) {
                    // identity is a hard gate: never link different people, even identical terms/times
                    continue;
                }
                out.add(score(a, b));
            }
        }
        return out;
    }

    private static Pair score(ObsView a, ObsView b) {
        List<String> reasons = new ArrayList<>();
        reasons.add("same_participant");

        double termSim;
        if (a.preferredTerm().equalsIgnoreCase(b.preferredTerm())) {
            termSim = 1.0;
            reasons.add("term_exact");
        } else if (notBlank(a.hlt()) && a.hlt().equalsIgnoreCase(b.hlt())) {
            termSim = 0.55;
            reasons.add("term_same_hlt");
        } else if (notBlank(a.soc()) && a.soc().equalsIgnoreCase(b.soc())) {
            termSim = 0.30;
            reasons.add("term_same_soc");
        } else {
            termSim = 0.0;
            reasons.add("term_disjoint");
        }

        long gapDays = gapDays(a, b);
        double timeSim;
        if (overlaps(a, b)) {
            timeSim = 1.0;
            reasons.add("time_overlap");
        } else if (gapDays <= 2) {
            timeSim = 0.60;
            reasons.add("time_gap_" + gapDays + "d");
        } else if (gapDays <= 7) {
            timeSim = 0.35;
            reasons.add("time_gap_" + gapDays + "d");
        } else if (gapDays <= 14) {
            timeSim = 0.20;
            reasons.add("time_gap_" + gapDays + "d");
        } else {
            timeSim = 0.05;
            reasons.add("time_gap_" + gapDays + "d");
        }

        double boost = (a.treatmentInterrupted() || b.treatmentInterrupted()) ? 0.05 : 0.0;
        if (boost > 0) {
            reasons.add("treatment_interruption_support");
        }

        // If the earlier episode documents resolution and the later starts afresh, that is
        // evidence of a NEW episode (a recurrence), not a duplicate of the same one.
        double penalty = 0.0;
        ObsView earlier = a.start().isBefore(b.start()) || a.start().equals(b.start()) ? a : b;
        ObsView later = earlier == a ? b : a;
        if (!overlaps(a, b) && earlier.resolved() && earlier.end() != null
                && !later.start().isBefore(earlier.end())) {
            penalty = 0.50;
            reasons.add("resolution_separates_episodes");
        } else if (earlier.resolved() || later.resolved()) {
            reasons.add("resolution_evidence_present");
        }

        double score = 0.60 * termSim + 0.35 * timeSim + boost - penalty;
        score = Math.max(0.0, Math.min(1.0, score));
        return new Pair(a.key(), b.key(), score, reasons);
    }

    private static boolean overlaps(ObsView a, ObsView b) {
        return a.start().isBefore(b.effectiveEnd()) && b.start().isBefore(a.effectiveEnd());
    }

    /** Whole-day gap between the earlier-ending and later-starting observation (0 if overlapping). */
    private static long gapDays(ObsView a, ObsView b) {
        ObsView earlier = a.effectiveEnd().isBefore(b.effectiveEnd()) ? a : b;
        ObsView later = earlier == a ? b : a;
        if (earlier.end() == null) {
            return 0; // ongoing; treated as continuously present
        }
        Instant from = earlier.end();
        Instant to = later.start();
        if (!to.isAfter(from)) {
            return 0;
        }
        return Math.max(0, Duration.between(from, to).toDays());
    }

    private static List<String> appendReason(List<String> reasons, String extra) {
        List<String> out = new ArrayList<>(reasons);
        out.add(extra);
        return out;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Minimal union-find over string keys. */
    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        void add(String x) {
            parent.putIfAbsent(x, x);
        }

        String find(String x) {
            String p = parent.getOrDefault(x, x);
            if (!p.equals(x)) {
                p = find(p);
                parent.put(x, p);
            }
            return p;
        }

        void union(String a, String b) {
            String ra = find(a);
            String rb = find(b);
            if (!ra.equals(rb)) {
                parent.put(ra, rb);
            }
        }
    }
}
