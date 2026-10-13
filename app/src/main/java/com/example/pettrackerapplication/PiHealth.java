package com.example.pettrackerapplication;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** The Pi's GET /health reply. */
public final class PiHealth {

    public final boolean ok;
    public final long timeMillis;
    public final long uptimeSeconds;

    public final long latestCursor;

    public final Double latestWeightGrams;

    public final Double portionGrams;

    public final boolean feederOpen;

    public final String feederKind;

    public final Map<String, Boolean> cameras;

    public final boolean scaleAvailable;
    public final boolean feederAvailable;
    public final boolean cameraAvailable;
    public final boolean locationAvailable;

    public final Map<String, Boolean> motionSensors;

    private PiHealth(boolean ok, long timeMillis, long uptimeSeconds, long latestCursor,
                     boolean scaleAvailable, boolean feederAvailable,
                     boolean cameraAvailable, boolean locationAvailable,
                     Map<String, Boolean> motionSensors,
                     Map<String, Boolean> cameras, Double latestWeightGrams,
                     Double portionGrams, boolean feederOpen,
                     String feederKind) {
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
        this.feederKind = feederKind;
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
                json.optBoolean("feeder_open", false),
                json.isNull("feeder_kind") ? null : json.optString("feeder_kind", null));
    }

    public Map<String, Boolean> camerasOrLegacy() {
        if (!cameras.isEmpty()) return cameras;
        Map<String, Boolean> single = new LinkedHashMap<>();
        single.put("camera", cameraAvailable);
        return Collections.unmodifiableMap(single);
    }

    public boolean feederHoldsOpen() {
        return "hold".equals(feederKind);
    }

    public boolean anyMotionSensorAvailable() {
        for (Boolean available : motionSensors.values()) {
            if (Boolean.TRUE.equals(available)) return true;
        }
        return false;
    }
}
