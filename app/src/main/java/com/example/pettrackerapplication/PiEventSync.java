package com.example.pettrackerapplication;

import android.util.Log;

import java.io.IOException;

/** Walks the Pi's event log and stores what it finds. */
public final class PiEventSync {

    private static final String TAG = "PiEventSync";

    private static final int MAX_PAGES_PER_SYNC = 20;

    public interface Cursor {
        long value();
        void save(long cursor);
    }

    public static final class Result {
        public final boolean reachable;
        public final int stored;
        public final int ignored;
        public final int malformed;
        public final long cursor;
        public final boolean moreWaiting;
        public final String error;
        public final PiHealth health;

        Result(boolean reachable, int stored, int ignored, int malformed, long cursor,
               boolean moreWaiting, String error, PiHealth health) {
            this.reachable = reachable;
            this.stored = stored;
            this.ignored = ignored;
            this.malformed = malformed;
            this.cursor = cursor;
            this.moreWaiting = moreWaiting;
            this.error = error;
            this.health = health;
        }

        static Result failed(String error) {
            return new Result(false, 0, 0, 0, 0, false, error, null);
        }

        public boolean storedAnything() {
            return stored > 0;
        }

        @Override
        public String toString() {
            return reachable
                    ? "stored " + stored + ", ignored " + ignored
                            + ", malformed " + malformed + ", cursor " + cursor
                    : "unreachable: " + error;
        }
    }

    private final PiEventSource source;
    private final PetDataRepository repository;
    private final Cursor cursor;

    public PiEventSync(PiEventSource source, PetDataRepository repository, Cursor cursor) {
        this.source = source;
        this.repository = repository;
        this.cursor = cursor;
    }

    public Result sync(long petId) {
        if (petId <= 0) {
            return Result.failed("no pet selected");
        }

        PiHealth health;
        try {
            health = source.health();
        } catch (IOException e) {
            Log.d(TAG, "Pi unreachable: " + e.getMessage());
            return Result.failed(describe(e));
        }

        long since = startingCursor(health);
        int stored = 0, ignored = 0, malformed = 0;
        boolean moreWaiting = false;

        try {
            for (int page = 0; page < MAX_PAGES_PER_SYNC; page++) {
                PiEventPage events = source.events(since);
                malformed += events.malformed;

                for (PiEvent event : events.events) {
                    if (apply(event, petId)) {
                        stored++;
                    } else {
                        ignored++;
                    }
                    // Advance the cursor per reading so an interrupted sync resumes where it stopped.
                    since = Math.max(since, event.id);
                }

                since = Math.max(since, events.nextCursor);
                cursor.save(since);

                if (!events.hasMore) break;
                moreWaiting = page == MAX_PAGES_PER_SYNC - 1;
            }
        } catch (IOException e) {
            cursor.save(since);
            return new Result(true, stored, ignored, malformed, since, true,
                    describe(e), health);
        }

        if (stored > 0 || malformed > 0) {
            Log.i(TAG, "sync: stored " + stored + ", ignored " + ignored
                    + ", malformed " + malformed + ", cursor " + since);
        }
        return new Result(true, stored, ignored, malformed, since, moreWaiting,
                null, health);
    }

    private long startingCursor(PiHealth health) {
        long saved = cursor.value();
        if (health.latestCursor < saved) {
            Log.i(TAG, "Pi's log only reaches " + health.latestCursor
                    + " but we are at " + saved + " — its buffer was reset, starting over");
            return 0;
        }
        return saved;
    }

    private boolean apply(PiEvent event, long petId) {
        switch (event.kind) {
            case PiEvent.KIND_FEEDING:
                if (event.grams == null || event.type == null) {
                    Log.w(TAG, "Feeding event " + event.id + " has no grams or type");
                    return false;
                }
                return repository.recordFeeding(petId, event.timestampMillis,
                        event.grams, event.type, event.source, event.id) > 0;

            case PiEvent.KIND_MOTION:
                return repository.recordMotion(petId, event.timestampMillis,
                        event.room, event.source, event.id, event.snapshot) > 0;

            case PiEvent.KIND_LOCATION:
                if (event.latitude == null || event.longitude == null) {
                    Log.w(TAG, "Location event " + event.id + " has no coordinates");
                    return false;
                }
                return repository.recordTrackerFix(petId, event.timestampMillis,
                        event.latitude, event.longitude,
                        event.satellites == null ? 0 : event.satellites,
                        event.source, event.id) > 0;

            case PiEvent.KIND_WEIGHT:
                return false;

            default:
                // An unknown kind comes from a newer Pi: skip it.
                Log.d(TAG, "Ignoring unknown event kind: " + event.kind);
                return false;
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty()
                ? e.getClass().getSimpleName() : message;
    }
}
