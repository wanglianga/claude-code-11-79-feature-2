package com.ctrial.service.safety;

import com.ctrial.model.SafetyEnums.EpisodeStrategy;
import com.ctrial.model.SafetyObservation;
import com.ctrial.service.safety.TerminologyMapper.ResolvedTerm;

import java.time.Instant;
import java.util.*;

/**
 * Pure reconstruction of episodes for <b>one residence domain</b>. Identity is
 * resolved only here: the participant key and local pseudonym never leave this
 * domain, and the global side only receives the counts this code contributes to
 * an approved aggregate.
 *
 * <p>Determinism: the same observations + edition + strategy always produce the
 * same clusters, the same episode codes (derived from stable observation keys)
 * and the same ordinal indices, so a stats version is reproducible.
 */
public final class LocalSafetyResolver {

    public record ActiveSet(List<SafetyObservation> active, Set<Long> supersededIds, Set<Long> voidedIds) {
    }

    /** Latest non-voided revision per observation group; earlier revisions are retained but inactive. */
    public ActiveSet activeRevisions(List<SafetyObservation> all) {
        Map<String, SafetyObservation> latest = new HashMap<>();
        Set<Long> superseded = new HashSet<>();
        Set<Long> voided = new HashSet<>();
        for (SafetyObservation o : all) {
            if (o.isVoided()) {
                voided.add(o.getId());
                continue;
            }
            SafetyObservation cur = latest.get(o.getObservationGroupKey());
            if (cur == null) {
                latest.put(o.getObservationGroupKey(), o);
            } else if (o.getRevision() > cur.getRevision()) {
                superseded.add(cur.getId());
                latest.put(o.getObservationGroupKey(), o);
            } else {
                superseded.add(o.getId());
            }
        }
        List<SafetyObservation> active = new ArrayList<>(latest.values());
        active.sort(Comparator.comparing(SafetyObservation::getOccurredStart)
                .thenComparing(SafetyObservation::getId));
        return new ActiveSet(active, superseded, voided);
    }

    public record EpisodeDraft(
            String episodeCode,
            String participantKey,
            String localPseudonym,
            String siteCode,
            String residencyDomain,
            String preferredTerm,
            String soc,
            int maxGrade,
            boolean serious,
            Instant start,
            Instant end,
            boolean resolutionEvidence,
            boolean treatmentInterruption,
            List<String> memberKeys,
            Set<String> sourceTypes,
            int occurrenceIndex,
            boolean recurrence
    ) {
    }

    public record DomainResult(
            String domain,
            List<EpisodeDraft> episodes,
            List<EpisodeLinker.Edge> edges,
            Map<String, ResolvedTerm> termByKey,
            List<String> unmapped,
            List<String> outOfValidity
    ) {
    }

    /**
     * Reconstruct episodes for one domain under one strategy and one recount scenario.
     */
    public DomainResult reconstruct(String domain,
                                    List<SafetyObservation> domainObservations,
                                    Map<Long, ResolvedTerm> termByObsId,
                                    EpisodeStrategy strategy,
                                    EpisodeLinker.Scenario scenario) {
        List<EpisodeLinker.ObsView> views = new ArrayList<>();
        Map<String, ResolvedTerm> termByKey = new LinkedHashMap<>();
        Map<String, SafetyObservation> obsByKey = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        List<String> outOfValidity = new ArrayList<>();

        for (SafetyObservation o : domainObservations) {
            ResolvedTerm t = termByObsId.get(o.getId());
            if (t == null || !t.usable()) {
                if (t != null && t.status() == com.ctrial.model.SafetyEnums.MappingStatus.OUT_OF_VALIDITY) {
                    outOfValidity.add(o.naturalKey() + ":" + o.getVerbatimTerm());
                } else {
                    unmapped.add(o.naturalKey() + ":" + o.getVerbatimTerm());
                }
                continue;
            }
            String key = o.naturalKey();
            termByKey.put(key, t);
            obsByKey.put(key, o);
        }

        // Resolution bounding: if a later report for the same participant+term documents an
        // explicit resolution end, any earlier open-ended report for that series is bounded by
        // it. Without this, an open-ended "still ongoing" report would overlap every later
        // occurrence forever and a genuine recurrence could never be separated.
        Map<String, Instant> resolutionBound = new HashMap<>();
        for (SafetyObservation o : domainObservations) {
            ResolvedTerm t = termByObsId.get(o.getId());
            if (t == null || !t.usable() || !o.isResolved() || o.getOccurredEnd() == null) {
                continue;
            }
            String seriesKey = o.getParticipantKey() + "|" + t.preferredTerm();
            Instant prior = resolutionBound.get(seriesKey);
            if (prior == null || o.getOccurredEnd().isAfter(prior)) {
                resolutionBound.put(seriesKey, o.getOccurredEnd());
            }
        }

        for (SafetyObservation o : domainObservations) {
            ResolvedTerm t = termByObsId.get(o.getId());
            if (t == null || !t.usable()) {
                continue;
            }
            String key = o.naturalKey();
            Instant end = o.getOccurredEnd();
            boolean resolved = o.isResolved();
            if (end == null) {
                Instant bound = resolutionBound.get(o.getParticipantKey() + "|" + t.preferredTerm());
                if (bound != null && bound.isAfter(o.getOccurredStart())) {
                    end = bound;
                    // A report bounded by a later documented resolution is itself evidence that
                    // the episode had resolved by that point; treat it as resolved so a later
                    // re-appearance is scored as a new episode, not a duplicate.
                    resolved = true;
                }
            }
            views.add(new EpisodeLinker.ObsView(key, o.getParticipantKey(), t.preferredTerm(), t.hlt(), t.soc(),
                    o.getOccurredStart(), end, resolved, o.isTreatmentInterrupted(),
                    o.getSeverityGrade(), o.isSerious(), o.getSourceType().name()));
        }

        EpisodeLinker.LinkResult link = EpisodeLinker.link(views, strategy, scenario);
        List<EpisodeDraft> drafts = new ArrayList<>();
        for (EpisodeLinker.Cluster cluster : link.clusters()) {
            drafts.add(toDraft(cluster, obsByKey, termByKey));
        }
        drafts = withOrdinals(drafts);
        return new DomainResult(domain, drafts, link.edges(), termByKey, unmapped, outOfValidity);
    }

