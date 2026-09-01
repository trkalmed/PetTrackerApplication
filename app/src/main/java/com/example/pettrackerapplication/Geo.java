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
        if (lats == null || lons == null) return 0;
        int n = Math.min(lats.length, lons.length);

        double total = 0;
        int from = 0;
        for (int to = 1; to < n; to++) {
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
        if (timestampsMillis == null || timestampsMillis.length < 2) return 0;

        long threshold = gapThresholdMillis(timestampsMillis);
        int gaps = 0;
        for (int i = 1; i < timestampsMillis.length; i++) {
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
