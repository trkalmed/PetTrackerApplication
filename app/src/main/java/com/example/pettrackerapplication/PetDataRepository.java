package com.example.pettrackerapplication;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where sensor readings land and where the dashboard reads them back.
 *
 * This is the seam the Raspberry Pi work plugs into. Whatever transport ends up
 * carrying the data — HTTP poll, a socket, a local broker — it only has to call
 * {@link #recordFeeding} and {@link #recordDetection}. Nothing above this class
 * knows or cares where a reading came from, so the transport can be written and
 * swapped without touching any screen.
 *
 * Every method here blocks on the database. Call from a background thread.
 */
public final class PetDataRepository {

    private static final String TAG = "PetDataRepository";

    /** How stale a sighting can be before the camera stops counting as live. */
    private static final long CAMERA_LIVENESS_WINDOW_MS = 5 * 60 * 1000L;

    /**
     * The longest stretch a single motion sighting is allowed to speak for.
     *
     * A PIR reports movement, never presence: it fires while the cat crosses the
     * kitchen and says nothing at all while the cat sleeps in it. So a sighting
     * has to stand for some amount of time, and the only question is how much.
     *
     * Too short and a nap in one room reads as no time anywhere. Too long and
     * the last sighting before bedtime credits the kitchen with the whole night.
     * Fifteen minutes is well past the Pi's 30 s debounce — a pet actually in a
     * room retriggers far more often than that — and short enough that going
     * quiet reads as "we no longer know", which is the truth.
     */
    private static final long MAX_DWELL_MS = 15 * 60 * 1000L;

    private final AppDatabase db;

    public PetDataRepository(Context context) {
        this.db = AppDatabase.getInstance(context.getApplicationContext());
    }

    // ── Ingestion — called by the Pi/feeder integration when it exists ───────

    /**
     * Records one feeder or load-cell reading.
     *
     * @param grams whole grams; negatives are dropped rather than stored, since
     *              a negative meal is a bug at the source and poisons every
     *              total downstream.
     * @param type  {@link FeedingEvent#TYPE_EATEN} or
     *              {@link FeedingEvent#TYPE_DISPENSED}
     * @return the new row id, or -1 if the reading was rejected
     */
    public long recordFeeding(long petId, long timestampMillis, int grams,
                              String type, String source) {
        return recordFeeding(petId, timestampMillis, grams, type, source, null);
    }

    /**
     * Records one reading that came from the Pi, tagged with the id it had there.
     *
     * That id is what makes polling safe to repeat. The app can be made to
     * re-read events it has already seen — the Pi's log is pruned and its
     * cursor rewinds, the phone is re-paired, a sync is interrupted halfway —
     * and a meal counted twice is worse than a meal counted late. The unique
     * index over (petId, source, remoteId) turns the second copy into a no-op.
     *
     * @return the new row id, -1 if the reading was rejected or already stored
     */
    public long recordFeeding(long petId, long timestampMillis, int grams,
                              String type, String source, Long remoteId) {
        if (grams < 0) {
            Log.w(TAG, "Ignoring feeding reading with negative grams: " + grams);
            return -1;
        }
        if (!FeedingEvent.TYPE_EATEN.equals(type)
                && !FeedingEvent.TYPE_DISPENSED.equals(type)) {
            Log.w(TAG, "Ignoring feeding reading with unknown type: " + type);
            return -1;
        }
        FeedingEvent event =
                new FeedingEvent(petId, timestampMillis, grams, type, source);
        event.setRemoteId(remoteId);
        return db.feedingDao().insert(event);
    }

    /**
     * Records one motion sighting: a sensor in {@code room} fired at that moment.
     *
     * Stored as reported. Whether three sightings in the kitchen mean the pet
     * spent twenty minutes there is a question for {@link #statsForToday}, where
     * the answer can be changed without rewriting history.
     *
     * @return the new row id, -1 if the sighting was rejected or already stored
     */
    public long recordMotion(long petId, long timestampMillis, String room,
                             String source, Long remoteId) {
        if (room == null || room.trim().isEmpty()) {
            // A sighting with no room says only "something moved somewhere",
            // which no screen can render and no total can use.
            Log.w(TAG, "Ignoring motion reading with no room");
            return -1;
        }
        RoomEvent event =
                new RoomEvent(petId, timestampMillis, room.trim(), source);
        event.setRemoteId(remoteId);
        return db.roomDao().insert(event);
    }

    /** Convenience for the common case: the load cell saw food leave the bowl. */
    public long recordMealEaten(long petId, int grams, String source) {
        return recordFeeding(petId, System.currentTimeMillis(), grams,
                FeedingEvent.TYPE_EATEN, source);
    }

    public long recordDetection(long petId, long timestampMillis, String label,
                                float confidence, String source) {
        return db.detectionDao().insert(
                new DetectionEvent(petId, timestampMillis, label, confidence, source));
    }

    // ── Read side ───────────────────────────────────────────────────────────

    /**
     * Builds the dashboard's view of one pet's day.
     *
     * Fields whose hardware does not exist yet stay null/empty on purpose — the
     * screens render setup prompts for those, which is the honest thing to show
     * and the reason the fields are boxed types.
     */
    public DashboardStats statsForToday(Pet pet) {
        DashboardStats stats = new DashboardStats();
        if (pet == null) return stats;

        long petId = pet.getId();
        DayRange today = DayRange.today();

        readFeeding(stats, petId, today);
        readDetections(stats, petId, today);
        readRooms(stats, petId, today);

        // GPS still has no source: no collar tag, no location provider, and the
        // Pi's own position is not the pet's. Leaving it empty keeps that setup
        // prompt honest instead of inventing a number.

        return stats;
    }

    private void readFeeding(DashboardStats stats, long petId, DayRange today) {
        FeedingDao dao = db.feedingDao();

        // No reading ever means no feeder is paired, which is a different
        // statement from "paired, and nothing eaten yet today".
        if (!dao.hasAnyReading(petId)) {
            stats.gramsEaten = null;
            return;
        }

        stats.gramsEaten = dao.getTotalGramsInRange(
                petId, FeedingEvent.TYPE_EATEN, today.startMillis, today.endMillis);

        List<FeedingEvent> events = dao.getInRange(
                petId, FeedingEvent.TYPE_EATEN, today.startMillis, today.endMillis);
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (FeedingEvent e : events) {
            stats.meals.add(new DashboardStats.Meal(
                    clock.format(new Date(e.getTimestampMillis())), e.getGrams()));
        }
    }

    /**
     * Turns today's motion sightings into minutes per room, and a current room.
     *
     * The sensors report moments; the dashboard draws durations. Bridging that
     * is a judgement, and it lives here rather than in the Pi or the database so
     * it can be changed later without the stored history being wrong.
     *
     * The rule: each sighting speaks for the time until the next one, anywhere,
     * capped at {@link #MAX_DWELL_MS}. Consecutive sightings in the same room
     * add up on their own, and a long silence stops the clock instead of
     * crediting a room the pet may have left hours ago.
     */
    private void readRooms(DashboardStats stats, long petId, DayRange today) {
        RoomDao dao = db.roomDao();

        // Never a sighting means no sensors are paired, which is a different
        // statement from "paired, and the pet has not moved today".
        stats.roomsReporting = dao.hasAnyReading(petId);
        if (!stats.roomsReporting) return;

        List<RoomEvent> events =
                dao.getInRange(petId, today.startMillis, today.endMillis);
        if (events.isEmpty()) return;

        long now = System.currentTimeMillis();
        // A day that has not finished yet only counts up to now; a day being
        // read back later counts to its own end.
        long windowEnd = Math.min(now, today.endMillis);

        Map<String, Long> dwellMillis = new LinkedHashMap<>();
        for (int i = 0; i < events.size(); i++) {
            RoomEvent event = events.get(i);
            long until = (i + 1 < events.size())
                    ? events.get(i + 1).getTimestampMillis()
                    : windowEnd;
            long dwell = Math.min(Math.max(0, until - event.getTimestampMillis()),
                    MAX_DWELL_MS);
            dwellMillis.merge(event.getRoom(), dwell, Long::sum);
        }

        List<Map.Entry<String, Long>> ordered = new ArrayList<>(dwellMillis.entrySet());
        // Longest first: the bar and its legend read as a ranking.
        Collections.sort(ordered, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> entry : ordered) {
            if (entry.getValue() <= 0) continue;
            // A room the pet was demonstrably in must not round away to nothing
            // and vanish from the chart; a minute is the smallest honest claim.
            int minutes = Math.max(1, Math.round(entry.getValue() / 60_000f));
            stats.rooms.add(new DashboardStats.RoomStay(entry.getKey(), minutes));
        }

        RoomEvent latest = events.get(events.size() - 1);
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
        if (now - latest.getTimestampMillis() <= MAX_DWELL_MS) {
            stats.currentRoom = latest.getRoom();
            stats.currentRoomSince = clock.format(new Date(currentRunStart(events)));
        } else {
            // Silence is not a location. Say when the trail went cold rather
            // than claiming the pet is still where it was last seen.
            stats.lastMotionTime = clock.format(new Date(latest.getTimestampMillis()));
        }
    }

    /**
     * When the pet arrived in the room it is in now.
     *
     * Walks back through the sightings while they stay in the same room and stay
     * close enough together to be one visit — so "since 14:20" means the start of
     * this visit, not the last time a sensor happened to fire.
     */
    private long currentRunStart(List<RoomEvent> events) {
        int i = events.size() - 1;
        String room = events.get(i).getRoom();
        long start = events.get(i).getTimestampMillis();

        while (i > 0) {
            RoomEvent previous = events.get(i - 1);
            if (!room.equals(previous.getRoom())) break;
            if (start - previous.getTimestampMillis() > MAX_DWELL_MS) break;
            start = previous.getTimestampMillis();
            i--;
        }
        return start;
    }

    private void readDetections(DashboardStats stats, long petId, DayRange today) {
        DetectionDao dao = db.detectionDao();

        stats.detectionsToday =
                dao.countInRange(petId, today.startMillis, today.endMillis);

        DetectionEvent latestToday =
                dao.getMostRecentInRange(petId, today.startMillis, today.endMillis);
        if (latestToday != null) {
            stats.lastDetectionTime = new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(latestToday.getTimestampMillis()));
        }

        // "Online" is really "was demonstrably working recently". Without a
        // heartbeat from the Pi this is the best claim the data supports, so it
        // is what the flag means — see CAMERA_LIVENESS_WINDOW_MS.
        Long newest = dao.getMostRecentTimestamp(petId);
        stats.cameraOnline = newest != null
                && System.currentTimeMillis() - newest <= CAMERA_LIVENESS_WINDOW_MS;
    }
}
