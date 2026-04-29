package com.example.pettrackerapplication;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Single source of truth for the Raspberry Pi's saved IP address.
 * Used by Petmenu (Tracking button) and SettingsActivity (Pi Connection
 * preference) so both screens always stay in sync.
 */
public class PiConnectionManager {

    private static final String PREFS_NAME  = "pet_tracker_prefs";
    private static final String KEY_PI_IP   = "pi_ip_address";
    private static final int    STREAM_PORT = 8080;

    private PiConnectionManager() {
        // static helper, no instances
    }

    public static String getSavedIp(Context context) {
        return prefs(context).getString(KEY_PI_IP, "");
    }

    public static boolean hasSavedIp(Context context) {
        return !getSavedIp(context).isEmpty();
    }

    public static void saveIp(Context context, String ip) {
        prefs(context).edit().putString(KEY_PI_IP, ip.trim()).apply();
    }

    public static void clearIp(Context context) {
        prefs(context).edit().remove(KEY_PI_IP).apply();
    }

    /** Returns the full stream URL, or null if no IP has been saved yet. */
    public static String getStreamUrl(Context context) {
        String ip = getSavedIp(context);
        if (ip.isEmpty()) return null;
        return "http://" + ip + ":" + STREAM_PORT + "/stream";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}