package com.example.pettrackerapplication;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Base activity that applies the AMOLED overlay before anything inflates.
 *
 * Light and Dark need nothing here — {@code setDefaultNightMode} already
 * decides which resource set loads. AMOLED is the exception: it is a theme
 * overlay on top of the night resources, and a theme can only be changed before
 * the first view is created. Doing it in each activity by hand is the kind of
 * thing that gets forgotten on the next screen someone adds, so it lives in one
 * place that every screen extends.
 */
public abstract class ThemedActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (ThemeManager.isAmoled(this)) {
            getTheme().applyStyle(R.style.ThemeOverlay_PetTracker_Amoled, true);
        }
        super.onCreate(savedInstanceState);
    }
}
