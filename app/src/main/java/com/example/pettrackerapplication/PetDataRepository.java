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

    /**
     * How stale the Pi's own account of itself can be before it stops counting.
     *
     * The poller runs every 15 s while a screen is open, so anything older than
     * this means the app has not spoken to the Pi recently — in which case the
     * camera's state is a guess, and a guess should not be presented as "live".
     */
    private static final long HEALTH_FRESHNESS_MS = 2 * 60 * 1000L;

    private final AppDatabase db;
    private final Context context;

    public PetDataRepository(Context context) {
        this.context = context.getApplicationContext();
        this.db = AppDatabase.getInstance(this.context);
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
        return recordMotion(petId, timestampMillis, room, source, remoteId, null);
    }

    /**
     * Records a sighting along with the picture the Pi took of it.
     *
     * @param snapshotName the Pi's file name for the frame, or null if the rig
     *                     has no camera. Only the name — see {@link RoomEvent}.
     */
    public long recordMotion(long petId, long timestampMillis, String room,
                             String source, Long remoteId, String snapshotName) {
        if (room == null || room.trim().isEmpty()) {
            // A sighting with no room says only "something moved somewhere",
            // which no screen can render and no total can use.
            Log.w(TAG, "Ignoring motion reading with no room");
            return -1;
        }
        RoomEvent event =
                new RoomEvent(petId, timestampMillis, room.trim(), source);
        event.setRemoteId(remoteId);
        event.setSnapshotName(snapshotName);
        return db.roomDao().insert(event);
    }

    /**
     * Records where the <em>Raspberry Pi</em> was, which is not where the pet was.
     *
     * See {@link TrackerFix} for why that distinction is kept all the way down
     * to the table name. Nothing here feeds any screen that answers "where is
     * the cat".
     *
     * The coordinates are validated rather than trusted. A GGA sentence with a
     * fix quality of 0 still carries lat and lon fields, usually zeroed, and the
     * Pi already drops those — but NaN survives a JSON parse, and 0,0 is a real
     * point in the Gulf of Guinea that no Pi has ever been at. Both would look
     * like a working tracker in the wrong place, which is worse than no fix.
     *
     * @return the new row id, or -1 if the reading was rejected or already held
     */
    public long recordTrackerFix(long petId, long timestampMillis, double latitude,
                                 double longitude, int satellites, String source,
                                 Long remoteId) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)
                || Double.isInfinite(latitude) || Double.isInfinite(longitude)) {
            Log.w(TAG, "Ignoring fix with unparseable coordinates");
            return -1;
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            Log.w(TAG, "Ignoring fix outside the possible range: "
                    + latitude + ", " + longitude);
            return -1;
        }
        if (latitude == 0 && longitude == 0) {
            // Null Island. Reachable by boat, not by a Pi in a hallway, and the
            // classic shape of a fix built from empty NMEA fields.
            Log.w(TAG, "Ignoring a 0,0 fix");
            return -1;
        }

        TrackerFix fix = new TrackerFix(petId, timestampMillis, latitude, longitude,
                Math.max(0, satellites), source);
        fix.setRemoteId(remoteId);
        return db.trackerFixDao().insert(fix);
    }

    /**
     * Sets this pet's daily food target, or clears it back to the default.
     *
     * @param grams a positive number of grams, or null to use the default
     */
    public void setDailyFoodGrams(long petId, String ownerId, Integer grams) {
        Pet pet = db.petDao().getPetById(petId, ownerId);
        if (pet == null) return;
        pet.setDailyFoodGrams(grams != null && grams > 0 ? grams : null);
        db.petDao().update(pet);
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

        Integer target = pet.getDailyFoodGrams();
        if (target != null && target > 0) {
            stats.gramsTarget = target;
        }

        readFeeding(stats, petId, today);
        readDetections(stats, petId, today);
        readRooms(stats, petId, today);

        readTrackerFix(stats, petId);

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
    /**
     * One motion sighting, as a history list wants it.
     *
     * A rendered view of a {@link RoomEvent} rather than the row itself: the
     * clock formatting belongs next to the other clock formatting in this class,
     * and a screen has no business knowing the storage shape.
     */
    public static final class Sighting {
        public final String room;
        public final String time;            // "15:20"
        /** The Pi's file name for the frame, or null if none was taken. */
        public final String snapshotName;
        public final long timestampMillis;

        Sighting(String room, String time, String snapshotName, long timestampMillis) {
            this.room = room;
            this.time = time;
            this.snapshotName = snapshotName;
            this.timestampMillis = timestampMillis;
        }

        public boolean hasPicture() {
            return snapshotName != null && !snapshotName.isEmpty();
        }
    }

    /**
     * Today's sightings, newest first.
     *
     * Only what this phone has collected. The Pi keeps thirty days; the app
     * holds whatever it synced while it was open, so a day with the app closed
     * is a gap here and not on the Pi. Fetching the difference on demand is a
     * later job — see pi/README.md for the endpoint that would do it.
     *
     * Hits the database. Call from a background thread.
     */
    public List<Sighting> sightingsForToday(Pet pet) {
        List<Sighting> sightings = new ArrayList<>();
        if (pet == null) return sightings;

        DayRange today = DayRange.today();
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

        for (RoomEvent event : db.roomDao().getInRangeNewestFirst(
                pet.getId(), today.startMillis, today.endMillis)) {
            sightings.add(new Sighting(
                    event.getRoom(),
                    clock.format(new Date(event.getTimestampMillis())),
                    event.getSnapshotName(),
                    event.getTimestampMillis()));
        }
        return sightings;
    }

    /**
     * Whether the Pi says it has a camera, recently enough to believe.
     *
     * This is the honest answer to "is the camera live", and it comes free with
     * every poll. The detector-based guess below remains as a fallback for a
     * phone with no Pi paired, where the only evidence available is that the
     * phone's own camera was recently producing sightings.
     */
    private boolean piCameraLive() {
        android.content.SharedPreferences prefs = Prefs.get(context);
        if (!prefs.getBoolean(Prefs.KEY_PI_CAMERA_OK, false)) return false;

        long at = prefs.getLong(Prefs.KEY_PI_HEALTH_AT, 0L);
        return at > 0 && System.currentTimeMillis() - at <= HEALTH_FRESHNESS_MS;
    }

    /**
     * The old rule, kept for a phone with no Pi: a sighting in the last few
     * minutes means something was demonstrably working recently.
     */
    private boolean recentlyDetectedSomething(DetectionDao dao, long petId) {
        Long newest = dao.getMostRecentTimestamp(petId);
        return newest != null
                && System.currentTimeMillis() - newest <= CAMERA_LIVENESS_WINDOW_MS;
    }

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

        // The newest sighting that came with a picture, which is usually not the
        // newest sighting: the camera misses plenty of them.
        RoomEvent pictured = dao.getMostRecentWithSnapshot(
                petId, today.startMillis, today.endMillis);
        SimpleDateFormat pictureClock =
                new SimpleDateFormat("HH:mm", Locale.getDefault());
        if (pictured != null) {
            stats.lastSnapshotName = pictured.getSnapshotName();
            stats.lastSnapshotRoom = pictured.getRoom();
            stats.lastSnapshotTime =
                    pictureClock.format(new Date(pictured.getTimestampMillis()));
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

        stats.cameraOnline = piCameraLive() || recentlyDetectedSomething(dao, petId);
    }

    /**
     * The tracker's last known position — the Pi's, not the pet's.
     *
     * Not limited to today, unlike everything else on this screen. A GNSS module
     * indoors can go a long time between fixes, and "located yesterday at 18:04"
     * is both more useful and more truthful than a blank that reads as "no GPS".
     * The date is included once the fix is not from today, so a stale one cannot
     * be mistaken for a fresh one at a glance.
     */
    private void readTrackerFix(DashboardStats stats, long petId) {
        TrackerFix fix = db.trackerFixDao().getMostRecent(petId);
        if (fix == null) return;

        DayRange today = DayRange.today();
        boolean fromToday = fix.getTimestampMillis() >= today.startMillis
                && fix.getTimestampMillis() < today.endMillis;

        stats.trackerFixTime = new SimpleDateFormat(
                fromToday ? "HH:mm" : "d MMM HH:mm", Locale.getDefault())
                .format(new Date(fix.getTimestampMillis()));
        stats.trackerLatitude = fix.getLatitude();
        stats.trackerLongitude = fix.getLongitude();
        stats.trackerSatellites = fix.getSatellites();
    }
}
