package com.example.pettrackerapplication;

import android.content.Context;
import android.content.SharedPreferences;

/** The app's single SharedPreferences file. */
public final class Prefs {

    private static final String FILE     = "pet_tracker_prefs";
    private static final String OLD_FILE = "app_settings";

    public static final String KEY_SOUND_ENABLED = "sound_enabled";
    public static final String KEY_SOUND_VOLUME  = "sound_volume";

    public static final String KEY_PI_IP = "pi_ip_address";

    public static final String KEY_PI_CURSOR      = "pi_event_cursor";
    public static final String KEY_PI_CURSOR_HOST = "pi_event_cursor_host";

    public static final String KEY_PI_LAST_SYNC = "pi_last_sync_millis";

    public static final String KEY_PI_CAMERA_OK = "pi_camera_available";
    public static final String KEY_PI_HEALTH_AT = "pi_health_millis";

    public static final String KEY_THEME_MODE = "theme_mode";

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

    public static Pet chooseActive(Context context, java.util.List<Pet> pets) {
        if (pets == null || pets.isEmpty()) return null;

        long activeId = getActivePetId(context);
        for (Pet pet : pets) {
            if (pet.getId() == activeId) return pet;
        }

        Pet first = pets.get(0);
        setActivePetId(context, first.getId());
        return first;
    }

    private static final String KEY_MIGRATED = "migrated_from_app_settings";

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            migrate(app, prefs);
        }
        return prefs;
    }

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
