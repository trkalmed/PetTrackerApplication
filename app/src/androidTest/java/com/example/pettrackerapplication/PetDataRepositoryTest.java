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
        context.deleteDatabase("pets_db");           // start from a known state
        db = AppDatabase.getInstance(context);
        repository = new PetDataRepository(context);

        petId = db.petDao().insert(new Pet(
                "Biscuit", "Dog", null, null, null, null, null, null, null, "owner-1"));
    }

    @After
    public void tearDown() {
        db.clearAllTables();
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
    public void gpsStaysEmptyUntilThatHardwareExists() {
        repository.recordFeeding(petId, todayAt(HOUR), 50, FeedingEvent.TYPE_EATEN, "f");

        DashboardStats stats = repository.statsForToday(pet());

        // Guards the honest empty state. A collar tag is the only thing that
        // could fill this: the Pi's own position is the kitchen floor, and the
        // phone's is the owner's pocket.
        assertNull(stats.lastGpsFixAgo);

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

    @Test
    public void statsForANullPetAreEmptyRatherThanACrash() {
        DashboardStats stats = repository.statsForToday(null);

        assertNull(stats.gramsEaten);
        assertEquals(0, stats.detectionsToday);
    }
}
