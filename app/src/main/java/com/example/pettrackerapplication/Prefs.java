package com.example.pettrackerapplication;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The app's one SharedPreferences file.
 *
 * There used to be two — "pet_tracker_prefs" for the Pi address and "app_settings"
 * for the sound toggle — which worked only because nothing needed to read across
 * them. {@link #get} is now the single entry point; values written to the old
 * settings file are carried over once so the change is invisible to anyone
 * upgrading.
 */
public final class Prefs {

    private static final String FILE     = "pet_tracker_prefs";
    private static final String OLD_FILE = "app_settings";

    // Sound (was in OLD_FILE)
    public static final String KEY_SOUND_ENABLED = "sound_enabled";
    public static final String KEY_SOUND_VOLUME  = "sound_volume";

    // Pi connection
    public static final String KEY_PI_IP = "pi_ip_address";

    /** Which pet the dashboard tracks. -1 = none chosen, fall back to the first. */
    public static final String KEY_ACTIVE_PET_ID = "active_pet_id";

    public static long getActivePetId(Context context) {
        return get(context).getLong(KEY_ACTIVE_PET_ID, -1L);
    }

    public static void setActivePetId(Context context, long petId) {
        get(context).edit().putLong(KEY_ACTIVE_PET_ID, petId).apply();
    }

    public static void clearActivePetId(Context context) {
        get(context).edit().remove(KEY_ACTIVE_PET_ID).apply();
    }

    private static final String KEY_MIGRATED = "migrated_from_app_settings";

    private Prefs() {
        // static helper, no instances
    }

    public static SharedPreferences get(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            migrate(app, prefs);
        }
        return prefs;
    }

    /** Copies the two sound values out of the retired file, once. */
    private static void migrate(Context app, SharedPreferences prefs) {
        SharedPreferences old = app.getSharedPreferences(OLD_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (old.contains(KEY_SOUND_ENABLED)) {
            editor.putBoolean(KEY_SOUND_ENABLED, old.getBoolean(KEY_SOUND_ENABLED, true));
        }
        if (old.contains(KEY_SOUND_VOLUME)) {
            editor.putInt(KEY_SOUND_VOLUME, old.getInt(KEY_SOUND_VOLUME, 70));
        }

        editor.putBoolean(KEY_MIGRATED, true).apply();
        old.edit().clear().apply();
    }
}
