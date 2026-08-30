package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The seam the Raspberry Pi work will plug into, exercised end to end against a
 * real database: readings go in through the ingestion methods, and the dashboard
 * reads them back out as {@link DashboardStats}.
 *
 * These run against the app's actual database file rather than an in-memory one,
 * because that is what the Pi integration will be writing to.
 */
@RunWith(AndroidJUnit4.class)
public class PetDataRepositoryTest {

    private Context context;
    private AppDatabase db;
    private PetDataRepository repository;
    private long petId;

    private static final long HOUR = 60 * 60 * 1000L;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // Close the cached instance before unlinking the file, or the
        // singleton hands the next test a connection to a database that
        // no longer exists — see AppDatabase.closeForTests().
        AppDatabase.closeForTests();
        context.deleteDatabase("pets_db");           // start from a known state
        db = AppDatabase.getInstance(context);
        repository = new PetDataRepository(context);

        petId = db.petDao().insert(new Pet(
                "Biscuit", "Dog", null, null, null, null, null, null, null, "owner-1"));
    }

    @After
    public void tearDown() {
        db.clearAllTables();
        // The camera flag is remembered across syncs, so it would otherwise leak
        // from one test into the next.
        Prefs.get(context).edit()
                .remove(Prefs.KEY_PI_CAMERA_OK)
                .remove(Prefs.KEY_PI_HEALTH_AT)
                .apply();
    }

    private Pet pet() {
        return db.petDao().getPetById(petId, "owner-1");
    }

    /** A timestamp inside today, offset back from now but never past midnight. */
    private long todayAt(long millisAgo) {
        long candidate = System.currentTimeMillis() - millisAgo;
        DayRange today = DayRange.today();
        return today.contains(candidate) ? candidate : today.startMillis + 1;
    }

    // ── Feeder ──────────────────────────────────────────────────────────────

    @Test
    public void noReadingsMeansNoFeederPaired() {
        DashboardStats stats = repository.statsForToday(pet());

        // null, not 0: "no feeder" and "ate nothing" are different statements and
        // the dashboard renders them differently.
        assertNull(stats.gramsEaten);
        assertTrue(stats.meals.isEmpty());
    }

    @Test
    public void aReadingYesterdayMeansPairedButZeroToday() {
        long yesterday = DayRange.today().startMillis - HOUR;
        repository.recordFeeding(petId, yesterday, 120, FeedingEvent.TYPE_EATEN, "feeder-01");

        DashboardStats stats = repository.statsForToday(pet());

        assertNotNull("the feeder is known, so today is a real zero", stats.gramsEaten);
        assertEquals(0, (int) stats.gramsEaten);
        assertTrue(stats.meals.isEmpty());
    }

    @Test
    public void todaysEatenGramsAreSummed() {
        repository.recordFeeding(petId, todayAt(3 * HOUR), 40, FeedingEvent.TYPE_EATEN, "f");
        repository.recordFeeding(petId, todayAt(2 * HOUR), 55, FeedingEvent.TYPE_EATEN, "f");

        DashboardStats stats = repository.statsForToday(pet());

        assertEquals(95, (int) stats.gramsEaten);
        assertEquals(2, stats.meals.size());
    }

    @Test
    public void dispensedDoesNotCountAsEaten() {
        // The feeder putting food in the bowl is not the pet eating it. Both are
        // stored; only EATEN drives the dashboard.
        repository.recordFeeding(petId, todayAt(HOUR), 200, FeedingEvent.TYPE_DISPENSED, "f");

        DashboardStats stats = repository.statsForToday(pet());

        assertNotNull(stats.gramsEaten);
        assertEquals(0, (int) stats.gramsEaten);
        assertTrue(stats.meals.isEmpty());
    }

    @Test
    public void mealsAreOldestFirst() {
        repository.recordFeeding(petId, todayAt(1 * HOUR), 10, FeedingEvent.TYPE_EATEN, "f");
        repository.recordFeeding(petId, todayAt(5 * HOUR), 20, FeedingEvent.TYPE_EATEN, "f");

        DashboardStats stats = repository.statsForToday(pet());

        assertEquals(2, stats.meals.size());
        assertEquals("the earlier meal should come first", 20, stats.meals.get(0).grams);
    }

    @Test
    public void negativeGramsAreRejectedRatherThanStored() {
        // A negative reading is a bug at the source; storing it would poison
        // every total downstream.
        long id = repository.recordFeeding(petId, todayAt(HOUR), -5, FeedingEvent.TYPE_EATEN, "f");

        assertEquals(-1, id);
        assertNull(repository.statsForToday(pet()).gramsEaten);
    }

    @Test
    public void unknownReadingTypeIsRejected() {
        long id = repository.recordFeeding(petId, todayAt(HOUR), 30, "NIBBLED", "f");

        assertEquals(-1, id);
    }

    // ── Detections ──────────────────────────────────────────────────────────

    @Test
    public void todaysDetectionsAreCounted() {
        repository.recordDetection(petId, todayAt(2 * HOUR), "cat", 0.9f,
                DetectionEvent.SOURCE_PI);
        repository.recordDetection(petId, todayAt(HOUR), "cat", 0.8f,
                DetectionEvent.SOURCE_PI);

        DashboardStats stats = repository.statsForToday(pet());

        assertEquals(2, stats.detectionsToday);
        assertNotNull(stats.lastDetectionTime);
    }

    @Test
    public void yesterdaysDetectionsDoNotCountTowardToday() {
        repository.recordDetection(petId, DayRange.today().startMillis - HOUR, "dog", 0.7f,
                DetectionEvent.SOURCE_PI);

        DashboardStats stats = repository.statsForToday(pet());

        assertEquals(0, stats.detectionsToday);
        assertNull(stats.lastDetectionTime);
    }

    @Test
    public void thePiSayingItHasACameraIsWhatMakesItLive() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.KEY_PI_CAMERA_OK, true)
                .putLong(Prefs.KEY_PI_HEALTH_AT, System.currentTimeMillis())
                .apply();

        // The honest source. The old rule asked whether the phone's own detector
        // had seen an animal lately, which is only true while somebody is
        // watching the Live screen — so a working Pi camera read as "offline"
        // for all the time nobody was looking at it.
        assertTrue(repository.statsForToday(pet()).cameraOnline);
    }

    @Test
    public void aPiThatHasNotBeenHeardFromLatelyIsNotEvidence() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.KEY_PI_CAMERA_OK, true)
                .putLong(Prefs.KEY_PI_HEALTH_AT, System.currentTimeMillis() - HOUR)
                .apply();

        // An hour-old answer describes an hour ago. The poller runs every 15 s
        // while a screen is open, so this means the app is not talking to the Pi.
        assertFalse(repository.statsForToday(pet()).cameraOnline);
    }

    @Test
    public void aPiWithNoCameraIsNotLiveEitherWay() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.KEY_PI_CAMERA_OK, false)
                .putLong(Prefs.KEY_PI_HEALTH_AT, System.currentTimeMillis())
                .apply();

        assertFalse(repository.statsForToday(pet()).cameraOnline);
    }

    @Test
    public void aRecentSightingMeansTheCameraIsLive() {
        repository.recordDetection(petId, System.currentTimeMillis() - 60_000L, "cat", 0.9f,
                DetectionEvent.SOURCE_PI);

        assertTrue(repository.statsForToday(pet()).cameraOnline);
    }

    @Test
    public void aStaleSightingDoesNotMeanTheCameraIsLive() {
        repository.recordDetection(petId, System.currentTimeMillis() - (2 * HOUR), "cat", 0.9f,
                DetectionEvent.SOURCE_PI);

        assertFalse(repository.statsForToday(pet()).cameraOnline);
    }

    // ── Things with no hardware yet ─────────────────────────────────────────

    @Test
    public void aFeedingReadingFillsInNothingItDoesNotKnow() {
        repository.recordFeeding(petId, todayAt(HOUR), 50, FeedingEvent.TYPE_EATEN, "f");

        DashboardStats stats = repository.statsForToday(pet());

        // Feeding readings say nothing about where the tracker is. The fix has
        // its own table and its own test below.
        assertNull(stats.trackerFixTime);

        // Feeder readings say nothing about rooms. See RoomOccupancyTest for
        // what motion sightings do.
        assertTrue(stats.rooms.isEmpty());
        assertNull(stats.currentRoom);
    }

    @Test
    public void deletingAPetTakesItsReadingsWithIt() {
        repository.recordFeeding(petId, todayAt(HOUR), 50, FeedingEvent.TYPE_EATEN, "f");
        repository.recordDetection(petId, todayAt(HOUR), "cat", 0.9f, DetectionEvent.SOURCE_PI);

        db.petDao().deleteById(petId, "owner-1");

        // The foreign keys cascade, so readings cannot outlive their pet.
        DayRange today = DayRange.today();
        assertEquals(0, db.feedingDao().getTotalGramsInRange(
                petId, FeedingEvent.TYPE_EATEN, today.startMillis, today.endMillis));
        assertEquals(0, db.detectionDao().countInRange(
                petId, today.startMillis, today.endMillis));
    }

    // ── The tracker's own position ──────────────────────────────────────────
    //
    // Not the pet's, anywhere in here. The Pi is a box in the hall, and the
    // naming is the guardrail — see TrackerFix.

    @Test
    public void aFixIsStoredAndReadBackOntoTheDashboard() {
        assertTrue(repository.recordTrackerFix(
                petId, todayAt(HOUR), 32.00964, 35.76567, 12, "gps", 421L) > 0);

        DashboardStats stats = repository.statsForToday(pet());

        assertNotNull(stats.trackerFixTime);
        assertEquals(32.00964, stats.trackerLatitude, 0.000005);
        assertEquals(35.76567, stats.trackerLongitude, 0.000005);
        assertEquals(12, stats.trackerSatellites);
    }

    @Test
    public void theNewestFixWins() {
        // Absolute times inside today rather than offsets from now: todayAt()
        // clamps to the start of the day, so two calls in the small hours can
        // land on the same millisecond and make "newest" ambiguous.
        long midnight = DayRange.today().startMillis;
        repository.recordTrackerFix(petId, midnight + HOUR, 32.0, 35.0, 4, "gps", 1L);
        repository.recordTrackerFix(petId, midnight + 2 * HOUR, 31.5, 35.5, 11, "gps", 2L);

        DashboardStats stats = repository.statsForToday(pet());

        assertEquals(31.5, stats.trackerLatitude, 0.000005);
        assertEquals(11, stats.trackerSatellites);
    }

    /**
     * A fix from before today still shows, unlike every other figure here.
     *
     * A GNSS module indoors can go a long time between fixes. "Located
     * yesterday" is true and useful; a blank would read as "no GPS at all",
     * which is a different and wrong statement about the hardware.
     */
    @Test
    public void aFixFromYesterdayIsStillReported() {
        repository.recordTrackerFix(petId, DayRange.today().startMillis - 6 * HOUR,
                32.00964, 35.76567, 9, "gps", 400L);

        DashboardStats stats = repository.statsForToday(pet());

        assertNotNull(stats.trackerFixTime);
        // Dated, so a stale fix cannot pass for a fresh one at a glance.
        assertTrue(stats.trackerFixTime, stats.trackerFixTime.length() > "19:42".length());
    }

    /**
     * 0,0 is a real point in the Gulf of Guinea and the classic shape of a fix
     * built from empty NMEA fields. Storing it would put a working-looking
     * tracker two thousand kilometres out to sea.
     */
    @Test
    public void nullIslandIsRejected() {
        assertEquals(-1, repository.recordTrackerFix(
                petId, todayAt(HOUR), 0.0, 0.0, 0, "gps", 500L));

        assertNull(repository.statsForToday(pet()).trackerFixTime);
    }

    @Test
    public void impossibleAndUnparseableCoordinatesAreRejected() {
        assertEquals(-1, repository.recordTrackerFix(
                petId, todayAt(HOUR), 91.0, 35.0, 8, "gps", 501L));
        assertEquals(-1, repository.recordTrackerFix(
                petId, todayAt(HOUR), 32.0, 181.0, 8, "gps", 502L));
        assertEquals(-1, repository.recordTrackerFix(
                petId, todayAt(HOUR), Double.NaN, 35.0, 8, "gps", 503L));

        assertNull(repository.statsForToday(pet()).trackerFixTime);
    }

    /**
     * Re-polling the Pi after a cursor reset must not store the same fix twice.
     * Same guarantee as every other Pi-fed table, and it comes from the unique
     * index over (petId, source, remoteId).
     */
    @Test
    public void redeliveringAFixIsANoOp() {
        assertTrue(repository.recordTrackerFix(
                petId, todayAt(HOUR), 32.00964, 35.76567, 12, "gps", 421L) > 0);
        assertEquals(-1, repository.recordTrackerFix(
                petId, todayAt(HOUR), 32.00964, 35.76567, 12, "gps", 421L));
    }

    @Test
    public void fixesDoNotOutliveTheirPet() {
        repository.recordTrackerFix(petId, todayAt(HOUR), 32.0, 35.0, 7, "gps", 600L);

        db.petDao().deleteById(petId, "owner-1");

        assertFalse(db.trackerFixDao().hasAnyFix(petId));
    }

    @Test
    public void statsForANullPetAreEmptyRatherThanACrash() {
        DashboardStats stats = repository.statsForToday(null);

        assertNull(stats.gramsEaten);
        assertEquals(0, stats.detectionsToday);
    }
}
