package com.example.pettrackerapplication;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
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

/** The Food tab. Everything on it is grams the feeder actually weighed. */
public class FeedingActivity extends ThemedActivity {

    /**
     * Grams the last dispense asked for, so the next one opens on the same
     * number. A household feeds the same portion nearly every time, and making
     * someone retype it is the sort of small friction that gets a feature
     * called broken.
     */
    private static final String KEY_LAST_FEED_GRAMS = "last_feed_grams";
    private static final int DEFAULT_FEED_GRAMS = 40;

    /** The Pi's own cap, mirrored so the phone can refuse before the network. */
    private static final int MAX_FEED_GRAMS = 500;

    /**
     * Below this the bowl is called empty.
     *
     * A tared load cell wanders either side of zero by a fraction of a gram
     * as it settles, and this rig's meal detector already ignores changes
     * under 5 g. Reporting "-0.4 g" would be precise about noise.
     */
    private static final double EMPTY_BAND_G = 2.0;

    private TextView     tvTodayGrams, tvTodayMealCount, tagTodayState, tvFeedingSubtitle;
    private MaterialButton btnFeedNow;
    private TextView       tvBowlGrams, tvBowlState;
    private TextView       tvFeedNowState;
    private ProgressBar  barToday;
    private LinearLayout mealList, emptyFeeder;
    private BottomNavigationView nav;

    /** Collects readings from the Pi while this screen is in front of the user. */
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
        btnFeedNow.setOnClickListener(v -> askHowMuch());

        setupBottomNavigation();
        piPoller = new PiSync.Poller(this, this::onSynced);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-assert Food after returning from another tab. Its listener branch is a no-op.
        nav.setSelectedItemId(R.id.nav_food);
        NavTint.apply(this, nav, R.id.nav_food);
        load();
        bowlPoll.removeCallbacks(bowlTick);
        bowlPoll.post(bowlTick);
        piPoller.start();
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

    // ── Feeding on demand ───────────────────────────────────────────────────

    /**
     * How often the bowl reading is re-read while this screen is open.
     *
     * Two seconds, not the event poller's fifteen. That poller collects history
     * and fifteen is generous for it, but this number is meant to be watched:
     * food lands, the weight climbs, the figure follows. At fifteen seconds it
     * would look broken. /health is a few hundred bytes and the Pi answers it
     * from memory, so the cost is nothing.
     *
     * Stops on pause, like the event poller and for the same reason — nothing
     * in this app polls a device when nobody is looking at the result.
     */
    private static final long BOWL_POLL_MS = 2000;

    private final android.os.Handler bowlPoll =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** One health request at a time, so a slow network cannot stack them up. */
    private final java.util.concurrent.atomic.AtomicBoolean bowlInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Runnable bowlTick = new Runnable() {
        @Override
        public void run() {
            refreshFeederAvailability();
            bowlPoll.postDelayed(this, BOWL_POLL_MS);
        }
    };

    /**
     * Reads /health: whether the feeder can be offered, and what the bowl weighs.
     *
     * The button starts disabled and only opens once /health says a feeder was
     * claimed. A button that is always tappable and fails at the far end blames
     * the user for the state of the hardware; this way the screen says what is
     * wrong before anything is pressed.
     *
     * "Claimed" is as much as the Pi can honestly answer — a hobby servo has no
     * feedback wire, so nothing on either side knows whether the wheel turned.
     * That is what the load cell is for, and the caption says so.
     */
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

        new Thread(() -> {
            PiHealth health = null;
            try {
                health = client.health();
            } catch (IOException ignored) {
                // Unreachable Pi. Rendered below as "not reporting", which is
                // true and is what the person can act on.
            }
            final PiHealth answer = health;
            bowlInFlight.set(false);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                boolean ready = answer != null && answer.feederAvailable;
                btnFeedNow.setEnabled(ready);
                tvFeedNowState.setText(ready
                        ? R.string.feeder_ready : R.string.feeder_unavailable);

                renderBowl(answer);
            });
        }).start();
    }

    /**
     * What the load cell reads at this moment.
     *
     * Different in kind from everything else on this screen. The meal figures
     * are history: the Pi's detector decided a weight change was a meal, the
     * phone stored it, and it stays true. This is the bowl right now, read
     * straight from /health and never written down — so it updates while you
     * watch, and it is the only thing here that shows the hardware working
     * live rather than showing what it once recorded.
     *
     * Small negatives are shown as empty. A tared load cell drifts either side
     * of zero by a fraction of a gram, and "-0.4 g of food" is not a sentence
     * about anything.
     */
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

    private void askHowMuch() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(Prefs.get(this)
                .getInt(KEY_LAST_FEED_GRAMS, DEFAULT_FEED_GRAMS)));
        input.setSelectAllOnFocus(true);

        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.feed_dialog_title)
                .setMessage(R.string.feed_dialog_message)
                .setView(wrapper)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.feed_action, (d, which) -> {
                    int grams;
                    try {
                        grams = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        grams = -1;
                    }
                    if (grams < 1 || grams > MAX_FEED_GRAMS) {
                        Toast.makeText(this, R.string.feed_grams_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Prefs.get(this).edit().putInt(KEY_LAST_FEED_GRAMS, grams).apply();
                    dispense(grams);
                })
                .show();
    }

    /**
     * Runs the feeder and says only what is true afterwards.
     *
     * Nothing is written to the database here. The Pi does not record a feeding
     * event for a dispense either, and for the same reason: the request says how
     * much was asked for, and only the load cell knows what fell. The meal turns
     * up on this screen a few seconds later, through the ordinary poll, having
     * been weighed. Recording the request would double-count it and would put a
     * number on the dashboard that nothing verified.
     */
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
                    // The load cell needs a moment to settle before the Pi's
                    // detector will call it a meal, so this poll usually finds
                    // nothing; the screen's own poller picks it up shortly after.
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

            // The same pet the dashboard shows and the same pet the Pi's readings
            // are filed against — this screen used to take pets.get(0), which is
            // a different pet as soon as the owner has two.
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
