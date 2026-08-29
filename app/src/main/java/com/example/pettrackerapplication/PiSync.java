package com.example.pettrackerapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs {@link PiEventSync} on behalf of a screen.
 *
 * <b>Only while the app is on screen, and that is deliberate.</b> Polling in the
 * background would mean a foreground service and its permanent notification, or
 * WorkManager and its fifteen-minute floor — real costs, in battery and in the
 * user's notification shade, for something that buys nothing: the Pi buffers
 * every reading and hands over whatever was missed the moment the app is opened.
 * Nothing is lost by not listening, so nothing listens. If push notifications
 * ("Biscuit has not eaten today") are ever wanted, that is the point at which
 * background sync earns its keep, and WorkManager is where it goes.
 *
 * The work itself runs on one shared background thread. Two screens asking at
 * once, or a poll landing on top of a slow one, collapses to a single sync —
 * they would only fight over the same cursor.
 */
public final class PiSync {

    private static final String TAG = "PiSync";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private PiSync() {
        // static helper, no instances
    }

    /**
     * Told what one sync did, on the main thread.
     *
     * A sync outlives the screen that asked for it, so implementations must
     * check they are still alive before touching views — the same guard every
     * database load in this app already uses.
     */
    public interface Listener {
        void onSyncFinished(PiEventSync.Result result);
    }

    /**
     * Collects whatever the Pi is holding, if there is a Pi and a pet.
     *
     * Does nothing when a sync is already running: the listener of the one in
     * flight is called instead, and screens reload on resume regardless, so a
     * dropped request costs nothing.
     */
    public static void requestSync(Context context, Listener listener) {
        Context app = context.getApplicationContext();

        PiClient client = PiClient.forSavedPi(app);
        if (client == null) {
            deliver(listener, PiEventSync.Result.failed("no Pi paired"));
            return;
        }

        long petId = Prefs.getActivePetId(app);
        if (petId <= 0) {
            deliver(listener, PiEventSync.Result.failed("no pet selected"));
            return;
        }

        if (!IN_FLIGHT.compareAndSet(false, true)) return;

        WORKER.execute(() -> {
            PiEventSync.Result result;
            try {
                result = new PiEventSync(client, new PetDataRepository(app),
                        cursorFor(app)).sync(petId);
                if (result.reachable && result.error == null) {
                    Prefs.get(app).edit()
                            .putLong(Prefs.KEY_PI_LAST_SYNC, System.currentTimeMillis())
                            .apply();
                }
                rememberHealth(app, result.health);
            } catch (RuntimeException e) {
                // A database that refuses a write, most likely because the
                // active pet was deleted between the check above and here. The
                // sync is lost, not the app.
                Log.e(TAG, "Sync failed", e);
                result = PiEventSync.Result.failed(e.getClass().getSimpleName());
            } finally {
                IN_FLIGHT.set(false);
            }
            deliver(listener, result);
        });
    }

    /**
     * Keeps what the Pi just said about its sensors, so a screen can render it
     * without waiting for a round trip of its own.
     */
    private static void rememberHealth(Context app, PiHealth health) {
        if (health == null) return;
        Prefs.get(app).edit()
                .putBoolean(Prefs.KEY_PI_CAMERA_OK, health.cameraAvailable)
                .putLong(Prefs.KEY_PI_HEALTH_AT, System.currentTimeMillis())
                .apply();
    }

    /** When the last successful sync finished, or 0 if there has never been one. */
    public static long lastSyncMillis(Context context) {
        return Prefs.get(context).getLong(Prefs.KEY_PI_LAST_SYNC, 0L);
    }

    /** Told whether the Pi answered, on the main thread. */
    public interface HealthListener {
        void onHealth(PiHealth health, String error);
    }

    /**
     * Asks the Pi whether it is there, and nothing more.
     *
     * Separate from {@link #requestSync} because "is this address right" is a
     * different question from "collect the readings": it has to be answerable
     * before a pet exists, while someone is standing in Settings typing an IP
     * address, and it must not be reported as a failure just because nothing has
     * been chosen to file readings against yet.
     */
    public static void checkHealth(Context context, HealthListener listener) {
        Context app = context.getApplicationContext();
        PiClient client = PiClient.forSavedPi(app);
        if (client == null) {
            new Handler(Looper.getMainLooper())
                    .post(() -> listener.onHealth(null, "no Pi paired"));
            return;
        }

        WORKER.execute(() -> {
            PiHealth health = null;
            String error = null;
            try {
                health = client.health();
            } catch (Exception e) {
                error = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
            }
            rememberHealth(app, health);
            final PiHealth result = health;
            final String failure = error;
            new Handler(Looper.getMainLooper())
                    .post(() -> listener.onHealth(result, failure));
        });
    }

    /**
     * The cursor, tied to the address it was recorded against.
     *
     * Re-pointing the app at a different Pi makes the stored number meaningless
     * — it indexes a log this machine has never seen — so the host is stored
     * alongside it and a mismatch starts from the beginning. Replaying is
     * harmless; silently reading from the wrong offset is not.
     */
    private static PiEventSync.Cursor cursorFor(Context app) {
        SharedPreferences prefs = Prefs.get(app);
        String host = PiConnectionManager.getSavedIp(app);

        return new PiEventSync.Cursor() {
            @Override
            public long value() {
                if (!host.equals(prefs.getString(Prefs.KEY_PI_CURSOR_HOST, ""))) {
                    return 0;
                }
                return prefs.getLong(Prefs.KEY_PI_CURSOR, 0L);
            }

            @Override
            public void save(long cursor) {
                prefs.edit()
                        .putLong(Prefs.KEY_PI_CURSOR, cursor)
                        .putString(Prefs.KEY_PI_CURSOR_HOST, host)
                        .apply();
            }
        };
    }

    private static void deliver(Listener listener, PiEventSync.Result result) {
        if (listener == null) return;
        new Handler(Looper.getMainLooper()).post(() -> listener.onSyncFinished(result));
    }

    /**
     * Keeps a screen up to date for as long as it is in front of the user.
     *
     * Start it in {@code onResume}, stop it in {@code onPause}. Nothing runs
     * once stopped, which is the whole design: see the class comment.
     */
    public static final class Poller {

        /**
         * Faster than anything the hardware can produce. The Pi debounces motion
         * to one event per room per 30 s and only commits a meal after the bowl
         * has been still for 4 s, so polling more often would just ask the same
         * question twice.
         */
        private static final long INTERVAL_MS = 15_000L;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Context context;
        private final Listener listener;
        private boolean running;

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                requestSync(context, listener);
                if (running) handler.postDelayed(this, INTERVAL_MS);
            }
        };

        public Poller(Context context, Listener listener) {
            this.context = context.getApplicationContext();
            this.listener = listener;
        }

        /** Syncs now, then every {@link #INTERVAL_MS} until stopped. */
        public void start() {
            if (running) return;
            running = true;
            handler.post(tick);
        }

        public void stop() {
            running = false;
            handler.removeCallbacks(tick);
        }
    }
}
