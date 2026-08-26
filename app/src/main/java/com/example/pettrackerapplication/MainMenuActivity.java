package com.example.pettrackerapplication;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.bumptech.glide.Glide;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;

/**
 * Dashboard. Three changes from the previous version:
 *  1. Every metric is one the hardware actually produces. Steps, distance and the
 *     100-minute activity goal are gone; grams, room minutes, detections and the
 *     GPS fix took their place.
 *  2. Missing data shows a setup prompt, never a permanent "—".
 *  3. Bottom nav is four destinations (Home / Live / Food / Settings). "Add pet"
 *     is a create action and moved to the FAB.
 */
public class MainMenuActivity extends ThemedActivity {

    private ImageView imgPetAvatar;
    private TextView  tvPetName, tvPetSubtitle;

    private View     dotDeviceStatus;
    private TextView tvCameraStatus, tvGpsStatus;

    private RampRing foodProgress;
    private TextView     tvGramsEaten, tvGramsTarget, tvFoodEmpty;
    private LinearLayout mealsContainer;

    private LinearLayout roomBar, roomLegend;
    private TextView     tvRoomsEmpty;

    private TextView tvCurrentRoom, tvCurrentRoomSince, tvDetectionCount, tvDetectionLast;

    private MaterialCardView cardFood, cardRooms;
    private View emptyStateView, scrollContent;
    private BottomNavigationView bottomNav;

    /** The pet currently on screen. Null until one loads, or when the owner has none. */
    private Pet currentPet;

    /**
     * One hue per room, cycling if there are more rooms than colours.
     *
     * Colour resources rather than literals so the dark palette can differ:
     * the light hues are too saturated to sit on a dark ground, and
     * values-night/colors.xml overrides all four. The bar and its legend read
     * the same array, so they cannot drift apart.
     */
    private static final int[] ROOM_COLORS = {
            R.color.room_1, R.color.room_2, R.color.room_3, R.color.room_4
    };

    @Override
    protected void onStart() {
        super.onStart();
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_menu);
        bindViews();
        setupBottomNavigation();

        findViewById(R.id.btnAccount).setOnClickListener(v ->
                startActivity(new Intent(this, AccountActivity.class)));
        findViewById(R.id.rowDeviceStatus).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        ((FloatingActionButton) findViewById(R.id.fabAddPet)).setOnClickListener(v ->
                startActivity(new Intent(this, AddPetActivity.class)));

