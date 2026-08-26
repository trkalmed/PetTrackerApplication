package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.card.MaterialCardView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The Appearance picker: persistence, night-mode mapping, and that the card
 * actually inflates.
 *
 * Driven through {@link ActivityScenario} rather than by tapping, because the
 * Settings screen sits behind a login and the activity is not exported — but
 * the theme logic is worth covering either way, and a test states the intended
 * mapping more precisely than a click does.
 */
@RunWith(AndroidJUnit4.class)
public class ThemeTest {

    private Context context;
    private String original;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        original = ThemeManager.current(context);
    }

    @After
    public void tearDown() {
        ThemeManager.apply(context, original);
    }

    // ── ThemeManager ────────────────────────────────────────────────────────

    @Test
    public void theDefaultIsLight() {
        Prefs.get(context).edit().remove(Prefs.KEY_THEME_MODE).apply();

        assertEquals(ThemeManager.LIGHT, ThemeManager.current(context));
    }

    @Test
    public void aChoiceIsRemembered() {
        ThemeManager.apply(context, ThemeManager.DARK);

        assertEquals(ThemeManager.DARK, ThemeManager.current(context));
    }

    @Test
    public void darkAndAmoledBothMapToNightMode() {
        // AMOLED is not a third resource set: it rides on values-night and adds
        // a theme overlay. If it stopped requesting night mode it would render
        // black text on a black ground.
        ThemeManager.apply(context, ThemeManager.DARK);
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode());

        ThemeManager.apply(context, ThemeManager.AMOLED);
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode());
    }

    @Test
    public void lightMapsToDayMode() {
        ThemeManager.apply(context, ThemeManager.LIGHT);

        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode());
    }

    @Test
    public void onlyAmoledReportsAmoled() {
        ThemeManager.apply(context, ThemeManager.DARK);
        assertFalse("dark must not pull in the AMOLED overlay",
                ThemeManager.isAmoled(context));

        ThemeManager.apply(context, ThemeManager.AMOLED);
        assertTrue(ThemeManager.isAmoled(context));
    }

    @Test
    public void anUnknownStoredValueFallsBackToLight() {
        // A value written by a future version, or a corrupted one, must not
        // leave the app themeless.
        Prefs.get(context).edit().putString(Prefs.KEY_THEME_MODE, "solarized").apply();

        assertEquals(ThemeManager.LIGHT, ThemeManager.current(context));
    }

    // ── the card itself ─────────────────────────────────────────────────────

    @Test
    public void theAppearanceCardInflatesWithAllThreeTiles() {
        ThemeManager.apply(context, ThemeManager.LIGHT);

        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull("Appearance card missing",
                        activity.findViewById(R.id.cardAppearance));
                for (int id : new int[]{R.id.themeLight, R.id.themeDark, R.id.themeAmoled}) {
                    View tile = activity.findViewById(id);
                    assertNotNull("tile missing", tile);
                    assertTrue("tile must be tappable", tile.isClickable());
                }
            });
        }
    }

    @Test
    public void theSelectedTileIsTheOneMarked() {
        ThemeManager.apply(context, ThemeManager.LIGHT);

        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                MaterialCardView light = activity.findViewById(R.id.tileLight);
                MaterialCardView dark = activity.findViewById(R.id.tileDark);

                // The selected tile carries a thicker ring; that is the only
                // signal telling the user which theme is live.
                assertTrue("the active tile should be ringed more heavily",
                        light.getStrokeWidth() > dark.getStrokeWidth());
            });
        }
    }
}
