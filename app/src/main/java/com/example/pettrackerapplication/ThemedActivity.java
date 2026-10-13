package com.example.pettrackerapplication;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Base activity that applies the AMOLED overlay before anything inflates. */
public abstract class ThemedActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (ThemeManager.isAmoled(this)) {
            getTheme().applyStyle(R.style.ThemeOverlay_PetTracker_Amoled, true);
        }
        super.onCreate(savedInstanceState);
    }
}
