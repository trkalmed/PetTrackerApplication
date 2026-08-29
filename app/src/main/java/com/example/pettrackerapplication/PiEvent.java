package com.example.pettrackerapplication;

import android.util.Log;

import org.json.JSONObject;

/**
 * One reading as the Pi reports it, before the app decides what to do with it.
 *
 * A deliberately dumb value object: it holds every field any event kind uses and
 * leaves them null when they do not apply, rather than growing a class per kind
 * for a contract with three of them. What it does own is the parsing, including
 * the two ways a reading can arrive unusable — see {@link #fromJson}.
 *
 * The wire format lives in pi/README.md and is the contract between the two
 * halves of this project. Field names here match it exactly.
 */
public final class PiEvent {

    private static final String TAG = "PiEvent";

    /** Grams left or entered the bowl. Carries {@link #grams} and {@link #type}. */
    public static final String KIND_FEEDING = "feeding";
    /** A PIR fired, already debounced by the Pi. Carries {@link #room}. */
    public static final String KIND_MOTION = "motion";
    /** Raw bowl weight, logged for diagnostics. The app has no use for it. */
    public static final String KIND_WEIGHT = "weight";

    /**
     * Timestamps before this are not believed.
     *
     * A Raspberry Pi has no real-time clock. Between boot and its first NTP sync
     * it genuinely thinks it is 1970, and anything it logs in that window is
     * stamped accordingly. Those readings cannot be placed on a day, so they are
     * dropped rather than being quietly filed under 1 January 1970 — or worse,
     * moved to "now", which would invent a meal that never happened at that time.
     */
    private static final long EARLIEST_BELIEVABLE_MS = 1_577_836_800_000L;  // 2020-01-01

    public final long id;
    public final String kind;
    public final long timestampMillis;
    public final String source;

    /** Feeding only. */
    public final Integer grams;
    /** Feeding only: {@link FeedingEvent#TYPE_EATEN} or TYPE_DISPENSED. */
    public final String type;
    /** Motion only. */
    public final String room;
    /** Motion only, and only when the Pi had a camera: the saved frame's name. */
    public final String snapshot;

    private PiEvent(long id, String kind, long timestampMillis, String source,
                    Integer grams, String type, String room, String snapshot) {
        this.id = id;
        this.kind = kind;
        this.timestampMillis = timestampMillis;
        this.source = source;
        this.grams = grams;
        this.type = type;
        this.room = room;
        this.snapshot = snapshot;
    }

    /**
     * Parses one event, or returns null if it cannot be trusted.
     *
     * Null rather than an exception on purpose: one malformed row in a page of
     * five hundred should cost that row, not the sync. The caller counts what it
     * skipped and moves the cursor past it, because a reading that cannot be
     * parsed now will not parse any better on the next poll.
     */
    public static PiEvent fromJson(JSONObject json) {
        if (json == null) return null;

        long id = json.optLong("id", -1);
        String kind = json.optString("kind", "");
        long timestamp = json.optLong("timestamp_ms", 0);

        if (id <= 0 || kind.isEmpty()) {
            Log.w(TAG, "Ignoring event with no id or kind: " + json);
            return null;
        }
        if (timestamp < EARLIEST_BELIEVABLE_MS) {
            Log.w(TAG, "Ignoring event " + id + " stamped before the Pi knew the time: "
                    + timestamp);
            return null;
        }

        return new PiEvent(
                id,
                kind,
                timestamp,
                json.isNull("source") ? null : json.optString("source", null),
                json.has("grams") ? json.optInt("grams") : null,
                json.isNull("type") ? null : json.optString("type", null),
                json.isNull("room") ? null : json.optString("room", null),
                json.isNull("snapshot") ? null : json.optString("snapshot", null));
    }

    @Override
    public String toString() {
        return "PiEvent{" + id + " " + kind + " @" + timestampMillis
                + (grams != null ? " " + grams + "g " + type : "")
                + (room != null ? " " + room : "") + "}";
    }
}
