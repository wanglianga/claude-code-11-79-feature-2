package com.ctrial.service.safety;

import com.ctrial.model.ExposureWindow;

import java.util.*;

/**
 * Pure rate arithmetic over reconstructed episodes and exposure windows.
 *
 * <p><b>Denominator</b>: observable treatment days — interval length minus days
 * with no safety observability, clamped at the analysis day, summed per
 * participant. Never nominal calendar duration, never days after last dose, and
 * never days a participant could not be observed.
 *
 * <p><b>Numerator</b>: index episodes are each participant+term series' first
 * episode; recurrences are every later episode in that series (which exists
 * because the earlier episode documented resolution).
 *
 * <p><b>Uncertainty</b>: linkage ambiguity means a term's episode count is not a
 * single number. Given the episode counts under the "merge every ambiguous
 * candidate" and "split every reversible near-threshold merge" recounts, the rate
 * interval is widened to contain both extremes on top of a Poisson sampling band.
 * The interval therefore propagates linkage uncertainty, not just sampling noise.
 */
public final class SafetyRateCalculator {

    private SafetyRateCalculator() {
    }

    public record EpisodePoint(String participantKey, String preferredTerm, String soc,
                               boolean recurrence, int ordinal, String siteCode) {
    }

    /** Per-term episode counts under a recount scenario: index and recurrence respectively. */
    public record CountPair(int index, int recurrence) {
        public static final CountPair ZERO = new CountPair(0, 0);
    }

    public record RateCell(
            String preferredTerm,
            String soc,
            int indexCount,
            int recurrenceCount,
            int subjectCount,
            long exposureDays,
            double indexRate,
            double recurrenceRate,
            double indexLower,
            double indexUpper,
            double recurrenceLower,
            double recurrenceUpper,
            int ambiguityDown,
            int ambiguityUp,
            String siteCodes
    ) {
    }

    public record Rates(
            long totalObservableDays,
            int participantCount,
            int totalIndex,
            int totalRecurrences,
            int ambiguousCandidates,
            List<RateCell> cells
    ) {
    }

    /** Observable days contributed by a window when the analysis day cuts it short. */
    public static long observableDaysThrough(ExposureWindow w, int analysisDay) {
        int lastObservable = Math.min(w.getEndDay(), analysisDay);
        int span = lastObservable - w.getStartDay() + 1;
        if (span <= 0) {
            return 0;
        }
        return Math.max(0, span - Math.max(0, w.getUnobservableDays()));
    }

    public static Map<String, Long> observableDaysByParticipant(List<ExposureWindow> windows, int analysisDay) {
        Map<String, Long> out = new TreeMap<>();
        for (ExposureWindow w : windows) {
            out.merge(w.getParticipantKey(), observableDaysThrough(w, analysisDay), Long::sum);
        }
        return out;
    }

    /**
     * @param pointEpisodes episode points from the BASE reconstruction
     * @param minByTerm     term -> lowest possible (index, recurrence) under merge-all
     * @param maxByTerm     term -> highest possible (index, recurrence) under split-reversible
     */
    public static Rates compute(List<EpisodePoint> pointEpisodes,
                                Map<String, CountPair> minByTerm,
                                Map<String, CountPair> maxByTerm,
                                Map<String, Long> daysByParticipant,
                                Map<String, String> sitesByParticipant,
                                int ambiguousCandidates) {
        long totalDays = daysByParticipant.values().stream().mapToLong(Long::longValue).sum();
        Set<String> participants = new TreeSet<>(daysByParticipant.keySet());
        for (EpisodePoint e : pointEpisodes) {
            participants.add(e.participantKey());
        }

        Map<String, List<EpisodePoint>> byTerm = new TreeMap<>();
        for (EpisodePoint e : pointEpisodes) {
            byTerm.computeIfAbsent(e.preferredTerm(), k -> new ArrayList<>()).add(e);
        }

        List<RateCell> cells = new ArrayList<>();
        int totalIndex = 0;
        int totalRec = 0;
        for (Map.Entry<String, List<EpisodePoint>> entry : byTerm.entrySet()) {
            String term = entry.getKey();
            List<EpisodePoint> eps = entry.getValue();
            String soc = eps.stream().map(EpisodePoint::soc).filter(Objects::nonNull)
                    .findFirst().orElse("");
            int index = (int) eps.stream().filter(e -> !e.recurrence()).count();
            int rec = (int) eps.stream().filter(EpisodePoint::recurrence).count();
            totalIndex += index;
            totalRec += rec;

            Set<String> subjects = new TreeSet<>();
            for (EpisodePoint e : eps) {
                subjects.add(e.participantKey());
            }
            long cellDays = subjects.stream().mapToLong(p -> daysByParticipant.getOrDefault(p, 0L)).sum();
            Set<String> sites = new TreeSet<>();
            for (String s : subjects) {
                String site = sitesByParticipant.get(s);
                if (site != null) {
                    sites.add(site);
                }
            }

            CountPair min = minByTerm.getOrDefault(term, new CountPair(index, rec));
            CountPair max = maxByTerm.getOrDefault(term, new CountPair(index, rec));
            int down = Math.max(0, index - min.index()) + Math.max(0, rec - min.recurrence());
            int up = Math.max(0, max.index() - index) + Math.max(0, max.recurrence() - rec);

            Interval idxIv = interval(index, Math.max(0, min.index()), Math.max(index, max.index()), cellDays);
            Interval recIv = interval(rec, Math.max(0, min.recurrence()), Math.max(rec, max.recurrence()), cellDays);

            cells.add(new RateCell(term, soc, index, rec, subjects.size(), cellDays,
                    round(rate(index, cellDays)), round(rate(rec, cellDays)),
                    round(idxIv.lower()), round(idxIv.upper()),
                    round(recIv.lower()), round(recIv.upper()),
                    down, up, String.join(",", sites)));
        }
        cells.sort(Comparator.comparing(RateCell::preferredTerm));
        return new Rates(totalDays, participants.size(), totalIndex, totalRec, ambiguousCandidates, cells);
    }

    public record Interval(double lower, double upper) {
    }

    /**
     * Poisson-style 95% band on the rate per 100 person-days, widened to cover the
     * linkage extremes {@code lowCount..highCount}.
     */
    public static Interval interval(int count, int lowCount, int highCount, long days) {
        if (days <= 0) {
            return new Interval(0.0, 0.0);
        }
        int low = Math.max(0, Math.min(lowCount, count));
        int high = Math.max(highCount, count);
        double scale = 100.0 / days;
        double lower = low == 0 ? 0.0 : poissonLower(low) * scale;
        double upper = poissonUpper(high) * scale;
        return new Interval(lower, upper);
    }

    /** Approximate one-sided lower bound of the 95% Poisson interval for k events. */
    private static double poissonLower(int k) {
        if (k <= 0) {
            return 0.0;
        }
        double z = 1.959964;
        double a = 1.0 - 1.0 / (9.0 * k) - z / (3.0 * Math.sqrt(k));
        return k * Math.pow(Math.max(a, 0.0), 3);
    }

    /** Approximate one-sided upper bound of the 95% Poisson interval for k events. */
    private static double poissonUpper(int k) {
        double z = 1.959964;
        double a = 1.0 - 1.0 / (9.0 * (k + 1.0)) + z / (3.0 * Math.sqrt(k + 1.0));
        return (k + 1.0) * Math.pow(Math.max(a, 0.0), 3);
    }

    public static double rate(int count, long days) {
        if (days <= 0) {
            return 0.0;
        }
        return (count * 100.0) / days;
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
