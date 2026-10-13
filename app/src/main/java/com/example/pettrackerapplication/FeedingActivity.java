package com.example.pettrackerapplication;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.IOException;

import java.util.List;

/** The Food tab. Every figure on it is grams the load cell weighed. */
public class FeedingActivity extends ThemedActivity {

    private static final int DEFAULT_FEED_GRAMS = 40;

    private static final int MAX_FEED_GRAMS = 500;

    private static final double EMPTY_BAND_G = 2.0;

    private static final int MAX_DAILY_GRAMS = 3000;

    private TextView     tvTodayGrams, tvTodayMealCount, tagTodayState, tvFeedingSubtitle;
    private MaterialButton btnFeedNow, btnDailyTarget;
    private TextView       tvBowlGrams, tvBowlState;

    private volatile PiHealth lastHealth;
    private TextView       tvFeedNowState;
    private ProgressBar  barToday;
    private LinearLayout mealList, emptyFeeder;
    private BottomNavigationView nav;

    private PiSync.Poller piPoller;

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

        btnFeedNow     = findViewById(R.id.btnFeedNow);
        tvFeedNowState = findViewById(R.id.tvFeedNowState);
        tvBowlGrams    = findViewById(R.id.tvBowlGrams);
        tvBowlState    = findViewById(R.id.tvBowlState);
        btnDailyTarget = findViewById(R.id.btnDailyTarget);
        btnFeedNow.setOnClickListener(v -> confirmDispense());

