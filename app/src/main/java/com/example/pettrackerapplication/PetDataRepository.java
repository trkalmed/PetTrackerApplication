package com.example.pettrackerapplication;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
        if (grams < 0) {
            Log.w(TAG, "Ignoring feeding reading with negative grams: " + grams);
            return -1;
        }
        if (!FeedingEvent.TYPE_EATEN.equals(type)
                && !FeedingEvent.TYPE_DISPENSED.equals(type)) {
            Log.w(TAG, "Ignoring feeding reading with unknown type: " + type);
            return -1;
        }
        return db.feedingDao().insert(
                new FeedingEvent(petId, timestampMillis, grams, type, source));
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

        // Room occupancy and GPS have no source yet: no motion sensors, no
        // collar tag, no location provider. Leaving them empty keeps the setup
        // prompts honest instead of inventing a number.

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