        // The header is the only way into the pet detail screen.
        findViewById(R.id.rowPetHeader).setOnClickListener(v -> openPetDetail());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from another tab leaves that tab highlighted, so re-assert
        // Home. Safe to fire the listener: its nav_home branch is a no-op.
        bottomNav.setSelectedItemId(R.id.nav_home);
        NavTint.apply(this, bottomNav, R.id.nav_home);
        loadActivePet();
    }

    private void bindViews() {
        imgPetAvatar       = findViewById(R.id.imgPetAvatar);
        tvPetName          = findViewById(R.id.tvPetName);
        tvPetSubtitle      = findViewById(R.id.tvPetSubtitle);
        dotDeviceStatus    = findViewById(R.id.dotDeviceStatus);
        tvCameraStatus     = findViewById(R.id.tvCameraStatus);
        tvGpsStatus        = findViewById(R.id.tvGpsStatus);
        foodProgress       = findViewById(R.id.foodProgress);
        tvGramsEaten       = findViewById(R.id.tvGramsEaten);
        tvGramsTarget      = findViewById(R.id.tvGramsTarget);
        tvFoodEmpty        = findViewById(R.id.tvFoodEmpty);
        mealsContainer     = findViewById(R.id.mealsContainer);
        roomBar            = findViewById(R.id.roomBar);
        roomLegend         = findViewById(R.id.roomLegend);
        tvRoomsEmpty       = findViewById(R.id.tvRoomsEmpty);
        tvCurrentRoom      = findViewById(R.id.tvCurrentRoom);
        tvCurrentRoomSince = findViewById(R.id.tvCurrentRoomSince);
        tvDetectionCount   = findViewById(R.id.tvDetectionCount);
        tvDetectionLast    = findViewById(R.id.tvDetectionLast);
        cardFood           = findViewById(R.id.cardFood);
        cardRooms          = findViewById(R.id.cardRooms);
        emptyStateView     = findViewById(R.id.emptyStateView);
        scrollContent      = findViewById(R.id.scrollContent);
    }

    // ── Data ────────────────────────────────────────────────────────────────

    private void loadActivePet() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;              // onStart owns the redirect
        String ownerId = user.getUid();

        new Thread(() -> {
            List<Pet> pets = AppDatabase
                    .getInstance(getApplicationContext())
                    .petDao()
                    .getPetsForOwner(ownerId);

            // Pick the pet and read its stats here, not in runOnUiThread:
            // forPetToday hits the database and Room refuses to run on the
            // main thread.
            final Pet pet = (pets == null || pets.isEmpty()) ? null : pickActive(pets);
            final DashboardStats stats = pet == null
                    ? null : DashboardStats.forPetToday(getApplicationContext(), pet);

            runOnUiThread(() -> {
                // The query outlives a rotation or a back press; touching views
                // after that throws. Nothing to update once we are going away.
                if (isFinishing() || isDestroyed()) return;

                if (pet == null) {
                    showNoPets();
                } else {
                    showPet(pet);
                    render(stats);
                }
            });
        }).start();
    }

    /**
     * The pet chosen in the list, or the first one. The stored id goes stale
     * when that pet is deleted or the account changes, so fall back rather than
     * showing an empty dashboard next to a non-empty pet list.
     */
    private Pet pickActive(List<Pet> pets) {   // called on the worker thread
        long activeId = Prefs.getActivePetId(this);
        for (Pet pet : pets) {
            if (pet.getId() == activeId) return pet;
        }
        Pet first = pets.get(0);
        Prefs.setActivePetId(this, first.getId());
        return first;
    }

    private void showNoPets() {
        currentPet = null;
        emptyStateView.setVisibility(View.VISIBLE);
        cardFood.setVisibility(View.GONE);
        cardRooms.setVisibility(View.GONE);
        tvPetName.setText(R.string.no_pets_yet);
        tvPetSubtitle.setText(R.string.tap_to_add_one);

        // The device-status row and the glance cards are outside the hidden
        // cards, so they still need filling. Rendering empty stats keeps them on
        // the same code path as everywhere else — otherwise they sit blank,
        // which is what happened once their layout defaults moved to tools:text.
        render(new DashboardStats());
    }

    private void showPet(Pet pet) {
        currentPet = pet;
        emptyStateView.setVisibility(View.GONE);
        cardFood.setVisibility(View.VISIBLE);
        cardRooms.setVisibility(View.VISIBLE);

        tvPetName.setText(pet.getName());
        String species = pet.getSpecies();
        tvPetSubtitle.setText(species == null || species.isEmpty()
                ? getString(R.string.pet_profile) : species);

        String photoUri = pet.getPhotoUri();
        if (photoUri != null && !photoUri.isEmpty()) {
            // Glide, not setImageURI — the latter decodes the full bitmap on the
            // main thread and drops frames on a phone-camera photo.
            imgPetAvatar.setColorFilter(null);
            imgPetAvatar.setPadding(0, 0, 0, 0);
            Glide.with(this)
                    .load(Uri.parse(photoUri))
                    .centerCrop()
                    .placeholder(R.drawable.ic_pets_24)
                    .into(imgPetAvatar);
        } else {
            // Reset, or a previous pet's photo lingers after the photo is cleared.
            Glide.with(this).clear(imgPetAvatar);
            imgPetAvatar.setImageResource(R.drawable.ic_pets_24);
            imgPetAvatar.setColorFilter(ContextCompat.getColor(this, R.color.accent_2_dark));
            imgPetAvatar.setPadding(dp(13), dp(13), dp(13), dp(13));
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private void render(DashboardStats s) {
        renderDeviceStatus(s);
        renderFood(s);
        renderRooms(s);
        renderGlanceCards(s);
    }

    private void renderDeviceStatus(DashboardStats s) {
        tvCameraStatus.setText(s.cameraOnline
                ? R.string.camera_live : R.string.camera_offline_status);
        setDotColor(dotDeviceStatus, s.cameraOnline
                ? R.color.accent_2 : R.color.text_disabled);
        tvGpsStatus.setText(s.lastGpsFixAgo == null
                ? "No GPS fix yet" : "GPS " + s.lastGpsFixAgo);
    }

    private void renderFood(DashboardStats s) {
        mealsContainer.removeAllViews();

        if (s.gramsEaten == null) {
            foodProgress.setProgress(0, false);
            tvGramsEaten.setText(R.string.em_dash);
            tvGramsTarget.setText(R.string.no_feeder);
            tvFoodEmpty.setVisibility(View.VISIBLE);
            return;
        }

        tvFoodEmpty.setVisibility(View.GONE);
        int pct = s.gramsTarget == 0 ? 0
                : Math.min(100, Math.round(s.gramsEaten * 100f / s.gramsTarget));
        foodProgress.setProgress(pct, true);
        tvGramsEaten.setText(getString(R.string.grams_value, s.gramsEaten));
        tvGramsTarget.setText(getString(R.string.of_grams_target, s.gramsTarget));

        for (DashboardStats.Meal m : s.meals) {
            mealsContainer.addView(buildPill(
                    m.time + " · " + m.grams + " g",
                    R.color.accent_2_tint, R.color.accent_2_dark));
        }
    }

    private void renderRooms(DashboardStats s) {
        roomBar.removeAllViews();
        roomLegend.removeAllViews();

        int total = s.totalRoomMinutes();
        if (s.rooms.isEmpty() || total == 0) {
            roomBar.setVisibility(View.GONE);
            tvRoomsEmpty.setVisibility(View.VISIBLE);
            return;
        }

        roomBar.setVisibility(View.VISIBLE);
        tvRoomsEmpty.setVisibility(View.GONE);

        for (int i = 0; i < s.rooms.size(); i++) {
            DashboardStats.RoomStay stay = s.rooms.get(i);
            int color = ContextCompat.getColor(this, ROOM_COLORS[i % ROOM_COLORS.length]);

            View seg = new View(this);
            LinearLayout.LayoutParams p =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, stay.minutes);
            p.setMarginEnd(i == s.rooms.size() - 1 ? 0 : dp(3));
            seg.setLayoutParams(p);
            seg.setBackground(pill(color));
            roomBar.addView(seg);

            roomLegend.addView(buildLegendRow(stay, color, i > 0));
        }
    }

    private void renderGlanceCards(DashboardStats s) {
        if (s.currentRoom == null) {
            tvCurrentRoom.setText(R.string.unknown);
            tvCurrentRoomSince.setText(R.string.waiting_for_a_sensor);
        } else {
            tvCurrentRoom.setText(s.currentRoom);
            tvCurrentRoomSince.setText(getString(R.string.room_since, s.currentRoomSince));
        }

        tvDetectionCount.setText(getString(
                R.string.detections_today_count, s.detectionsToday));
        tvDetectionLast.setText(s.lastDetectionTime == null
                ? (s.cameraOnline ? "none yet today" : "camera not connected")
                : "last " + s.lastDetectionTime);
    }

    // ── Small view builders ─────────────────────────────────────────────────

    private View buildLegendRow(DashboardStats.RoomStay stay, int color, boolean spaced) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (spaced) rp.topMargin = dp(9);
        row.setLayoutParams(rp);

        View dot = new View(this);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(11), dp(11)));
        dot.setBackground(pill(color));
        row.addView(dot);

        TextView name = new TextView(this);
        name.setText(stay.room);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        LinearLayout.LayoutParams np =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        np.setMarginStart(dp(9));
        name.setLayoutParams(np);
        row.addView(name);

        TextView value = new TextView(this);
        value.setText(formatMinutes(stay.minutes));
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        value.setTypeface(ResourcesCompat.getFont(this, R.font.poppins_semibold));
        row.addView(value);

        return row;
    }

    private View buildPill(String text, int bgColorRes, int textColorRes) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(ContextCompat.getColor(this, textColorRes));
        tv.setPadding(dp(12), dp(5), dp(12), dp(5));
        tv.setBackground(pill(ContextCompat.getColor(this, bgColorRes)));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMarginEnd(dp(8));
        tv.setLayoutParams(p);
        return tv;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(999));
        d.setColor(color);
        return d;
    }

    private void setDotColor(View dot, int colorRes) {
        dot.setBackground(pill(ContextCompat.getColor(this, colorRes)));
    }

    private String formatMinutes(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        return h == 0 ? m + " m" : h + " h " + String.format("%02d", m) + " m";
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    private void setupBottomNavigation() {
        bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setSelectedItemId(R.id.nav_home);
        NavTint.apply(this, bottomNav, R.id.nav_home);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            NavTint.apply(this, bottomNav, id);

            if (id == R.id.nav_home) {
                return true;                                  // already here
            } else if (id == R.id.nav_live) {
                startActivity(PiConnectionManager.streamIntent(this));
                return true;
            } else if (id == R.id.nav_food) {
                startActivity(new Intent(this, FeedingActivity.class));
                return true;
            } else if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
    }

    /**
     * The header opens the pet list, where you pick which pet the dashboard
     * tracks. With no pets there is nothing to pick, so go straight to Add.
     */
    private void openPetDetail() {
        if (currentPet == null) {
            startActivity(new Intent(this, AddPetActivity.class));
            return;
        }
        startActivity(new Intent(this, PetListActivity.class));
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
