package com.example.pettrackerapplication;

import android.content.Context;

import androidx.annotation.StringDef;
import androidx.appcompat.app.AppCompatDelegate;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Light, Dark and AMOLED themes, chosen by the user. */
public final class ThemeManager {

    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    public static final String AMOLED = "amoled";

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({LIGHT, DARK, AMOLED})
    public @interface Mode {}

    private ThemeManager() {
    }

    @Mode
    public static String current(Context context) {
        String mode = Prefs.get(context).getString(Prefs.KEY_THEME_MODE, LIGHT);
        if (DARK.equals(mode) || AMOLED.equals(mode)) return mode;
        return LIGHT;
    }

    public static void apply(Context context, @Mode String mode) {
        Prefs.get(context).edit().putString(Prefs.KEY_THEME_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(nightModeFor(mode));
    }

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
