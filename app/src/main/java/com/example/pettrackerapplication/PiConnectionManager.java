package com.example.pettrackerapplication;

import android.content.Context;
import android.content.Intent;

/** Stores the Raspberry Pi's saved IP address. */
public class PiConnectionManager {

    private static final int STREAM_PORT = 8080;

    public static final String DEFAULT_PI_IP = "192.168.100.19";

    private PiConnectionManager() {
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

    public static String getBaseUrl(Context context) {
        String ip = getSavedIp(context);
        if (ip.isEmpty()) return null;
        return "http://" + ip + ":" + STREAM_PORT;
    }

    public static String getStreamUrl(Context context) {
        String base = getBaseUrl(context);
        return base == null ? null : base + "/stream";
    }

    public static Intent streamIntent(Context context) {
        Intent intent = new Intent(context, LiveObjectDetectionActivity.class);
        String streamUrl = getStreamUrl(context);
        if (streamUrl != null) {
            intent.putExtra("stream_url", streamUrl);
        }
        return intent;
    }

}