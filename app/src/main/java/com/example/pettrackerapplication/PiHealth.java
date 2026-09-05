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

    /**
     * What the bowl weighs right now, or null when the scale is not reporting.
     *
     * Null rather than 0: a load cell that has stopped answering and a bowl
     * with nothing in it are different facts, and only one of them means the
     * pet has eaten everything.
     */
    public final Double latestWeightGrams;

    /**
     * What one serving is, in grams, or null if this feeder has no natural one.
     *
     * A paddle wheel meters in whole pockets, so a portion is one pocket and
     * the Pi is the only thing that knows how much that is. It belongs here
     * rather than in a box on the phone: asking a person for grams invites an
     * answer the hardware will round to the nearest pocket anyway.
     */
    public final Double portionGrams;

    /**
     * Whether a hold-style hopper is currently open.
     *
     * Always false for the metered designs — they shut themselves as part of
     * dispensing, so there is no moment between requests when they are open.
     */
    public final boolean feederOpen;

    /** Camera name to whether it is up, in the Pi's own order. */
    public final Map<String, Boolean> cameras;

    public final boolean scaleAvailable;
    public final boolean feederAvailable;
    public final boolean cameraAvailable;
    public final boolean locationAvailable;

    /** Room name to whether that PIR was claimed, in the Pi's own order. */
    public final Map<String, Boolean> motionSensors;

    private PiHealth(boolean ok, long timeMillis, long uptimeSeconds, long latestCursor,
                     boolean scaleAvailable, boolean feederAvailable,
                     boolean cameraAvailable, boolean locationAvailable,
                     Map<String, Boolean> motionSensors,
                     Map<String, Boolean> cameras, Double latestWeightGrams,
                     Double portionGrams, boolean feederOpen) {
        this.ok = ok;
        this.timeMillis = timeMillis;
        this.uptimeSeconds = uptimeSeconds;
        this.latestCursor = latestCursor;
        this.scaleAvailable = scaleAvailable;
        this.feederAvailable = feederAvailable;
        this.cameraAvailable = cameraAvailable;
        this.locationAvailable = locationAvailable;
        this.motionSensors = Collections.unmodifiableMap(motionSensors);
        this.cameras = Collections.unmodifiableMap(cameras);
        this.latestWeightGrams = latestWeightGrams;
        this.portionGrams = portionGrams;
        this.feederOpen = feederOpen;
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

        Map<String, Boolean> cameras = new LinkedHashMap<>();
        JSONObject camerasJson = sensors.optJSONObject("cameras");
        if (camerasJson != null) {
            for (Iterator<String> it = camerasJson.keys(); it.hasNext(); ) {
                String name = it.next();
                cameras.put(name, camerasJson.optBoolean(name, false));
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
                motion,
                cameras,
                json.isNull("latest_weight_grams")
                        ? null : json.optDouble("latest_weight_grams"),
                json.isNull("portion_grams")
                        ? null : json.optDouble("portion_grams"),
                json.optBoolean("feeder_open", false));
    }

    /**
     * The cameras this Pi has, in its own order, or a single unnamed one.
     *
     * A Pi that predates named cameras sends only the boolean, so one entry is
     * synthesised from it. That keeps the screen's "which camera" logic in one
     * shape rather than branching on the age of the Pi it is talking to.
     */
    public Map<String, Boolean> camerasOrLegacy() {
        if (!cameras.isEmpty()) return cameras;
        Map<String, Boolean> single = new LinkedHashMap<>();
        single.put("camera", cameraAvailable);
        return Collections.unmodifiableMap(single);
    }

    /** True when at least one PIR was claimed — i.e. rooms can be expected. */
    public boolean anyMotionSensorAvailable() {
        for (Boolean available : motionSensors.values()) {
            if (Boolean.TRUE.equals(available)) return true;
        }
        return false;
    }
}
