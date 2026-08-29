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
 * Motion sightings turned into minutes per room.
 *
 * This is the one place in the app where stored data is not simply read back:
 * the sensors report moments and the dashboard draws durations, and something
 * has to decide what a moment is worth. That decision is arbitrary in the sense
 * that another reasonable person would pick different numbers, and load-bearing
 * in the sense that the room chart is meaningless if it is wrong — so it is
 * pinned here rather than left to be rediscovered from the code.
 *
 * The rule under test: a sighting speaks for the time until the next one
 * anywhere, capped at fifteen minutes, and going quiet means the pet's location
 * becomes unknown rather than unchanged.
 */
@RunWith(AndroidJUnit4.class)
public class RoomOccupancyTest {

    private static final long MINUTE = 60 * 1000L;
    private static final long HOUR = 60 * MINUTE;

    private Context context;
    private AppDatabase db;
    private PetDataRepository repository;
    private long petId;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("pets_db");
        db = AppDatabase.getInstance(context);
        repository = new PetDataRepository(context);

        petId = db.petDao().insert(new Pet(
                "Biscuit", "Cat", null, null, null, null, null, null, null, "owner-1"));
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

    private void seen(String room, long millisAgo) {
        repository.recordMotion(petId, todayAt(millisAgo), room, "pir", null);
    }

    private DashboardStats stats() {
        return repository.statsForToday(pet());
    }

    private int minutesIn(String room) {
        for (DashboardStats.RoomStay stay : stats().rooms) {
            if (stay.room.equals(room)) return stay.minutes;
        }
        return 0;
    }

    // ── Reporting, or not ───────────────────────────────────────────────────

    @Test
    public void noSightingEverMeansNoSensorsPaired() {
        DashboardStats stats = stats();

        // The dashboard shows "pair them in Settings" for this, which would be
        // wrong advice for a house whose sensors are simply quiet.
        assertFalse(stats.roomsReporting);
        assertTrue(stats.rooms.isEmpty());
        assertNull(stats.currentRoom);
    }

    @Test
    public void aSightingYesterdayMeansPairedButQuietToday() {
        repository.recordMotion(petId, DayRange.today().startMillis - HOUR,
                "Kitchen", "pir", null);

        DashboardStats stats = stats();

        assertTrue("the sensors are known to exist", stats.roomsReporting);
        assertTrue("and today is genuinely empty", stats.rooms.isEmpty());
    }

    // ── Minutes ─────────────────────────────────────────────────────────────

    @Test
    public void timeBetweenSightingsBelongsToTheEarlierRoom() {
        seen("Kitchen", 3 * HOUR);
        seen("Hall", 3 * HOUR - 20 * MINUTE);
        seen("Hall", 3 * HOUR - 25 * MINUTE);

        // Twenty minutes between the kitchen sighting and the next one anywhere,
        // but a single sighting is only believed for fifteen.
        assertEquals(15, minutesIn("Kitchen"));
    }

    @Test
    public void aRunOfSightingsInOneRoomAddsUp() {
        // A pet actually in a room retriggers: the Pi debounces to one event per
        // room per 30 s, so a real stay is many sightings, not one long gap.
        for (int i = 0; i < 10; i++) {
            seen("Kitchen", 2 * HOUR - i * 2 * MINUTE);
        }
        seen("Hall", 2 * HOUR - 20 * MINUTE);

        assertEquals(20, minutesIn("Kitchen"));
    }

    @Test
    public void aLongSilenceDoesNotCreditTheLastRoomWithIt() {
        seen("Kitchen", 8 * HOUR);
        seen("Hall", 1 * HOUR);

        // Eight hours of silence is a sleeping cat somewhere, not eight hours in
        // the kitchen. Fifteen minutes is all one sighting can claim.
        assertEquals(15, minutesIn("Kitchen"));
    }

    @Test
    public void theMostRecentSightingIsCreditedUpToNow() {
        seen("Hall", 5 * MINUTE);

        // Still counting: the day is not over.
        assertEquals(5, minutesIn("Hall"));
    }

