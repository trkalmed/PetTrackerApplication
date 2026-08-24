package com.example.pettrackerapplication;

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;

/** The Food tab. Everything on it is grams the feeder actually weighed. */
public class FeedingActivity extends AppCompatActivity {

    private TextView     tvTodayGrams, tvTodayMealCount, tagTodayState, tvFeedingSubtitle;
    private ProgressBar  barToday;
    private LinearLayout mealList, emptyFeeder;
    private BottomNavigationView nav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feeding);

        tvTodayGrams      = findViewById(R.id.tvTodayGrams);
        tvTodayMealCount  = findViewById(R.id.tvTodayMealCount);
        tagTodayState     = findViewById(R.id.tagTodayState);
        tvFeedingSubtitle = findViewById(R.id.tvFeedingSubtitle);
        barToday          = findViewById(R.id.barToday);
        mealList          = findViewById(R.id.mealList);
        emptyFeeder       = findViewById(R.id.emptyFeeder);

        findViewById(R.id.btnPairFeeder).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        setupBottomNavigation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-assert Food after returning from another tab. Its listener branch is a no-op.
        nav.setSelectedItemId(R.id.nav_food);
        load();
    }

    private void load() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            // Signed out while this screen was open — the dashboard owns the redirect.
            finish();
            return;
        }
        String ownerId = user.getUid();

        new Thread(() -> {
            List<Pet> pets = AppDatabase
                    .getInstance(getApplicationContext())
                    .petDao()
                    .getPetsForOwner(ownerId);

            DashboardStats stats = (pets == null || pets.isEmpty())
                    ? new DashboardStats()
                    : DashboardStats.forPetToday(getApplicationContext(), pets.get(0));

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                render(stats);
            });
        }).start();
    }

    private void render(DashboardStats s) {
        mealList.removeAllViews();

        if (s.gramsEaten == null) {
            emptyFeeder.setVisibility(View.VISIBLE);
            mealList.setVisibility(View.GONE);
            tvTodayGrams.setText(R.string.no_data);
            tvTodayMealCount.setText(R.string.feeder_not_reporting);
            tagTodayState.setText(R.string.waiting);
            tvFeedingSubtitle.setText(R.string.no_feeder_paired);
            barToday.setProgress(0);
            return;
        }

        emptyFeeder.setVisibility(View.GONE);
        mealList.setVisibility(View.VISIBLE);

        int pct = s.gramsTarget == 0 ? 0
                : Math.min(100, Math.round(s.gramsEaten * 100f / s.gramsTarget));
        barToday.setProgress(pct);
        tvTodayGrams.setText(getString(R.string.grams_of_target,
                s.gramsEaten, s.gramsTarget));
        tvTodayMealCount.setText(getResources().getQuantityString(
                R.plurals.meal_count, s.meals.size(), s.meals.size()));
        tagTodayState.setText(pct >= 100 ? R.string.target_met : R.string.on_track);
        tvFeedingSubtitle.setText(R.string.grams_measured_by_the_feeder);

        for (int i = 0; i < s.meals.size(); i++) {
            mealList.addView(buildMealRow(s.meals.get(i), i > 0));
        }
    }

    private View buildMealRow(DashboardStats.Meal meal, boolean divided) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(divided ? 14 : 0), 0, dp(14));

        TextView time = new TextView(this);
        time.setText(meal.time);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        time.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        time.setLayoutParams(new LinearLayout.LayoutParams(dp(52),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(time);

        TextView grams = new TextView(this);
        grams.setText(getString(R.string.grams_value, meal.grams));
        grams.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        grams.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        grams.setTypeface(ResourcesCompat.getFont(this, R.font.poppins_semibold));
        grams.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(grams);

        return row;
    }

    private void setupBottomNavigation() {
        nav = findViewById(R.id.bottomNav);
        nav.setSelectedItemId(R.id.nav_food);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_food) return true;
            if (id == R.id.nav_home) {
                // An explicit intent, not finish() — this screen is not always
                // launched from the dashboard, so finish() cannot be relied on
                // to land there. CLEAR_TOP reuses the existing dashboard.
                Intent intent = new Intent(this, MainMenuActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                return true;
            }
            if (id == R.id.nav_live) {
                startActivity(PiConnectionManager.streamIntent(this));
                return true;
            }
            if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
