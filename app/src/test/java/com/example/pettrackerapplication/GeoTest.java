package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GeoTest {

    private static final double DEGREE_OF_LATITUDE_M = 111_194.9;

    @Test
    public void oneDegreeOfLatitudeIsAboutOneHundredAndElevenKilometres() {
        double metres = Geo.distanceMetres(0, 0, 1, 0);

        assertEquals(DEGREE_OF_LATITUDE_M, metres, 5.0);
    }

    @Test
    public void oneDegreeOfLatitudeIsTheSameLengthAtEveryLatitude() {
        double atEquator = Geo.distanceMetres(0, 0, 1, 0);
        double atSixty = Geo.distanceMetres(60, 0, 61, 0);

        assertEquals(atEquator, atSixty, 5.0);
    }

    @Test
    public void longitudeShrinksTowardsThePoles() {
        assertEquals(1.0, Geo.longitudeScale(0), 0.0001);
        assertEquals(0.5, Geo.longitudeScale(60), 0.001);
        assertEquals(0.848, Geo.longitudeScale(32), 0.001);
    }

    @Test
    public void distanceIsSymmetric() {
        double there = Geo.distanceMetres(32.00964, 35.76567, 32.01064, 35.76667);
        double back = Geo.distanceMetres(32.01064, 35.76667, 32.00964, 35.76567);

        assertEquals(there, back, 0.0001);
    }

    @Test
    public void aRouteIsTheSumOfItsLegs() {
        double step = 20.0 / DEGREE_OF_LATITUDE_M;
        double[] lats = new double[5];
        double[] lons = new double[5];
        for (int i = 0; i < 5; i++) {
            lats[i] = 32.0 + i * step;
            lons[i] = 35.0;
        }

        assertEquals(80.0, Geo.routeLengthMetres(lats, lons), 0.5);
    }

    @Test
    public void aStationaryTrackerDoesNotAccumulateDistance() {
        double metre = 1.0 / DEGREE_OF_LATITUDE_M;
        double[] lats = new double[200];
        double[] lons = new double[200];
        for (int i = 0; i < 200; i++) {
            lats[i] = 32.0 + (i % 2 == 0 ? metre : -metre);
            lons[i] = 35.0;
        }

        assertEquals(0.0, Geo.routeLengthMetres(lats, lons), 0.001);
    }

    @Test
    public void aSlowWalkSampledQuicklyStillCounts() {
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
    public void distanceIsNotMeasuredAcrossABreakInCoverage() {
        double metre = 1.0 / DEGREE_OF_LATITUDE_M;
        double[] lats = new double[8];
        double[] lons = new double[8];
        long[] stamps = new long[8];
        long now = 1_788_000_000_000L;
        for (int i = 0; i < 8; i++) {
            boolean second = i >= 4;
            lats[i] = 32.0 + (second ? 1000 : 0) * metre + (i % 4) * 4 * metre;
            lons[i] = 35.0;
            stamps[i] = now + i * 2_000L + (second ? 600_000L : 0);
        }

        double timed = Geo.routeLengthMetres(lats, lons, stamps);
        double untimed = Geo.routeLengthMetres(lats, lons);

        assertTrue("the unmeasured kilometre must not be counted: " + timed,
                timed < 100);
        assertTrue("without timestamps it is still counted: " + untimed,
                untimed > 900);
    }

    @Test
    public void aWalkWithNoBreaksMeasuresTheSameEitherWay() {
        double metre = 1.0 / DEGREE_OF_LATITUDE_M;
        double[] lats = new double[50];
        double[] lons = new double[50];
        long[] stamps = new long[50];
        long now = 1_788_000_000_000L;
        for (int i = 0; i < 50; i++) {
            lats[i] = 32.0 + i * 2 * metre;
            lons[i] = 35.0;
            stamps[i] = now + i * 2_000L;
        }

        assertEquals(Geo.routeLengthMetres(lats, lons),
                Geo.routeLengthMetres(lats, lons, stamps), 0.001);
    }

    @Test
    public void distanceFallsBackToUntimedWhenTheStampsDoNotMatch() {
        double[] lats = {32.0, 32.001};
        double[] lons = {35.0, 35.0};

        assertTrue(Geo.routeLengthMetres(lats, lons, new long[]{1L}) > 100);
    }

    @Test
    public void oneContinuousWalkIsAllOneRun() {
        assertEquals(0, Geo.mostRecentRunStart(steady(200, 2_000)));
    }

    @Test
    public void yesterdaysWalkIsNotPartOfTodays() {
        long[] stamps = new long[20];
        long now = 1_788_000_000_000L;
        for (int i = 0; i < 10; i++) stamps[i] = now + i * 2_000L;
        for (int i = 10; i < 20; i++) stamps[i] = now + 86_400_000L + i * 2_000L;

        assertEquals(10, Geo.mostRecentRunStart(stamps));
    }

    @Test
    public void losingTheSkyForAMinuteDoesNotEndTheWalk() {
        long[] stamps = new long[20];
        long now = 1_788_000_000_000L;
        for (int i = 0; i < 10; i++) stamps[i] = now + i * 2_000L;
        for (int i = 10; i < 20; i++) stamps[i] = now + 60_000L + i * 2_000L;

        assertEquals(0, Geo.mostRecentRunStart(stamps));
    }

    @Test
    public void onlyTheLastBreakEndsTheRun() {
        long[] stamps = new long[30];
        long now = 1_788_000_000_000L;
        for (int i = 0; i < 10; i++) stamps[i] = now + i * 2_000L;
        for (int i = 10; i < 20; i++) stamps[i] = now + 86_400_000L + i * 2_000L;
        for (int i = 20; i < 30; i++) stamps[i] = now + 172_800_000L + i * 2_000L;

        assertEquals(20, Geo.mostRecentRunStart(stamps));
    }

    @Test
    public void aRunStartIsSafeOnEmptyInput() {
        assertEquals(0, Geo.mostRecentRunStart(null));
        assertEquals(0, Geo.mostRecentRunStart(new long[0]));
        assertEquals(0, Geo.mostRecentRunStart(new long[]{1_788_000_000_000L}));
    }

    @Test
    public void aScatterFromThreeSatellitesIsNotDistanceWalked() {
        double metre = 1.0 / DEGREE_OF_LATITUDE_M;
        int n = 60;
        double[] lats = new double[n];
        double[] lons = new double[n];
        long[] stamps = new long[n];
        int[] sats = new int[n];
        long now = 1_788_000_000_000L;
        for (int i = 0; i < n; i++) {
            boolean degraded = i >= 25 && i < 35;
            lats[i] = 32.0 + i * 2 * metre;
            lons[i] = 35.0 + (degraded ? (i % 2 == 0 ? 40 : -40) * metre : 0);
            stamps[i] = now + i * 2_000L;
            sats[i] = degraded ? 3 : 12;
        }

        double rated = Geo.routeLengthMetres(lats, lons, stamps, sats);
        double unrated = Geo.routeLengthMetres(lats, lons, stamps);

        assertTrue("the scatter must not be counted as walking: " + rated,
                rated < 140);
        assertTrue("without the counts it is counted: " + unrated, unrated > 400);
    }

    @Test
    public void aWalkThroughGoodReceptionIsUnaffected() {
        double metre = 1.0 / DEGREE_OF_LATITUDE_M;
        double[] lats = new double[40];
        double[] lons = new double[40];
        long[] stamps = new long[40];
        int[] sats = new int[40];
        long now = 1_788_000_000_000L;
        for (int i = 0; i < 40; i++) {
            lats[i] = 32.0 + i * 2 * metre;
            lons[i] = 35.0;
            stamps[i] = now + i * 2_000L;
            sats[i] = 11;
        }

        assertEquals(Geo.routeLengthMetres(lats, lons, stamps),
                Geo.routeLengthMetres(lats, lons, stamps, sats), 0.001);
    }

    @Test
    public void sixSatellitesIsTrustedAndFiveIsNot() {
        assertTrue(Geo.isTrusted(6));
        assertTrue(Geo.isTrusted(12));
        assertFalse(Geo.isTrusted(5));
        assertFalse(Geo.isTrusted(3));
        assertFalse(Geo.isTrusted(0));
    }

    @Test
    public void aRunOfPoorFixesIsOneBreakNotTen() {
        long[] stamps = steady(60, 2_000);
        int[] sats = new int[60];
        for (int i = 0; i < 60; i++) sats[i] = (i >= 25 && i < 35) ? 3 : 12;

        assertEquals(1, Geo.countGaps(stamps, sats));
    }

    @Test
    public void twoSeparateStretchesOfPoorReceptionAreTwoBreaks() {
        long[] stamps = steady(60, 2_000);
        int[] sats = new int[60];
        for (int i = 0; i < 60; i++) {
            sats[i] = ((i >= 10 && i < 15) || (i >= 40 && i < 45)) ? 4 : 12;
        }

        assertEquals(2, Geo.countGaps(stamps, sats));
    }

    @Test
    public void withoutSatelliteCountsNothingChanges() {
        long[] stamps = steady(100, 2_000, 40);
        assertEquals(Geo.countGaps(stamps), Geo.countGaps(stamps, null));
    }

    private static long[] steady(int count, long stepMillis, int... gapAt) {
        long[] out = new long[count];
        long now = 1_788_000_000_000L;
        for (int i = 0; i < count; i++) {
            out[i] = now;
            long step = stepMillis;
            for (int g : gapAt) {
                if (g == i) step = stepMillis * 40;
            }
            now += step;
        }
        return out;
    }

    @Test
    public void aSteadyRunHasNoGaps() {
        assertEquals(0, Geo.countGaps(steady(100, 2000)));
    }

    @Test
    public void aPauseInReportingIsAGap() {
        assertEquals(1, Geo.countGaps(steady(100, 2000, 40)));
        assertEquals(3, Geo.countGaps(steady(100, 2000, 10, 40, 70)));
    }

    @Test
    public void theThresholdAdaptsToTheRecordingInterval() {
        long fast = Geo.gapThresholdMillis(steady(50, 2000));
        long slow = Geo.gapThresholdMillis(steady(50, 300_000));

        assertTrue("a 2 s run should not treat 30 s as normal", fast < 30_000);
        assertTrue("a 300 s run should not treat 30 s as a gap", slow > 30_000);
    }

    @Test
    public void aSlowlyRecordedRunIsNotAllGaps() {
        assertEquals(0, Geo.countGaps(steady(50, 300_000)));
    }

    @Test
    public void aSingleMissedFixIsNotAGap() {
        long[] stamps = steady(50, 2000);
        for (int i = 25; i < stamps.length; i++) {
            stamps[i] += 2000;
        }

        assertEquals(0, Geo.countGaps(stamps));
    }

    @Test
    public void tooFewFixesToJudgeIsNotAGap() {
        assertEquals(0, Geo.countGaps(new long[]{1_788_000_000_000L}));
        assertEquals(0, Geo.countGaps(new long[0]));
        assertEquals(0, Geo.countGaps(null));
    }

    @Test
    public void aSingleFixIsNoDistanceAndDoesNotCrash() {
        assertEquals(0.0, Geo.routeLengthMetres(new double[]{32}, new double[]{35}), 0.0);
        assertEquals(0.0, Geo.routeLengthMetres(new double[0], new double[0]), 0.0);
        assertEquals(0.0, Geo.routeLengthMetres(null, null), 0.0);
    }
}