    @Test
    public void aRoomIsNeverRoundedAwayToNothing() {
        seen("Study", 10 * 1000L);      // ten seconds ago

        // Rounding to zero would drop the room out of the chart entirely, which
        // reads as "never went in there" rather than "went in there just now".
        assertEquals(1, minutesIn("Study"));
    }

    @Test
    public void roomsAreOrderedByTimeSpent() {
        seen("Hall", 3 * HOUR);
        seen("Kitchen", 3 * HOUR - 2 * MINUTE);
        seen("Hall", 3 * HOUR - 12 * MINUTE);
        seen("Hall", 2 * HOUR);

        DashboardStats stats = stats();
        assertEquals("Hall", stats.rooms.get(0).room);
        assertTrue(stats.rooms.get(0).minutes >= stats.rooms.get(1).minutes);
    }

    @Test
    public void yesterdaysSightingsDoNotCountTowardToday() {
        repository.recordMotion(petId, DayRange.today().startMillis - HOUR,
                "Kitchen", "pir", null);
        seen("Hall", HOUR);

        assertEquals(0, minutesIn("Kitchen"));
        assertEquals(1, stats().rooms.size());
    }

    // ── Where the pet is now ────────────────────────────────────────────────

    @Test
    public void recentMotionMeansTheRoomIsKnown() {
        seen("Kitchen", 2 * MINUTE);

        DashboardStats stats = stats();
        assertEquals("Kitchen", stats.currentRoom);
        assertNotNull(stats.currentRoomSince);
        assertNull(stats.lastMotionTime);
    }

    @Test
    public void sinceIsWhenThisVisitStartedNotTheLastPulse() {
        // A pet in a room keeps setting the sensor off, so a real stay is a
        // steady trickle of sightings — every few minutes, not every twenty.
        for (int i = 0; i < 5; i++) {
            seen("Kitchen", 40 * MINUTE - i * 9 * MINUTE);
        }

        // "In the kitchen since 14:20" has to mean arrival. The last sighting is
        // just the most recent time something moved.
        DashboardStats stats = stats();
        assertEquals("Kitchen", stats.currentRoom);
        assertEquals(clockOf(40 * MINUTE), stats.currentRoomSince);
    }

    @Test
    public void aGapTooLongToBelieveStartsANewVisit() {
        seen("Kitchen", 90 * MINUTE);
        seen("Kitchen", 5 * MINUTE);

        // Same room, but nothing for an hour and a half in between. Claiming the
        // pet has been in the kitchen since 12:00 would assert a stay nothing
        // observed — it left and came back, as far as anything here can tell.
        assertEquals(clockOf(5 * MINUTE), stats().currentRoomSince);
    }

    @Test
    public void aVisitEndsWhenTheRoomChanges() {
        seen("Hall", 30 * MINUTE);
        seen("Kitchen", 10 * MINUTE);
        seen("Kitchen", 1 * MINUTE);

        assertEquals(clockOf(10 * MINUTE), stats().currentRoomSince);
    }

    @Test
    public void goingQuietMakesTheRoomUnknownRatherThanUnchanged() {
        seen("Kitchen", 2 * HOUR);

        DashboardStats stats = stats();

        // A PIR reports movement, never presence. Two hours of silence is not
        // evidence the pet is still in the kitchen, and the screen says so.
        assertNull(stats.currentRoom);
        assertNotNull("but when it was last seen is worth showing", stats.lastMotionTime);
        assertEquals(clockOf(2 * HOUR), stats.lastMotionTime);
    }

    // ── Storage rules ───────────────────────────────────────────────────────

    @Test
    public void aSightingWithNoRoomIsRejected() {
        assertEquals(-1, repository.recordMotion(petId, todayAt(HOUR), null, "pir", null));
        assertEquals(-1, repository.recordMotion(petId, todayAt(HOUR), "  ", "pir", null));

        assertFalse("a rejected sighting must not look like a paired sensor",
                stats().roomsReporting);
    }

