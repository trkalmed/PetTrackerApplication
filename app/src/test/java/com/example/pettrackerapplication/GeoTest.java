package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The route length shown on the track screen.
 *
 * Worth testing precisely because it is the one figure there that is computed
 * rather than displayed: a route can be drawn perfectly and still be labelled
 * with a nonsense distance, and nobody looking at the picture would know.
 */
public class GeoTest {

    /** One degree of latitude, in metres, for the radius Geo uses. */
    private static final double DEGREE_OF_LATITUDE_M = 111_194.9;

    @Test
    public void oneDegreeOfLatitudeIsAboutOneHundredAndElevenKilometres() {
        double metres = Geo.distanceMetres(0, 0, 1, 0);

        assertEquals(DEGREE_OF_LATITUDE_M, metres, 5.0);
    }

    @Test
    public void oneDegreeOfLatitudeIsTheSameLengthAtEveryLatitude() {
        // Unlike longitude. This is the property that makes the projection in
        // TrackView need a cos(lat) correction on longitude and none on this.
        double atEquator = Geo.distanceMetres(0, 0, 1, 0);
        double atSixty = Geo.distanceMetres(60, 0, 61, 0);

        assertEquals(atEquator, atSixty, 5.0);
    }

    @Test
    public void longitudeShrinksTowardsThePoles() {
        // At 60 degrees, a degree of longitude is half what it is at the
        // equator. Plotting lat against lon without this stretches an
        // east-west route.
        assertEquals(1.0, Geo.longitudeScale(0), 0.0001);
        assertEquals(0.5, Geo.longitudeScale(60), 0.001);
        assertEquals(0.848, Geo.longitudeScale(32), 0.001);   // the rig's latitude
    }

    @Test
    public void distanceIsSymmetric() {
        double there = Geo.distanceMetres(32.00964, 35.76567, 32.01064, 35.76667);
        double back = Geo.distanceMetres(32.01064, 35.76667, 32.00964, 35.76567);

        assertEquals(there, back, 0.0001);
    }

    @Test
    public void aRouteIsTheSumOfItsLegs() {
        // Five points, each 20 m further north than the last.
        double step = 20.0 / DEGREE_OF_LATITUDE_M;
        double[] lats = new double[5];
        double[] lons = new double[5];
        for (int i = 0; i < 5; i++) {
            lats[i] = 32.0 + i * step;
            lons[i] = 35.0;
        }

        assertEquals(80.0, Geo.routeLengthMetres(lats, lons), 0.5);
    }

    /**
     * The failure this guards against is quiet and embarrassing: a Pi sitting
     * still on a table, reporting a fix every second all night, and the app
     * announcing in the morning that the tracker walked several kilometres.
     */
    @Test
    public void aStationaryTrackerDoesNotAccumulateDistance() {
        // A metre of wander around one spot, sampled 200 times.
        double metre = 1.0 / DEGREE_OF_LATITUDE_M;
        double[] lats = new double[200];
        double[] lons = new double[200];
        for (int i = 0; i < 200; i++) {
            lats[i] = 32.0 + (i % 2 == 0 ? metre : -metre);
            lons[i] = 35.0;
        }

        assertEquals(0.0, Geo.routeLengthMetres(lats, lons), 0.001);
    }

    /**
     * A slow walk sampled quickly must still accumulate.
     *
     * This is the counterweight to the test above, and the reason the filter
     * keeps the last *accepted* point as its anchor rather than the previous
     * one. Comparing each point only to the one before it would discard every
     * step of a genuine walk sampled faster than three metres of progress.
     */
    @Test
    public void aSlowWalkSampledQuicklyStillCounts() {
        // 100 points, each 1 m further north — below the per-step threshold,
        // but 99 m of real travel.
        double metre = 1.0 / DEGREE_OF_LATITUDE_M;
        double[] lats = new double[100];
        double[] lons = new double[100];
        for (int i = 0; i < 100; i++) {
            lats[i] = 32.0 + i * metre;
            lons[i] = 35.0;
        }

        double metres = Geo.routeLengthMetres(lats, lons);
        assertTrue("a 99 m walk should not read as zero: " + metres, metres > 90);
        assertTrue("nor should it exceed the distance walked: " + metres, metres <= 99.5);
    }

    @Test
    public void aSingleFixIsNoDistanceAndDoesNotCrash() {
        assertEquals(0.0, Geo.routeLengthMetres(new double[]{32}, new double[]{35}), 0.0);
        assertEquals(0.0, Geo.routeLengthMetres(new double[0], new double[0]), 0.0);
        assertEquals(0.0, Geo.routeLengthMetres(null, null), 0.0);
    }
}
