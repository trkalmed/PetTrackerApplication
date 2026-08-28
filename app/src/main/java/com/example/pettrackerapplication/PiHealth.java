package com.example.pettrackerapplication;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What {@code GET /health} says about the Pi right now.
 *
 * Two things here matter to the app rather than to a human reading a terminal:
 * {@link #latestCursor}, which is how the sync notices that the Pi's event log
 * has been reset underneath it, and the per-sensor flags, which separate "the
 * feeder is unplugged" from "the feeder is fine and the pet has not eaten".
 */
public final class PiHealth {

    public final boolean ok;
    public final long timeMillis;
    public final long uptimeSeconds;

    /** The highest event id the Pi holds. 0 when its log is empty. */
    public final long latestCursor;

    public final boolean scaleAvailable;
    public final boolean feederAvailable;
    public final boolean cameraAvailable;
    public final boolean locationAvailable;

    /** Room name to whether that PIR was claimed, in the Pi's own order. */
    public final Map<String, Boolean> motionSensors;

    private PiHealth(boolean ok, long timeMillis, long uptimeSeconds, long latestCursor,
                     boolean scaleAvailable, boolean feederAvailable,
                     boolean cameraAvailable, boolean locationAvailable,
                     Map<String, Boolean> motionSensors) {
        this.ok = ok;
        this.timeMillis = timeMillis;
        this.uptimeSeconds = uptimeSeconds;
        this.latestCursor = latestCursor;
        this.scaleAvailable = scaleAvailable;
        this.feederAvailable = feederAvailable;
        this.cameraAvailable = cameraAvailable;
        this.locationAvailable = locationAvailable;
        this.motionSensors = Collections.unmodifiableMap(motionSensors);
    }

    public static PiHealth fromJson(JSONObject json) {
        JSONObject sensors = json.optJSONObject("sensors");
        if (sensors == null) sensors = new JSONObject();

        Map<String, Boolean> motion = new LinkedHashMap<>();
        JSONObject motionJson = sensors.optJSONObject("motion");
        if (motionJson != null) {
            for (Iterator<String> it = motionJson.keys(); it.hasNext(); ) {
                String room = it.next();
                motion.put(room, motionJson.optBoolean(room, false));
            }
        }

        return new PiHealth(
                json.optBoolean("ok", false),
                json.optLong("time_ms", 0),
                json.optLong("uptime_s", 0),
                json.optLong("latest_cursor", 0),
                sensors.optBoolean("scale", false),
                sensors.optBoolean("feeder", false),
                sensors.optBoolean("camera", false),
                sensors.optBoolean("location", false),
                motion);
    }

    /** True when at least one PIR was claimed — i.e. rooms can be expected. */
    public boolean anyMotionSensorAvailable() {
        for (Boolean available : motionSensors.values()) {
            if (Boolean.TRUE.equals(available)) return true;
        }
        return false;
    }
}