    private EpisodeDraft toDraft(EpisodeLinker.Cluster cluster,
                                 Map<String, SafetyObservation> obsByKey,
                                 Map<String, ResolvedTerm> termByKey) {
        List<EpisodeLinker.ObsView> members = cluster.members();
        EpisodeLinker.ObsView first = members.get(0);

        // dominant preferred term: most frequent, tie broken by earliest member
        Map<String, Integer> ptCounts = new TreeMap<>();
        Map<String, String> ptToSoc = new HashMap<>();
        for (EpisodeLinker.ObsView m : members) {
            ptCounts.merge(m.preferredTerm(), 1, Integer::sum);
            ptToSoc.putIfAbsent(m.preferredTerm(), m.soc());
        }
        String pt = ptCounts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(e -> e.getKey(), Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElse(first.preferredTerm());

        int maxGrade = members.stream().mapToInt(EpisodeLinker.ObsView::severity).max().orElse(0);
        boolean serious = members.stream().anyMatch(EpisodeLinker.ObsView::serious);
        boolean resolved = members.stream().anyMatch(EpisodeLinker.ObsView::resolved);
        boolean interrupted = members.stream().anyMatch(EpisodeLinker.ObsView::treatmentInterrupted);
        Instant start = members.stream().map(EpisodeLinker.ObsView::start).min(Comparator.naturalOrder()).orElseThrow();
        boolean anyOngoing = members.stream().anyMatch(m -> m.end() == null);
        Instant end = anyOngoing ? null
                : members.stream().map(EpisodeLinker.ObsView::end).max(Comparator.naturalOrder()).orElse(null);

        List<String> keys = members.stream().map(EpisodeLinker.ObsView::key).sorted().toList();
        Set<String> sources = new TreeSet<>();
        for (String k : keys) {
            SafetyObservation o = obsByKey.get(k);
            if (o != null) {
                sources.add(o.getSourceType().name());
            }
        }
        SafetyObservation anchor = obsByKey.get(keys.get(0));
        String episodeCode = "EP-" + anchor.getSiteCode() + "-" + anchor.getParticipantKey() + "-"
                + pt.replaceAll("[^A-Za-z0-9]", "").toUpperCase() + "-"
                + keys.get(0).replaceAll("[^A-Za-z0-9]", "");

        return new EpisodeDraft(episodeCode, anchor.getParticipantKey(), anchor.getLocalPseudonym(),
                anchor.getSiteCode(), anchor.getResidencyDomain(), pt, ptToSoc.getOrDefault(pt, ""),
                maxGrade, serious, start, end, resolved, interrupted, keys, sources, 1, false);
    }

    /**
     * Two-pass rebuild so the ordinal and recurrence flag can be set without a
     * mutable record. Returns a new list; ordering is preserved by series then start.
     */
    private List<EpisodeDraft> withOrdinals(List<EpisodeDraft> drafts) {
        Map<String, List<Integer>> order = new HashMap<>();
        for (int i = 0; i < drafts.size(); i++) {
            order.computeIfAbsent(drafts.get(i).participantKey() + "|" + drafts.get(i).preferredTerm(),
                    k -> new ArrayList<>()).add(i);
        }
        EpisodeDraft[] out = drafts.toArray(new EpisodeDraft[0]);
        for (List<Integer> idxs : order.values()) {
            idxs.sort(Comparator.comparing(i -> drafts.get(i).start()));
            int ordinal = 0;
            boolean prevResolved = false;
            for (int i : idxs) {
                EpisodeDraft d = drafts.get(i);
                ordinal++;
                // A later episode is a recurrence only when the preceding one documented
                // resolution. Without resolution evidence it is a second occurrence of an
                // unresolved series, not a recurrence.
                boolean recurrence = ordinal > 1 && prevResolved;
                out[i] = new EpisodeDraft(d.episodeCode(), d.participantKey(), d.localPseudonym(), d.siteCode(),
                        d.residencyDomain(), d.preferredTerm(), d.soc(), d.maxGrade(), d.serious(), d.start(),
                        d.end(), d.resolutionEvidence(), d.treatmentInterruption(), d.memberKeys(),
                        d.sourceTypes(), ordinal, recurrence);
                prevResolved = d.resolutionEvidence();
            }
        }
        return Arrays.asList(out);
    }

    public static String normaliseTerm(String term) {
        return TerminologyMapper.normalise(term);
    }
}
