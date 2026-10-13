package com.example.pettrackerapplication;

/** Distance and projection helpers for GPS routes. */
public final class Geo {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    public static final double MIN_STEP_M = 3.0;

    private Geo() {
    }

    public static double distanceMetres(double lat1, double lon1,
                                        double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static double routeLengthMetres(double[] lats, double[] lons) {
        return routeLengthMetres(lats, lons, null);
    }

    public static double routeLengthMetres(double[] lats, double[] lons,
                                           long[] timestampsMillis) {
        return routeLengthMetres(lats, lons, timestampsMillis, null);
    }

    public static final int MIN_SATELLITES = 6;

    public static boolean isTrusted(int satellites) {
        return satellites >= MIN_SATELLITES;
    }

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
                // Too few satellites: skip the fix without moving the anchor.
                continue;
            }
            if (timed && timestampsMillis[to] - timestampsMillis[from] > threshold) {
                // Across a coverage break: not measured, and the anchor moves past it.
                from = to;
                continue;
            }
            double step = distanceMetres(lats[from], lons[from], lats[to], lons[to]);
            if (step < MIN_STEP_M) {
                // Below the step threshold: keep the earlier anchor so a slow walk still adds up.
                continue;
            }
            total += step;
            from = to;
        }
        return total;
    }

    public static int mostRecentRunStart(long[] timestampsMillis) {
        if (timestampsMillis == null || timestampsMillis.length < 2) return 0;

        for (int i = timestampsMillis.length - 1; i > 0; i--) {
            if (timestampsMillis[i] - timestampsMillis[i - 1] > SESSION_GAP_MS) {
                return i;
            }
        }
        return 0;
    }

    private static final long SESSION_GAP_MS = 60L * 60_000L;

    private static final long MIN_GAP_MS = 10_000;

    private static final long GAP_MULTIPLIER = 4;

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

    public static int countGaps(long[] timestampsMillis) {
        return countGaps(timestampsMillis, null);
    }

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

    public static double longitudeScale(double latitudeDegrees) {
        return Math.cos(Math.toRadians(latitudeDegrees));
    }
}
