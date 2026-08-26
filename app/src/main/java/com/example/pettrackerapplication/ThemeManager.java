package com.example.pettrackerapplication;

import android.content.Context;

import androidx.annotation.StringDef;
import androidx.appcompat.app.AppCompatDelegate;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Light / Dark / AMOLED, chosen by the user rather than the system.
 *
 * Two mechanisms, because they do different jobs:
 *
 * <ul>
 *   <li>{@link AppCompatDelegate#setDefaultNightMode} decides whether
 *       {@code values/} or {@code values-night/} resources load. That covers
 *       Light and Dark on its own.</li>
 *   <li>AMOLED is not a third resource set — it is Dark with the greys pushed
 *       to true black. It rides on the night resources and adds a theme overlay
 *       on top, which is why activities have to apply it themselves before
 *       their content view inflates.</li>
 * </ul>
 *
 * Every activity extends {@link ThemedActivity}, which does that for them.
 */
public final class ThemeManager {

    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    public static final String AMOLED = "amoled";

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({LIGHT, DARK, AMOLED})
    public @interface Mode {}

    private ThemeManager() {
        // static helper, no instances
    }

    @Mode
    public static String current(Context context) {
        String mode = Prefs.get(context).getString(Prefs.KEY_THEME_MODE, LIGHT);
        // A value from a future version, or a corrupted one, must not leave the
        // app themeless.
        if (DARK.equals(mode) || AMOLED.equals(mode)) return mode;
        return LIGHT;
    }

    /**
     * Stores the choice and applies it process-wide.
     *
     * {@code setDefaultNightMode} recreates every started activity, so the
     * screen the user is looking at redraws in the new theme without any extra
     * work. AMOLED needs no recreate of its own — it is a night mode too, and
     * the overlay is picked up as each activity is rebuilt.
     */
    public static void apply(Context context, @Mode String mode) {
        Prefs.get(context).edit().putString(Prefs.KEY_THEME_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(nightModeFor(mode));
    }

    /** Applies the stored choice. Call once, from {@link PetTrackerApp}. */
    public static void applyStored(Context context) {
        AppCompatDelegate.setDefaultNightMode(nightModeFor(current(context)));
    }

    private static int nightModeFor(@Mode String mode) {
        return LIGHT.equals(mode)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_YES;
    }

    public static boolean isAmoled(Context context) {
        return AMOLED.equals(current(context));
    }
}
