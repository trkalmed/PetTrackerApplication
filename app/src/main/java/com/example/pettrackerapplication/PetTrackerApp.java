package com.example.pettrackerapplication;

import android.app.Application;

/** Applies the saved theme before any activity starts. */
public class PetTrackerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.applyStored(this);
    }
}
