package com.example.pettrackerapplication;

/**
 * The two pieces of spherical geometry this app needs, and nothing else.
 *
 * Separate from any screen so it can be tested on a laptop: distance is the one
 * number on the track screen that is computed rather than displayed, and a
 * wrong one is not obvious by looking — a route can be drawn perfectly and
 * still be labelled with nonsense.
 */
public final class Geo {

    /** Mean Earth radius, metres. */
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /**
     * Below this, a step between fixes is treated as noise rather than travel.
     *
     * A stationary consumer GNSS receiver does not report a constant position;
     * it wanders by several metres as the satellite geometry shifts. Summing
     * every step would have a Pi sitting still on a table "walk" a kilometre
     * overnight, and the total distance is exactly the figure someone would
     * quote. Three metres is below a walking pace sample and above the jitter.
     */
    public static final double MIN_STEP_M = 3.0;

    private Geo() {
        // static helper, no instances
    }

    /**
     * Great-circle distance between two points, in metres.
     *
     * Haversine rather than the spherical law of cosines: the two agree to
     * within centimetres at these distances, but the law of cosines loses
     * precision badly for short distances in floating point, and short
     * distances are all this app ever measures.
     */
    public static double distanceMetres(double lat1, double lon1,
                                        double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * How far a sequence of fixes actually travelled, in metres.
     *
     * Steps shorter than {@link #MIN_STEP_M} are discarded rather than summed —
     * see the constant for why that matters more than it sounds.
     */
    public static double routeLengthMetres(double[] lats, double[] lons) {
        return routeLengthMetres(lats, lons, null);
    }

    /**
     * The same, but refusing to measure across a break in coverage.
     *
     * The drawing already declines to join two fixes separated by a gap,
     * because the straight line between them crosses ground nothing observed.
     * Without the timestamps the distance had no way to make the same
     * refusal, so it counted every one of those lines — and a screen that
     * draws a break while quietly adding its length to the total is stating
     * two different things about the same pair of points.
     *
     * The visible cost of that: a track screen reporting 782 m for eight days
     * of separate outings, most of it the jump from where the tracker sat on
     * one day to where it was switched on the next.
     *
     * @param timestampsMillis one per fix, or null to measure straight through
     */
    public static double routeLengthMetres(double[] lats, double[] lons,
                                           long[] timestampsMillis) {
        return routeLengthMetres(lats, lons, timestampsMillis, null);
    }

    /**
     * How many satellites a fix needs before its position is measured from.
     *
     * Four is the minimum for a three-dimensional fix at all; six leaves two
     * spare, which is where the position stops wandering. Chosen on that
     * reasoning rather than on results: on a route measured at 1351.6 m,
     * requiring eight fitted better (+0.3% against +2.7%), and a threshold
     * picked because it matches one walk is not evidence, it is fitting.
     *
     * A count, and not a speed limit, on purpose. Rejecting steps faster than
     * a person walks fits this data even better — but it assumes something
     * about the animal, and a tracker for animals should not quietly discard a
     * dog for running. The satellite count is the receiver's own statement
     * about how well it knows where it is, which is a fact about the
     * measurement instead of an assumption about the subject.
     */
    public static final int MIN_SATELLITES = 6;

    /** Whether a fix from this many satellites is worth measuring from. */
    public static boolean isTrusted(int satellites) {
        return satellites >= MIN_SATELLITES;
    }

    /**
     * The same again, also declining to measure from a fix the receiver was
     * unsure of.
     *
     * A break in coverage was defined by silence: fixes stopped arriving. A
     * receiver losing sky view in a built-up street does something else — it
     * keeps reporting, every two seconds, from three or four satellites, and
     * the positions scatter over tens of metres while the tracker walks in a
     * straight line. On a measured walk that added 11.7% of phantom distance,
     * and reported no gaps at all, because nothing had gone quiet.
     *
     * So confidence is treated the same way as silence. A stretch the receiver
     * could not place is not measured, and the drawing shows it broken.
     *
     * @param satellites one per fix, or null to measure from all of them
     */
    public static double routeLengthMetres(double[] lats, double[] lons,
                                           long[] timestampsMillis,
                                           int[] satellites) {
        if (lats == null || lons == null) return 0;
        int n = Math.min(lats.length, lons.length);

        boolean timed = timestampsMillis != null && timestampsMillis.length >= n;
        boolean rated = satellites != null && satellites.length >= n;
        long threshold = timed ? gapThresholdMillis(timestampsMillis) : 0;

        double total = 0;
        int from = 0;
        while (rated && from < n && !isTrusted(satellites[from])) from++;

        for (int to = from + 1; to < n; to++) {
            if (rated && !isTrusted(satellites[to])) {
                // Reported, but from too few satellites to say where. Skipped
                // without moving the anchor: the next trusted fix is measured
                // from the last trusted one, so a stretch of poor reception
                // costs its own scatter and not the ground either side of it.
                continue;
            }
            if (timed && timestampsMillis[to] - timestampsMillis[from] > threshold) {
                // Across a gap. Not measured, and the anchor moves so the
                // distance resumes from the far side rather than carrying the
                // unmeasured jump into the next step.
                from = to;
                continue;
            }
            double step = distanceMetres(lats[from], lons[from], lats[to], lons[to]);
            if (step < MIN_STEP_M) {
                // Not movement. Keep the earlier point as the anchor, so a slow
                // walk sampled quickly still accumulates instead of being
                // discarded one sub-threshold step at a time.
                continue;
            }
            total += step;
            from = to;
        }
        return total;
    }

    /**
     * Where the most recent outing starts, in a list of fixes that may hold many.
     *
     * A tracker is switched on, carried, and switched off; days later it is
     * switched on again. Those are separate journeys, and the only thing that
     * separates them in the data is a long silence. Reading a fixed number of
     * recent fixes and calling the result a run merges them: the track screen
     * summarised eight days as "783 fixes over 194 h 35" and drew the walk
     * joined to a week of readings taken at a desk.
     *
     * Deliberately much longer than {@link #gapThresholdMillis}. That answers
     * "did the receiver lose the sky for a moment", which happens inside one
     * walk and should not split it; this answers "is this the same outing at
     * all", and an hour of silence means it is not.
     *
     * @return the index of the first fix of the trailing run; 0 if they are all
     *         one run, which is the normal case for a single recorded walk
     */
    public static int mostRecentRunStart(long[] timestampsMillis) {
        if (timestampsMillis == null || timestampsMillis.length < 2) return 0;

        for (int i = timestampsMillis.length - 1; i > 0; i--) {
            if (timestampsMillis[i] - timestampsMillis[i - 1] > SESSION_GAP_MS) {
                return i;
            }
        }
        return 0;
    }

    /**
     * The silence that separates one outing from the next.
     *
     * An hour: long enough that a walk interrupted by a coffee stop, or by a
     * receiver that lost lock in a built-up street, stays one walk; short
     * enough that yesterday's route is not welded onto today's.
     */
    private static final long SESSION_GAP_MS = 60L * 60_000L;

    /** No run of fixes is treated as continuous across a gap shorter than this. */
    private static final long MIN_GAP_MS = 10_000;

    /**
     * How much longer than the usual step a gap has to be before it counts.
     *
     * Four is loose on purpose. Fixes do not arrive on a perfect metronome —
     * the receiver occasionally misses one, the poll loop drifts — and marking
     * every hiccup as a coverage gap would clutter the drawing with breaks that
     * mean nothing.
     */
    private static final long GAP_MULTIPLIER = 4;

    /**
     * The gap, in milliseconds, above which two fixes are not a straight line.
     *
     * <b>Why this exists.</b> A route is drawn by joining consecutive fixes. If
     * the receiver loses lock behind a building for a minute, the next fix is
     * hundreds of metres away, and the drawing joins them with a confident
     * straight line through whatever was in between — a line that crosses
     * streets, buildings and the rest of the route, and that no part of the
     * system ever measured. It is the one place the picture claims more than
     * the data supports, and the first walk produced several.
     *
     * Adaptive rather than a constant, because the recording interval is a
     * setting: a walk records every 2 s and a fixed installation every 300 s,
     * and a threshold right for one is nonsense for the other. Taking the
     * median of the actual intervals means the drawing calibrates itself to
     * however the run was recorded.
     *
     * The median, not the mean — the gaps being looked for would drag a mean
     * upwards and hide themselves.
     */
    public static long gapThresholdMillis(long[] timestampsMillis) {
        if (timestampsMillis == null || timestampsMillis.length < 3) {
            return MIN_GAP_MS;
        }

        long[] steps = new long[timestampsMillis.length - 1];
        for (int i = 1; i < timestampsMillis.length; i++) {
            steps[i - 1] = Math.max(0, timestampsMillis[i] - timestampsMillis[i - 1]);
        }
        java.util.Arrays.sort(steps);
        long median = steps[steps.length / 2];

        return Math.max(MIN_GAP_MS, median * GAP_MULTIPLIER);
    }

    /**
     * How many breaks in coverage a run of fixes contains.
     *
     * Worth reporting next to the route rather than only drawing: a track with
     * six gaps in it is a different piece of evidence from one with none, and
     * the difference is not obvious at a glance on a busy drawing.
     */
    public static int countGaps(long[] timestampsMillis) {
        return countGaps(timestampsMillis, null);
    }

    /**
     * The same, counting a stretch the receiver could not place as a break too.
     *
     * The legend says how many breaks the route contains, and a walk through a
     * built-up street where the fix degraded without ever stopping used to
     * report none. The reader was told the line was continuous while part of
     * it was scatter.
     *
     * A run of poor fixes is one break, not one per fix.
     */
    public static int countGaps(long[] timestampsMillis, int[] satellites) {
        if (timestampsMillis == null || timestampsMillis.length < 2) return 0;

        long threshold = gapThresholdMillis(timestampsMillis);
        boolean rated = satellites != null && satellites.length >= timestampsMillis.length;

        int gaps = 0;
        boolean inDegraded = false;
        for (int i = 1; i < timestampsMillis.length; i++) {
            if (rated && !isTrusted(satellites[i])) {
                if (!inDegraded) { gaps++; inDegraded = true; }
                continue;
            }
            inDegraded = false;
            if (timestampsMillis[i] - timestampsMillis[i - 1] > threshold) gaps++;
        }
        return gaps;
    }

    /**
     * Metres per degree of longitude at a given latitude.
     *
     * Degrees of latitude are the same length everywhere; degrees of longitude
     * shrink towards the poles. Drawing a route without this makes an
     * east-west walk look longer than a north-south one of the same distance —
     * at 32 degrees north, by about 15%.
     */
    public static double longitudeScale(double latitudeDegrees) {
        return Math.cos(Math.toRadians(latitudeDegrees));
    }
}
