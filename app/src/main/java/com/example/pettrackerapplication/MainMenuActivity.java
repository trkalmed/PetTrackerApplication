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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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

    private MaterialCardView cardLastSeen;
    private ImageView        imgLastSeen;
    private TextView         tvLastSeenCaption;

    private TextView tvCurrentRoom, tvCurrentRoomSince, tvDetectionCount, tvDetectionLast;

    private MaterialCardView cardFood, cardRooms;
    private View emptyStateView, scrollContent;
    private BottomNavigationView bottomNav;

    /** The pet currently on screen. Null until one loads, or when the owner has none. */
    private Pet currentPet;

    /** Collects readings from the Pi while this screen is in front of the user. */
    private PiSync.Poller piPoller;

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

        piPoller = new PiSync.Poller(this, this::onSynced);

        // The room card shows today's shape; tapping it shows today's sequence.
        cardRooms.setOnClickListener(v ->
                startActivity(new Intent(this, SightingHistoryActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from another tab leaves that tab highlighted, so re-assert
        // Home. Safe to fire the listener: its nav_home branch is a no-op.
        bottomNav.setSelectedItemId(R.id.nav_home);
        NavTint.apply(this, bottomNav, R.id.nav_home);
        loadActivePet();
        piPoller.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Nothing polls the Pi while the dashboard is not on screen. The Pi
        // buffers, so the next resume collects whatever happened meanwhile.
        piPoller.stop();
    }

    /** A sync finished. Only a sync that stored something changes what is drawn. */
    private void onSynced(PiEventSync.Result result) {
        if (isFinishing() || isDestroyed()) return;
        if (result.storedAnything()) loadActivePet();
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
        cardLastSeen       = findViewById(R.id.cardLastSeen);
        imgLastSeen        = findViewById(R.id.imgLastSeen);
        tvLastSeenCaption  = findViewById(R.id.tvLastSeenCaption);
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
            final Pet pet = Prefs.chooseActive(getApplicationContext(), pets);
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
            // Separate from the colour filter above and not cleared by it: the
            // layout's app:tint is an image tint list, and it was painting the
            // photograph the placeholder's colour.
            PhotoTint.clearFor(imgPetAvatar);
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
            // The paw's fill is transparent, so without the tint it is invisible.
            PhotoTint.restore(imgPetAvatar, R.color.accent_2_dark);
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
        renderTrackerFix(s);
    }

    /**
     * The Pi's own GPS fix, sitting in the device-status row where it belongs.
     *
     * "Pi located", not "GPS" on its own: this row is next to the camera status
     * and describes the hardware, and a bare "GPS 19:42" under a dashboard about
     * an animal reads as the animal's position. It is the tracker's. Tapping
     * shows the coordinates and says so in as many words.
     */
    private void renderTrackerFix(DashboardStats s) {
        tvGpsStatus.setText(s.trackerFixTime == null
                ? getString(R.string.no_gps_fix_yet)
                : getString(R.string.tracker_located, s.trackerFixTime));

        // Opens the route rather than a dialog about one point. A single fix
        // shows the module works; the run of them shows the whole chain does —
        // the Pi buffering while off the network and the phone reconciling
        // afterwards, which no "latest reading" view can display.
        tvGpsStatus.setOnClickListener(v ->
                startActivity(new Intent(this, TrackActivity.class)));
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
        renderLastSeen(s);

        int total = s.totalRoomMinutes();
        if (s.rooms.isEmpty() || total == 0) {
            roomBar.setVisibility(View.GONE);
            tvRoomsEmpty.setVisibility(View.VISIBLE);
            // Sensors that have never reported need pairing; sensors that have
            // reported before and are quiet today are simply a quiet day.
            tvRoomsEmpty.setText(s.roomsReporting
                    ? R.string.no_motion_yet_today
                    : R.string.no_motion_sensors_reporting_pair_them);
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

    /**
     * The frame the Pi grabbed when a sensor last fired.
     *
     * The URL is built here rather than stored, because the only durable part of
     * it is the file name: the Pi's address is a DHCP lease, and one saved with
     * the event would be pointing at somebody else's toaster by next month.
     *
     * Hidden whenever there is nothing to show — no camera on the rig, no
     * sighting today, or no Pi paired on this phone. An empty grey rectangle
     * would be worse than the card simply not being there.
     */
    private void renderLastSeen(DashboardStats s) {
        String base = PiConnectionManager.getBaseUrl(this);
        if (s.lastSnapshotName == null || base == null) {
            cardLastSeen.setVisibility(View.GONE);
            Glide.with(this).clear(imgLastSeen);
            return;
        }

        cardLastSeen.setVisibility(View.VISIBLE);
        tvLastSeenCaption.setText(getString(R.string.snapshot_caption,
                s.lastSnapshotRoom, s.lastSnapshotTime));

        // Snapshots never change once written and the Pi says so in its headers,
        // so Glide's disk cache means walking past a sensor costs one fetch
        // rather than one per dashboard refresh.
        Glide.with(this)
                .load(base + "/snapshot?name=" + s.lastSnapshotName)
                .centerCrop()
                .into(imgLastSeen);
    }

    private void renderGlanceCards(DashboardStats s) {
        if (s.currentRoom == null) {
            tvCurrentRoom.setText(R.string.unknown);
            // A PIR reports movement, not presence, so going quiet means the
            // trail went cold — not that the pet is still where it was last
            // seen. Say when that was instead of claiming a room.
            tvCurrentRoomSince.setText(s.lastMotionTime == null
                    ? getString(R.string.waiting_for_a_sensor)
                    : getString(R.string.no_motion_since, s.lastMotionTime));
        } else {
            tvCurrentRoom.setText(s.currentRoom);
            tvCurrentRoomSince.setText(getString(R.string.room_since, s.currentRoomSince));
        }

        tvDetectionCount.setText(getString(
                R.string.detections_today_count, s.detectionsToday));
        // "N today" on its own overstates what this is. Sightings come from the
        // phone's own detector, which runs only while the Live screen is open —
        // so the number is what was seen while somebody was watching, and the
        // card has to say so rather than implying a day's coverage it does not
        // have. The Pi has the camera all day; it does not run a detector.
        if (s.lastDetectionTime != null) {
            tvDetectionLast.setText(
                    getString(R.string.detections_last_seen, s.lastDetectionTime));
        } else if (s.cameraOnline) {
            tvDetectionLast.setText(R.string.detections_none_while_watching);
        } else {
            tvDetectionLast.setText(R.string.detections_camera_not_connected);
        }
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
