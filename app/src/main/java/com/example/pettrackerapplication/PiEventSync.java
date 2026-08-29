package com.example.pettrackerapplication;

import android.util.Log;

import java.io.IOException;

/**
 * Walks the Pi's event log and files what it finds.
 *
 * This is the missing half of the contract in pi/README.md: the Pi buffers
 * readings and never pushes, so somebody has to come and collect them. That is
 * this class. It holds the whole conversation — where we got to, what to do when
 * that turns out to be wrong, which kinds are worth storing — and none of the
 * transport, which is {@link PiEventSource}'s job.
 *
 * Three things it has to get right, none of them obvious:
 *
 * <b>The cursor can be wrong in two directions.</b> It is a row id in one
 * particular Pi's database. Point the app at a different Pi, or reinstall the
 * one it had, and the same number now means a different place — or nothing at
 * all. Both are handled: a changed host resets it, and a Pi whose log does not
 * reach the stored cursor resets it too, because that means the log was pruned
 * or rebuilt underneath us. The alternative is a phone that has silently stopped
 * receiving anything and cannot tell.
 *
 * <b>Re-reading has to be free.</b> Which it is, because every reset above
 * replays events the app may already hold, and each one carries the id it had on
 * the Pi. The unique index over (petId, source, remoteId) drops the second copy.
 * Without that, "recover from a rewind" and "double every meal" would be the
 * same operation.
 *
 * <b>A sync must end.</b> A Pi that has been running for a month with a phone
 * that has not synced holds tens of thousands of events. Paging through all of
 * them in one pass on a foreground thread pool would stall the UI it is meant to
 * be filling, so a sync takes {@link #MAX_PAGES_PER_SYNC} pages and leaves the
 * rest for the next one, which is seconds away.
 *
 * Blocks on network and database. Call from a background thread.
 */
public final class PiEventSync {

    private static final String TAG = "PiEventSync";

    /**
     * Pages per sync. The Pi sends up to 500 events a page, so this is 10,000
     * readings — far more than a normal catch-up, and a bounded amount of work.
     */
    private static final int MAX_PAGES_PER_SYNC = 20;

    /** Where the app's place in the Pi's log is kept between syncs. */
    public interface Cursor {
        long value();
        void save(long cursor);
    }

    /** What one sync did, in the terms a screen or a log would want. */
    public static final class Result {
        /** False when the Pi could not be reached at all. */
        public final boolean reachable;
        /** Readings that became rows. */
        public final int stored;
        /** Readings deliberately not stored: weights, unknown kinds, duplicates. */
        public final int ignored;
        /** Entries that could not be parsed or trusted. */
        public final int malformed;
        /** Where the cursor ended up. */
        public final long cursor;
        /** True while the Pi has more waiting than this sync collected. */
        public final boolean moreWaiting;
        /** Null when nothing went wrong. */
        public final String error;
        /** Null when the Pi could not be reached. */
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

        /** Whether anything changed, i.e. whether a screen showing this should redraw. */
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

    /**
     * Collects everything waiting, attributing it to {@code petId}.
     *
     * The pet id comes from the app, not the Pi: a load cell cannot tell which
     * cat ate and a PIR cannot tell which cat walked past, so readings belong to
     * whichever pet the owner is currently tracking. That is a limitation of the
     * hardware, and pretending otherwise would be worse than admitting it.
     */
    public Result sync(long petId) {
        if (petId <= 0) {
            // Nothing to attach readings to. Collecting them now would mean
            // deciding whose they are later, with no way to tell.
            return Result.failed("no pet selected");
        }

        PiHealth health;
        try {
            health = source.health();
        } catch (IOException e) {
            // The ordinary case: the phone is not at home. Not an error worth
            // shouting about, and the Pi is holding everything until it is.
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
                    // Advance past each reading as it is filed, so an interrupted
                    // sync resumes from the last one that actually landed rather
                    // than repeating the page or skipping the rest of it.
                    since = Math.max(since, event.id);
                }

                since = Math.max(since, events.nextCursor);
                cursor.save(since);

                if (!events.hasMore) break;
                moreWaiting = page == MAX_PAGES_PER_SYNC - 1;
            }
        } catch (IOException e) {
            // Whatever was filed before the connection dropped stays filed, and
            // the saved cursor already reflects it.
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

    /**
     * Where to start reading, given what the Pi says it holds.
     *
     * The stored cursor is only meaningful for the Pi it was recorded against
     * and only while that Pi still has the events behind it. When either stops
     * being true, starting over is safe — the remote ids make a replay a no-op —
     * and is the only way to notice a rebuilt Pi at all.
     */
    private long startingCursor(PiHealth health) {
        long saved = cursor.value();
        if (health.latestCursor < saved) {
            Log.i(TAG, "Pi's log only reaches " + health.latestCursor
                    + " but we are at " + saved + " — its buffer was reset, starting over");
            return 0;
        }
        return saved;
    }

    /** @return true if the reading became a row */
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

            case PiEvent.KIND_WEIGHT:
                // Diagnostics for whoever is calibrating the load cell. The bowl
                // weighing 118 g is not something the app has anything to say
                // about; the meals the Pi derives from it are.
                return false;

            default:
                // The contract says adding a kind is safe, so an unknown one is
                // a newer Pi rather than a fault. Skip it and keep going.
                Log.d(TAG, "Ignoring unknown event kind: " + event.kind);
                return false;
        }
    }

    /** Exception text fit to put in front of a person. */
    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty()
                ? e.getClass().getSimpleName() : message;
    }
}
