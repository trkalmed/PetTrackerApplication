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

/** Where sensor readings are stored and where the screens read them back. */
public final class PetDataRepository {

    private static final String TAG = "PetDataRepository";

    private static final long CAMERA_LIVENESS_WINDOW_MS = 5 * 60 * 1000L;

    private static final long MAX_DWELL_MS = 15 * 60 * 1000L;

    private static final long HEALTH_FRESHNESS_MS = 2 * 60 * 1000L;

    private final AppDatabase db;
    private final Context context;

    public PetDataRepository(Context context) {
        this.context = context.getApplicationContext();
        this.db = AppDatabase.getInstance(this.context);
    }

    public long recordFeeding(long petId, long timestampMillis, int grams,
                              String type, String source) {
        return recordFeeding(petId, timestampMillis, grams, type, source, null);
    }

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

    public long recordMotion(long petId, long timestampMillis, String room,
                             String source, Long remoteId) {
        return recordMotion(petId, timestampMillis, room, source, remoteId, null);
    }

    public long recordMotion(long petId, long timestampMillis, String room,
                             String source, Long remoteId, String snapshotName) {
        if (room == null || room.trim().isEmpty()) {
            Log.w(TAG, "Ignoring motion reading with no room");
            return -1;
        }
        RoomEvent event =
                new RoomEvent(petId, timestampMillis, room.trim(), source);
        event.setRemoteId(remoteId);
        event.setSnapshotName(snapshotName);
        return db.roomDao().insert(event);
    }

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
            // Reject (0, 0): the shape of a fix built from empty NMEA fields.
            Log.w(TAG, "Ignoring a 0,0 fix");
            return -1;
        }

        TrackerFix fix = new TrackerFix(petId, timestampMillis, latitude, longitude,
                Math.max(0, satellites), source);
        fix.setRemoteId(remoteId);
        return db.trackerFixDao().insert(fix);
    }

    public void setDailyFoodGrams(long petId, String ownerId, Integer grams) {
        Pet pet = db.petDao().getPetById(petId, ownerId);
        if (pet == null) return;
        pet.setDailyFoodGrams(grams != null && grams > 0 ? grams : null);
        db.petDao().update(pet);
    }

    public long recordMealEaten(long petId, int grams, String source) {
        return recordFeeding(petId, System.currentTimeMillis(), grams,
                FeedingEvent.TYPE_EATEN, source);
    }

    public long recordDetection(long petId, long timestampMillis, String label,
                                float confidence, String source) {
        return db.detectionDao().insert(
                new DetectionEvent(petId, timestampMillis, label, confidence, source));
    }

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

        // No reading at all means no feeder is paired, which differs from eating nothing.
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

    public static final class Sighting {
        public final String room;
        public final String time;
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

    private boolean piCameraLive() {
        android.content.SharedPreferences prefs = Prefs.get(context);
        if (!prefs.getBoolean(Prefs.KEY_PI_CAMERA_OK, false)) return false;

        long at = prefs.getLong(Prefs.KEY_PI_HEALTH_AT, 0L);
        return at > 0 && System.currentTimeMillis() - at <= HEALTH_FRESHNESS_MS;
    }

    private boolean recentlyDetectedSomething(DetectionDao dao, long petId) {
        Long newest = dao.getMostRecentTimestamp(petId);
        return newest != null
                && System.currentTimeMillis() - newest <= CAMERA_LIVENESS_WINDOW_MS;
    }

    private void readRooms(DashboardStats stats, long petId, DayRange today) {
        RoomDao dao = db.roomDao();

        // No sighting at all means no sensors are paired, which differs from a quiet day.
        stats.roomsReporting = dao.hasAnyReading(petId);
        if (!stats.roomsReporting) return;

        List<RoomEvent> events =
                dao.getInRange(petId, today.startMillis, today.endMillis);
        if (events.isEmpty()) return;

        long now = System.currentTimeMillis();
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
        Collections.sort(ordered, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> entry : ordered) {
            if (entry.getValue() <= 0) continue;
            // A visited room shows at least one minute so it does not vanish from the chart.
            int minutes = Math.max(1, Math.round(entry.getValue() / 60_000f));
            stats.rooms.add(new DashboardStats.RoomStay(entry.getKey(), minutes));
        }

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
            // Silence is not a location: report when the last sighting was.
            stats.lastMotionTime = clock.format(new Date(latest.getTimestampMillis()));
        }
    }

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
