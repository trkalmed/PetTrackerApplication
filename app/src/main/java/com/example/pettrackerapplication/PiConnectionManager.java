package com.example.pettrackerapplication;

import android.content.Context;
import android.content.Intent;

/**
 * Single source of truth for the Raspberry Pi's saved IP address.
 * Used by Petmenu (Tracking button) and SettingsActivity (Pi Connection
 * preference) so both screens always stay in sync.
 */
public class PiConnectionManager {

    private static final int STREAM_PORT = 8080;

    /**
     * The rig's address on the home network, used until someone saves another.
     *
     * A default rather than a hard-coded value: it is only consulted when
     * nothing has been saved, and saving any address replaces it permanently.
     * The alternative — a blank field on a fresh install — means the app looks
     * broken until an IP is typed, which for a demonstration is the difference
     * between opening the app and explaining the app.
     *
     * It stops being right the moment the Pi is on a different network, which
     * is why the Settings dialog exists and why nothing here treats this as
     * more than a starting point.
     */
    public static final String DEFAULT_PI_IP = "192.168.100.19";

    private PiConnectionManager() {
        // static helper, no instances
    }

    public static String getSavedIp(Context context) {
        return Prefs.get(context).getString(Prefs.KEY_PI_IP, DEFAULT_PI_IP);
    }

    public static boolean hasSavedIp(Context context) {
        return !getSavedIp(context).isEmpty();
    }

    public static void saveIp(Context context, String ip) {
        Prefs.get(context).edit().putString(Prefs.KEY_PI_IP, ip.trim()).apply();
    }

    public static void clearIp(Context context) {
        Prefs.get(context).edit().remove(Prefs.KEY_PI_IP).apply();
    }

    /**
     * The Pi's HTTP root, or null if no IP has been saved yet.
     *
     * One place builds this. The stream and the event poller are two clients of
     * the same service on the same port, and having each assemble its own URL is
     * how they end up disagreeing about which port that is.
     */
    public static String getBaseUrl(Context context) {
        String ip = getSavedIp(context);
        if (ip.isEmpty()) return null;
        return "http://" + ip + ":" + STREAM_PORT;
    }

    /** Returns the full stream URL, or null if no IP has been saved yet. */
    public static String getStreamUrl(Context context) {
        String base = getBaseUrl(context);
        return base == null ? null : base + "/stream";
    }

    /**
     * Intent for the live view, with the Pi stream attached when one is paired.
     * LiveObjectDetectionActivity picks its mode from the presence of "stream_url":
     * with it, the Pi feed; without it, the phone's own camera. Every entry point
     * should build the intent here so that choice is made the same way everywhere.
     */
    public static Intent streamIntent(Context context) {
        Intent intent = new Intent(context, LiveObjectDetectionActivity.class);
        String streamUrl = getStreamUrl(context);
        if (streamUrl != null) {
            intent.putExtra("stream_url", streamUrl);
        }
        return intent;
    }

}