        setupBottomNavigation();
        piPoller = new PiSync.Poller(this, this::onSynced);
    }

    @Override
    protected void onResume() {
        super.onResume();
        nav.setSelectedItemId(R.id.nav_food);
        NavTint.apply(this, nav, R.id.nav_food);
        load();
        bowlPoll.removeCallbacks(bowlTick);
        bowlPoll.post(bowlTick);
        piPoller.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bowlWorker.shutdownNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        bowlPoll.removeCallbacks(bowlTick);
        piPoller.stop();
    }

    private void onSynced(PiEventSync.Result result) {
        if (isFinishing() || isDestroyed()) return;
        if (result.storedAnything()) load();
    }

    private static final long BOWL_POLL_MS = 2000;

    private final android.os.Handler bowlPoll =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final java.util.concurrent.atomic.AtomicBoolean bowlInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final java.util.concurrent.ExecutorService bowlWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private final Runnable bowlTick = new Runnable() {
        @Override
        public void run() {
            refreshFeederAvailability();
            bowlPoll.postDelayed(this, BOWL_POLL_MS);
        }
    };

    private void refreshFeederAvailability() {
        PiClient client = PiClient.forSavedPi(this);
        if (client == null) {
            btnFeedNow.setEnabled(false);
            tvFeedNowState.setText(R.string.feeder_no_pi);
            tvBowlGrams.setText(R.string.em_dash);
            tvBowlState.setText(R.string.bowl_no_pi);
            return;
        }

        if (!bowlInFlight.compareAndSet(false, true)) return;

        bowlWorker.execute(() -> {
            PiHealth health = null;
            try {
                health = client.health();
            } catch (IOException ignored) {
            }
            final PiHealth answer = health;
            bowlInFlight.set(false);
            lastHealth = answer;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                boolean ready = answer != null && answer.feederAvailable;
                btnFeedNow.setEnabled(ready);

                boolean holds = answer != null && answer.feederHoldsOpen();
                boolean open = answer != null && answer.feederOpen;

                btnFeedNow.setText(open ? R.string.feeder_close
                        : holds ? R.string.feeder_open : R.string.feed_now);
                tvFeedNowState.setText(!ready ? getString(R.string.feeder_unavailable)
                        : open ? getString(R.string.feeder_open_now)
                               : getString(R.string.feeder_ready));

                renderBowl(answer);
            });
        });
    }

    private void renderBowl(PiHealth health) {
        if (health == null || !health.scaleAvailable
                || health.latestWeightGrams == null) {
            tvBowlGrams.setText(R.string.em_dash);
            tvBowlState.setText(R.string.bowl_no_scale);
            return;
        }

        double grams = health.latestWeightGrams;
        long rounded = Math.round(grams);
        if (rounded < 0 && grams > -EMPTY_BAND_G) rounded = 0;

        tvBowlGrams.setText(getString(R.string.grams_value, (int) rounded));
        tvBowlState.setText(Math.abs(grams) < EMPTY_BAND_G
                ? R.string.bowl_empty_note : R.string.bowl_live);
    }

    private void confirmDispense() {
        if (lastHealth != null && lastHealth.feederOpen) {
            closeFeeder();
            return;
        }

        boolean holds = lastHealth != null && lastHealth.feederHoldsOpen();
        new MaterialAlertDialogBuilder(this)
                .setTitle(holds ? R.string.feed_confirm_title_hold
                                : R.string.feed_confirm_title)
                .setMessage(holds ? R.string.feed_confirm_message_hold
                                  : R.string.feed_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.feed_action, (d, which) -> dispense(portionGrams()))
                .show();
    }

    private void askDailyTarget(int current) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setSelectAllOnFocus(true);

        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(this);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.daily_target_title)
                .setMessage(R.string.daily_target_message)
                .setView(wrapper)
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.daily_target_reset,
                        (d, which) -> saveDailyTarget(null))
                .setPositiveButton(R.string.action_save, (d, which) -> {
                    int grams;
                    try {
                        grams = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        grams = -1;
                    }
                    if (grams < 1 || grams > MAX_DAILY_GRAMS) {
                        Toast.makeText(this, getString(R.string.daily_target_invalid,
                                MAX_DAILY_GRAMS), Toast.LENGTH_LONG).show();
                        return;
                    }
                    saveDailyTarget(grams);
                })
                .show();
    }

    private void saveDailyTarget(Integer grams) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String ownerId = user.getUid();
        long petId = Prefs.getActivePetId(this);
        if (petId <= 0) return;

        new Thread(() -> {
            new PetDataRepository(getApplicationContext())
                    .setDailyFoodGrams(petId, ownerId, grams);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                load();
            });
        }).start();
    }

    private void closeFeeder() {
        PiClient client = PiClient.forSavedPi(this);
        if (client == null) return;

        btnFeedNow.setEnabled(false);
        tvFeedNowState.setText(R.string.feeder_closing);

        bowlWorker.execute(() -> {
            String failure = null;
            try {
                client.closeFeeder();
            } catch (IOException e) {
                String message = e.getMessage();
                failure = message == null || message.isEmpty()
                        ? e.getClass().getSimpleName() : message;
            }
            final String outcome = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnFeedNow.setEnabled(true);
                if (outcome != null) {
                    Toast.makeText(this, getString(R.string.feed_failed, outcome),
                            Toast.LENGTH_LONG).show();
                }
                refreshFeederAvailability();
            });
        });
    }

    private int portionGrams() {
        Double portion = lastHealth == null ? null : lastHealth.portionGrams;
        if (portion == null || portion < 1 || portion > MAX_FEED_GRAMS) {
            return DEFAULT_FEED_GRAMS;
        }
        return (int) Math.round((double) portion);
    }

    private void dispense(int grams) {
        PiClient client = PiClient.forSavedPi(this);
        if (client == null) {
            Toast.makeText(this, R.string.feeder_no_pi, Toast.LENGTH_LONG).show();
            return;
        }

        btnFeedNow.setEnabled(false);
        tvFeedNowState.setText(R.string.feed_dispensing);

        new Thread(() -> {
            String failure = null;
            try {
                client.feed(grams);
            } catch (IOException e) {
                String message = e.getMessage();
                failure = message == null || message.isEmpty()
                        ? e.getClass().getSimpleName() : message;
            }
            String outcome = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnFeedNow.setEnabled(true);
                if (outcome == null) {
                    tvFeedNowState.setText(R.string.feeder_ready);
                    Toast.makeText(this, R.string.feed_sent, Toast.LENGTH_LONG).show();
                    PiSync.requestSync(this, this::onSynced);
                } else {
                    tvFeedNowState.setText(R.string.feeder_unavailable);
                    Toast.makeText(this, getString(R.string.feed_failed, outcome),
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void load() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        String ownerId = user.getUid();

        new Thread(() -> {
            List<Pet> pets = AppDatabase
                    .getInstance(getApplicationContext())
                    .petDao()
                    .getPetsForOwner(ownerId);

            Pet pet = Prefs.chooseActive(getApplicationContext(), pets);
            DashboardStats stats = pet == null
                    ? new DashboardStats()
                    : DashboardStats.forPetToday(getApplicationContext(), pet);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                render(stats);
            });
        }).start();
    }

    private void render(DashboardStats s) {
        mealList.removeAllViews();

        btnDailyTarget.setText(getString(R.string.daily_target_button, s.gramsTarget));
        btnDailyTarget.setOnClickListener(v -> askDailyTarget(s.gramsTarget));

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
        NavTint.apply(this, nav, R.id.nav_food);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            NavTint.apply(this, nav, id);
            if (id == R.id.nav_food) return true;
            if (id == R.id.nav_home) {
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
