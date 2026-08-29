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

    /**
     * How far through the Pi's event log this device has read.
     *
     * Stored next to the host it belongs to: the cursor is a row id in one
     * particular Pi's database, so it means nothing after the app is pointed at
     * a different machine. See {@link PiEventSync} for what happens then.
     */
    public static final String KEY_PI_CURSOR      = "pi_event_cursor";
    public static final String KEY_PI_CURSOR_HOST = "pi_event_cursor_host";

    /** When the last successful sync finished, epoch millis. 0 = never. */
    public static final String KEY_PI_LAST_SYNC = "pi_last_sync_millis";

    /**
     * What the Pi last said about its own camera, and when it said it.
     *
     * The dashboard used to decide "camera live" from whether the phone's own
     * detector had seen an animal recently, which is only ever true while
     * somebody is watching the Live screen. The Pi answers the actual question
     * on every /health, so the answer is kept here for the dashboard to read.
     */
    public static final String KEY_PI_CAMERA_OK = "pi_camera_available";
    public static final String KEY_PI_HEALTH_AT = "pi_health_millis";

    /** Light / Dark / AMOLED. See ThemeManager. */
    public static final String KEY_THEME_MODE = "theme_mode";

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

    /**
     * The pet the app is tracking, out of the ones this owner has.
     *
     * The stored id goes stale whenever that pet is deleted or the account
     * changes, so this falls back to the first pet and writes the choice back
     * rather than leaving a screen empty next to a non-empty list.
     *
     * Every screen that shows "the pet" has to agree on which one it is, and now
     * more than before: {@link PiSync} files the Pi's readings against this id,
     * so a screen picking differently would show someone else's meals. That is
     * why the rule lives here and not in three activities.
     *
     * @return the chosen pet, or null if the owner has none
     */
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