    @Test
    public void theSameRemoteSightingIsStoredOnce() {
        long first = repository.recordMotion(petId, todayAt(HOUR), "Kitchen", "pir", 99L);
        long second = repository.recordMotion(petId, todayAt(HOUR), "Kitchen", "pir", 99L);

        assertTrue(first > 0);
        assertEquals("the Pi's own id is what makes re-polling safe", -1, second);
    }

    // ── The picture that came with the sighting ─────────────────────────────

    @Test
    public void theNewestPictureOfTheDayIsOffered() {
        repository.recordMotion(petId, todayAt(3 * HOUR), "Hall", "pir", 1L, "old.jpg");
        repository.recordMotion(petId, todayAt(2 * MINUTE), "Kitchen", "pir", 2L, "new.jpg");

        DashboardStats stats = stats();
        assertEquals("new.jpg", stats.lastSnapshotName);
        assertEquals("Kitchen", stats.lastSnapshotRoom);
        assertNotNull(stats.lastSnapshotTime);
    }

    @Test
    public void aLaterSightingWithNoPictureDoesNotHideTheOneWithA() {
        repository.recordMotion(petId, todayAt(30 * MINUTE), "Hall", "pir", 1L, "hall.jpg");
        repository.recordMotion(petId, todayAt(2 * MINUTE), "Kitchen", "pir", 2L, null);

        // Most sightings have no picture — the camera was busy, or absent — so
        // taking simply the newest would usually show nothing.
        assertEquals("hall.jpg", stats().lastSnapshotName);
    }

    @Test
    public void noPictureAnywhereLeavesTheFieldEmpty() {
        seen("Kitchen", 2 * MINUTE);

        assertNull(stats().lastSnapshotName);
    }

    @Test
    public void yesterdaysPictureIsNotTodaysNews() {
        repository.recordMotion(petId, DayRange.today().startMillis - HOUR,
                "Kitchen", "pir", 1L, "yesterday.jpg");

        assertNull(stats().lastSnapshotName);
    }

    // ── The history list ────────────────────────────────────────────────────

    @Test
    public void theHistoryReadsNewestFirst() {
        seen("Hall", 3 * HOUR);
        seen("Kitchen", 2 * HOUR);
        seen("Study", 1 * HOUR);

        java.util.List<PetDataRepository.Sighting> history =
                repository.sightingsForToday(pet());

        // The opposite order to the dwell maths, which needs oldest first —
        // getting the two confused silently inverts every duration.
        assertEquals(3, history.size());
        assertEquals("Study", history.get(0).room);
        assertEquals("Hall", history.get(2).room);
    }

    @Test
    public void aHistoryEntryKnowsWhetherItHasAPicture() {
        repository.recordMotion(petId, todayAt(HOUR), "Kitchen", "pir", 1L, "k.jpg");
        repository.recordMotion(petId, todayAt(2 * HOUR), "Hall", "pir", 2L, null);

        java.util.List<PetDataRepository.Sighting> history =
                repository.sightingsForToday(pet());

        assertTrue(history.get(0).hasPicture());
        assertEquals("k.jpg", history.get(0).snapshotName);
        assertFalse("most sightings have none, and the row says so",
                history.get(1).hasPicture());
    }

    @Test
    public void theHistoryIsTodayOnly() {
        repository.recordMotion(petId, DayRange.today().startMillis - HOUR,
                "Kitchen", "pir", 1L, "yesterday.jpg");
        seen("Hall", HOUR);

        assertEquals(1, repository.sightingsForToday(pet()).size());
    }

    @Test
    public void aPetWithNothingCollectedHasAnEmptyHistory() {
        assertTrue(repository.sightingsForToday(pet()).isEmpty());
        assertTrue(repository.sightingsForToday(null).isEmpty());
    }

    @Test
    public void deletingAPetTakesItsSightingsWithIt() {
        seen("Kitchen", HOUR);

        db.petDao().deleteById(petId, "owner-1");

        DayRange today = DayRange.today();
        assertTrue(db.roomDao().getInRange(petId, today.startMillis, today.endMillis)
                .isEmpty());
    }

    private String clockOf(long millisAgo) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date(todayAt(millisAgo)));
    }
}
