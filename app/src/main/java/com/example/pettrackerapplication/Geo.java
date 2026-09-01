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
