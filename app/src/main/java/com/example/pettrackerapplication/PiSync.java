package com.example.pettrackerapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs PiEventSync for a screen, only while the app is in the foreground. */
public final class PiSync {

    private static final String TAG = "PiSync";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private PiSync() {
    }

    public interface Listener {
        void onSyncFinished(PiEventSync.Result result);
    }

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
                Log.e(TAG, "Sync failed", e);
                result = PiEventSync.Result.failed(e.getClass().getSimpleName());
            } finally {
                IN_FLIGHT.set(false);
            }
            deliver(listener, result);
        });
    }

    private static void rememberHealth(Context app, PiHealth health) {
        if (health == null) return;
        Prefs.get(app).edit()
                .putBoolean(Prefs.KEY_PI_CAMERA_OK, health.cameraAvailable)
                .putLong(Prefs.KEY_PI_HEALTH_AT, System.currentTimeMillis())
                .apply();
    }

    public static long lastSyncMillis(Context context) {
        return Prefs.get(context).getLong(Prefs.KEY_PI_LAST_SYNC, 0L);
    }

    public interface HealthListener {
        void onHealth(PiHealth health, String error);
    }

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

    public static final class Poller {

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
