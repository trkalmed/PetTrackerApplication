package com.example.pettrackerapplication;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders layouts straight to PNG without signing in.
 *
 * Every screen worth looking at sits behind Firebase auth, which makes the
 * theme awkward to check on a device. Settings has no auth guard, so it hosts
 * the inflation: its LayoutInflater carries a real, fully themed activity
 * context, which Material widgets require — a plain ContextThemeWrapper over
 * the application context is not enough, and CircularProgressIndicator refuses
 * to inflate from one.
 *
 * Files land in the app's external files dir, ready to pull with adb.
 */
@RunWith(AndroidJUnit4.class)
public class LayoutRenderTest {

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2400;

    @After
    public void tearDown() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }

    private void render(int layoutRes, String name, boolean night) {
        AppCompatDelegate.setDefaultNightMode(night
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);

        AtomicReference<Boolean> ok = new AtomicReference<>(false);

        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> ok.set(draw(activity, layoutRes, name, night)));
        }

        assertTrue("nothing written for " + name, ok.get());
    }

    private boolean draw(Activity activity, int layoutRes, String name, boolean night) {
        try {
            View root = activity.getLayoutInflater().inflate(layoutRes, null, false);
            populate(root);

            root.measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, WIDTH, HEIGHT);

            Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));

            File dir = ApplicationProvider.getApplicationContext().getExternalFilesDir(null);
            File out = new File(dir, name + (night ? "_dark" : "_light") + ".png");
            try (FileOutputStream stream = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            return out.length() > 0;
        } catch (Exception e) {
            throw new AssertionError("could not render " + name, e);
        }
    }

    /**
     * Fills in a few representative values.
     *
     * Inflating outside an activity means nothing binds, and tools:text is a
     * design-time attribute the Layout Editor honours but the runtime ignores —
     * so an untouched render is all blanks and an empty ring, which says
     * nothing about whether the design is right.
     */
    private void populate(View root) {
        text(root, R.id.tvPetName, "Marvel");
        text(root, R.id.tvPetSubtitle, "Golden retriever · 4 yrs");
        text(root, R.id.tvCameraStatus, "Kitchen camera live");
        text(root, R.id.tvGpsStatus, "GPS 2 min ago");
        text(root, R.id.tvGramsEaten, "185 g");
        text(root, R.id.tvGramsTarget, "of 300 g");
        text(root, R.id.tvCurrentRoom, "Living room");
        text(root, R.id.tvCurrentRoomSince, "since 14:20");
        text(root, R.id.tvDetectionCount, "28 today");
        text(root, R.id.tvDetectionLast, "last 15:02");

        View ring = root.findViewById(R.id.foodProgress);
        if (ring instanceof RampRing) {
            ((RampRing) ring).setProgress(62, false);
        }
    }

    private void text(View root, int id, String value) {
        View view = root.findViewById(id);
        if (view instanceof android.widget.TextView) {
            ((android.widget.TextView) view).setText(value);
        }
    }

    @Test
    public void renderDashboard() {
        render(R.layout.activity_main_menu, "dashboard", false);
        render(R.layout.activity_main_menu, "dashboard", true);
    }

    @Test
    public void renderSettings() {
        render(R.layout.activity_settings, "settings", false);
    }

    @Test
    public void renderAccount() {
        render(R.layout.activity_account, "account", false);
    }

    @Test
    public void renderPetDetail() {
        render(R.layout.pet_menu, "petdetail", false);
    }
}
