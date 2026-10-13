package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PiEventSyncTest {

    private static final long HOUR = 60 * 60 * 1000L;

    private Context context;
    private AppDatabase db;
    private PetDataRepository repository;
    private FakePi pi;
    private MemoryCursor cursor;
    private PiEventSync sync;
    private long petId;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AppDatabase.closeForTests();
        context.deleteDatabase("pets_db");
        db = AppDatabase.getInstance(context);
        repository = new PetDataRepository(context);

        petId = db.petDao().insert(new Pet(
                "Biscuit", "Cat", null, null, null, null, null, null, null, "owner-1"));

        pi = new FakePi();
        cursor = new MemoryCursor();
        sync = new PiEventSync(pi, repository, cursor);
    }

    @After
    public void tearDown() {
        db.clearAllTables();
    }

    private Pet pet() {
        return db.petDao().getPetById(petId, "owner-1");
    }

    private long todayAt(long millisAgo) {
        long candidate = System.currentTimeMillis() - millisAgo;
        DayRange today = DayRange.today();
        return today.contains(candidate) ? candidate : today.startMillis + 1;
    }

    @Test
    public void aMealFromThePiReachesTheDashboard() {
        pi.page(feeding(43, todayAt(2 * HOUR), 40, FeedingEvent.TYPE_EATEN));

        PiEventSync.Result result = sync.sync(petId);

        assertEquals(1, result.stored);
        assertEquals(Integer.valueOf(40), repository.statsForToday(pet()).gramsEaten);
    }

    @Test
    public void motionFromThePiBecomesARoom() {
        pi.page(motion(44, todayAt(HOUR), "Kitchen"));

        assertEquals(1, sync.sync(petId).stored);

        DashboardStats stats = repository.statsForToday(pet());
        assertTrue(stats.roomsReporting);
        assertEquals("Kitchen", stats.rooms.get(0).room);
    }

    @Test
    public void aSnapshotNameTravelsWithItsSighting() {
        try {
            pi.page(motion(50, todayAt(2 * 60 * 1000L), "Kitchen")
                    .put("snapshot", "motion-20260828-152031-812-kitchen.jpg"));
        } catch (JSONException e) {
            throw new AssertionError(e);
        }

        sync.sync(petId);

        assertEquals("motion-20260828-152031-812-kitchen.jpg",
                repository.statsForToday(pet()).lastSnapshotName);
    }

    @Test
    public void aSightingWithNoSnapshotIsStillASighting() {
        pi.page(motion(51, todayAt(60 * 1000L), "Hall"));

        assertEquals(1, sync.sync(petId).stored);
        assertNull(repository.statsForToday(pet()).lastSnapshotName);
    }

    @Test
    public void aFixFromThePiReachesTheDashboard() {
        pi.page(location(52, todayAt(HOUR), 32.00964, 35.76567, 12));

        assertEquals(1, sync.sync(petId).stored);

        DashboardStats stats = repository.statsForToday(pet());
        assertEquals(32.00964, stats.trackerLatitude, 0.000005);
        assertEquals(12, stats.trackerSatellites);
    }

    @Test
    public void aFixWithNoCoordinatesIsSkipped() {
        pi.page(event(53, PiEvent.KIND_LOCATION, todayAt(HOUR)));

        PiEventSync.Result result = sync.sync(petId);

        assertEquals(0, result.stored);
        assertEquals(1, result.ignored);
        assertNull(repository.statsForToday(pet()).trackerFixTime);
    }

    @Test
    public void rawWeightsAreNotStored() {
        pi.page(weight(45, todayAt(HOUR), 118.4));

        PiEventSync.Result result = sync.sync(petId);

        assertEquals(0, result.stored);
        assertEquals(1, result.ignored);
        assertNull("a weight is not a meal", repository.statsForToday(pet()).gramsEaten);
    }

    @Test
    public void anUnknownKindIsSkippedRatherThanFatal() {
        pi.page(event(46, "humidity", todayAt(HOUR)),
                motion(47, todayAt(HOUR), "Hall"));

        PiEventSync.Result result = sync.sync(petId);

        assertEquals(1, result.stored);
        assertEquals(1, result.ignored);
        assertEquals(47, cursor.value());
    }

    @Test
    public void aFeedingEventMissingItsGramsIsSkipped() {
        pi.page(event(48, PiEvent.KIND_FEEDING, todayAt(HOUR)));

        PiEventSync.Result result = sync.sync(petId);

        assertEquals(0, result.stored);
        assertEquals(48, cursor.value());
    }

    @Test
    public void theCursorStartsAtZeroAndAdvances() {
        pi.page(motion(10, todayAt(HOUR), "Hall"));

        sync.sync(petId);

        assertEquals("since=0 on the first ever poll", 0, (long) pi.requestedCursors.get(0));
        assertEquals(10, cursor.value());
    }

    @Test
    public void theNextSyncAsksForWhatComesAfter() {
        cursor.save(42);
        pi.latestCursor = 100;
        pi.page(motion(43, todayAt(HOUR), "Hall"));

        sync.sync(petId);

        assertEquals(42, (long) pi.requestedCursors.get(0));
    }

    @Test
    public void everyPageIsCollectedWhileMoreAreWaiting() {
        pi.page(motion(1, todayAt(3 * HOUR), "Hall"));
        pi.page(motion(2, todayAt(2 * HOUR), "Kitchen"));
        pi.page(motion(3, todayAt(HOUR), "Hall"));

        PiEventSync.Result result = sync.sync(petId);

        assertEquals(3, result.stored);
        assertEquals(java.util.Arrays.asList(0L, 1L, 2L), pi.requestedCursors);
        assertFalse(result.moreWaiting);
    }

    @Test
    public void oneSyncDoesNotRunForever() {
        pi.endlessPages = true;

        PiEventSync.Result result = sync.sync(petId);

        assertEquals(20, pi.requestedCursors.size());
        assertTrue("the caller has to know to come back", result.moreWaiting);
    }

    @Test
    public void aPiWhoseLogWasRebuiltIsNoticed() {
        cursor.save(500);
        pi.latestCursor = 12;
        pi.page(motion(1, todayAt(HOUR), "Kitchen"));

        PiEventSync.Result result = sync.sync(petId);

        assertEquals("start over rather than wait for an id that will never come",
                0, (long) pi.requestedCursors.get(0));
        assertEquals(1, result.stored);
    }

    @Test
    public void replayingTheSameEventsStoresThemOnce() {
        pi.page(feeding(43, todayAt(2 * HOUR), 40, FeedingEvent.TYPE_EATEN),
                motion(44, todayAt(HOUR), "Kitchen"));
        sync.sync(petId);

        cursor.save(0);
        pi.rewind();
        PiEventSync.Result again = sync.sync(petId);

        assertEquals(0, again.stored);
        assertEquals(2, again.ignored);
        assertEquals("40 g eaten once, not twice",
                Integer.valueOf(40), repository.statsForToday(pet()).gramsEaten);
        assertEquals(1, repository.statsForToday(pet()).rooms.size());
    }

    @Test
    public void anUnreachablePiIsReportedRatherThanThrown() {
        pi.failHealth = true;

        PiEventSync.Result result = sync.sync(petId);

        assertFalse(result.reachable);
        assertNotNull(result.error);
        assertEquals("nothing was read, so nothing moved", 0, cursor.value());
    }

    @Test
    public void aSyncCutOffKeepsWhatItAlreadyStored() {
        pi.page(motion(1, todayAt(2 * HOUR), "Hall"));
        pi.page(motion(2, todayAt(HOUR), "Kitchen"));
        pi.failEventsAfterPages = 1;

        PiEventSync.Result result = sync.sync(petId);

        assertTrue(result.reachable);
        assertEquals(1, result.stored);
        assertEquals(1, cursor.value());
        assertNotNull(result.error);
        assertTrue(result.moreWaiting);
    }

    @Test
    public void withNoPetSelectedNothingIsCollected() {
        pi.page(motion(1, todayAt(HOUR), "Hall"));

        PiEventSync.Result result = sync.sync(-1);

        assertFalse(result.reachable);
        assertTrue(pi.requestedCursors.isEmpty());
        assertEquals(0, cursor.value());
    }

    private static JSONObject event(long id, String kind, long timestamp) {
        try {
            return new JSONObject()
                    .put("id", id)
                    .put("kind", kind)
                    .put("timestamp_ms", timestamp)
                    .put("source", "test");
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static JSONObject feeding(long id, long timestamp, int grams, String type) {
        try {
            return event(id, PiEvent.KIND_FEEDING, timestamp)
                    .put("grams", grams).put("type", type);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static JSONObject motion(long id, long timestamp, String room) {
        try {
            return event(id, PiEvent.KIND_MOTION, timestamp).put("room", room);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static JSONObject location(long id, long timestamp, double lat,
                                       double lon, int satellites) {
        try {
            return event(id, PiEvent.KIND_LOCATION, timestamp)
                    .put("lat", lat).put("lon", lon).put("satellites", satellites);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static JSONObject weight(long id, long timestamp, double grams) {
        try {
            return event(id, PiEvent.KIND_WEIGHT, timestamp).put("grams", grams);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static final class FakePi implements PiEventSource {

        private final List<List<JSONObject>> pages = new ArrayList<>();
        private final List<List<JSONObject>> served = new ArrayList<>();

        final List<Long> requestedCursors = new ArrayList<>();

        long latestCursor = Long.MAX_VALUE;
        boolean failHealth = false;
        boolean endlessPages = false;
        int failEventsAfterPages = -1;

        void page(JSONObject... events) {
            pages.add(java.util.Arrays.asList(events));
        }

        void rewind() {
            pages.addAll(0, served);
            served.clear();
            requestedCursors.clear();
        }

        @Override
        public PiHealth health() throws IOException {
            if (failHealth) throw new IOException("unreachable");
            try {
                return PiHealth.fromJson(
                        new JSONObject().put("ok", true).put("latest_cursor", latestCursor));
            } catch (JSONException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public PiEventPage events(long since) throws IOException {
            if (failEventsAfterPages >= 0
                    && requestedCursors.size() >= failEventsAfterPages) {
                requestedCursors.add(since);
                throw new IOException("connection reset");
            }
            requestedCursors.add(since);

            if (endlessPages) {
                return PiEventPage.of(
                        java.util.Collections.singletonList(PiEvent.fromJson(
                                motion(requestedCursors.size(),
                                        System.currentTimeMillis(), "Hall"))),
                        requestedCursors.size(), true);
            }

            if (pages.isEmpty()) return PiEventPage.of(new ArrayList<>(), since, false);

            List<JSONObject> page = pages.remove(0);
            served.add(page);

            List<PiEvent> parsed = new ArrayList<>();
            JSONArray array = new JSONArray();
            for (JSONObject json : page) {
                array.put(json);
                PiEvent event = PiEvent.fromJson(json);
                if (event != null) parsed.add(event);
            }
            long next = parsed.isEmpty() ? since : parsed.get(parsed.size() - 1).id;
            return PiEventPage.of(parsed, next, !pages.isEmpty());
        }
    }

    private static final class MemoryCursor implements PiEventSync.Cursor {
        private long value;

        @Override
        public long value() {
            return value;
        }

        @Override
        public void save(long cursor) {
            this.value = cursor;
        }
    }
}
