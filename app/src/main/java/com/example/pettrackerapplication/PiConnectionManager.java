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

    private PiConnectionManager() {
        // static helper, no instances
    }

    public static String getSavedIp(Context context) {
        return Prefs.get(context).getString(Prefs.KEY_PI_IP, "");
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

    /** Returns the full stream URL, or null if no IP has been saved yet. */
    public static String getStreamUrl(Context context) {
        String ip = getSavedIp(context);
        if (ip.isEmpty()) return null;
        return "http://" + ip + ":" + STREAM_PORT + "/stream";
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