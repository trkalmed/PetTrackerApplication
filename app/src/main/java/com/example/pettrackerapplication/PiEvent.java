package com.example.pettrackerapplication;

import android.util.Log;

import org.json.JSONObject;

/** One reading as the Pi reports it. */
public final class PiEvent {

    private static final String TAG = "PiEvent";

    public static final String KIND_FEEDING = "feeding";
    public static final String KIND_MOTION = "motion";
    public static final String KIND_WEIGHT = "weight";
    public static final String KIND_LOCATION = "location";

    private static final long EARLIEST_BELIEVABLE_MS = 1_577_836_800_000L;

    public final long id;
    public final String kind;
    public final long timestampMillis;
    public final String source;

    public final Integer grams;
    public final String type;
    public final String room;
    public final String snapshot;
    public final Double latitude;
    public final Double longitude;
    public final Integer satellites;

    private PiEvent(long id, String kind, long timestampMillis, String source,
                    Integer grams, String type, String room, String snapshot,
                    Double latitude, Double longitude, Integer satellites) {
        this.id = id;
        this.kind = kind;
        this.timestampMillis = timestampMillis;
        this.source = source;
        this.grams = grams;
        this.type = type;
        this.room = room;
        this.snapshot = snapshot;
        this.latitude = latitude;
        this.longitude = longitude;
        this.satellites = satellites;
    }

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
                json.isNull("snapshot") ? null : json.optString("snapshot", null),
                json.has("lat") && !json.isNull("lat") ? json.optDouble("lat") : null,
                json.has("lon") && !json.isNull("lon") ? json.optDouble("lon") : null,
                json.has("satellites") ? json.optInt("satellites") : null);
    }

    @Override
    public String toString() {
        return "PiEvent{" + id + " " + kind + " @" + timestampMillis
                + (grams != null ? " " + grams + "g " + type : "")
                + (room != null ? " " + room : "")
                + (latitude != null ? " " + latitude + "," + longitude : "") + "}";
    }
}
