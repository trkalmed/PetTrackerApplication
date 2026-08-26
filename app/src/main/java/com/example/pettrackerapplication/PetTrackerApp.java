package com.example.pettrackerapplication;

import android.app.Application;

/**
 * Applies the saved theme once, before any activity starts.
 *
 * Without this the app would open in the default light theme and then flip to
 * the user's choice as the first activity applied it — a visible flash on every
 * cold start.
 */
public class PetTrackerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.applyStored(this);
    }
}
