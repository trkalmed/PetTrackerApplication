package com.example.pettrackerapplication;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The route the tracker took — the Pi's route, and never the pet's.
 *
 * The dashboard shows the latest fix; this shows the run of them, which is a
 * different claim and a stronger one. A single fix proves the GNSS module
 * works. A sequence proves the whole chain works: the module fixes, the Pi
 * buffers while it is off the house network, and the phone reconciles the lot
 * when it comes back into range. That last part is the interesting one, and it
 * is invisible on any screen that only shows the newest reading.
 *
 * Everything here is labelled as the tracker's position. Carrying the Pi to
 * record a route does not make the route a pet's — it makes it a demonstration
 * of what a collar-mounted unit would produce, at a size that could be carried
 * by a large animal but not by a cat.
 */
public class TrackActivity extends ThemedActivity {

    /**
     * Most fixes drawn. At a one-second interval this is over two hours of
     * walking, and a path with more vertices than the screen has pixels costs
     * time to draw and shows nothing extra.
     */
    private static final int MAX_FIXES = 8000;

    private TrackView trackView;
    private PiSync.Poller piPoller;
    private TextView tvTrackEmpty, tvTrackPoints, tvTrackDistance,
            tvTrackSatellites, tvTrackLatest, tvTrackLegend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_track);

        trackView         = findViewById(R.id.trackView);
        tvTrackEmpty      = findViewById(R.id.tvTrackEmpty);
        tvTrackPoints     = findViewById(R.id.tvTrackPoints);
        tvTrackDistance   = findViewById(R.id.tvTrackDistance);
        tvTrackSatellites = findViewById(R.id.tvTrackSatellites);
        tvTrackLatest     = findViewById(R.id.tvTrackLatest);
        tvTrackLegend     = findViewById(R.id.tvTrackLegend);

        findViewById(R.id.btnBack).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        piPoller = new PiSync.Poller(this, this::onSynced);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        // The route is the one screen where the reading arriving is the whole
        // point — watching a walk extend is different evidence from opening a
        // finished one. Same poller the dashboard uses, so the Pi is asked no
        // more often than anywhere else.
        piPoller.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        piPoller.stop();
    }

    /** A sync finished. Redrawing only matters if it brought new fixes. */
    private void onSynced(PiEventSync.Result result) {
        if (isFinishing() || isDestroyed()) return;
        if (result.storedAnything()) load();
    }

    private void load() {
        long petId = Prefs.getActivePetId(this);

        new Thread(() -> {
            final List<TrackerFix> fixes = petId < 0
                    ? null
                    : AppDatabase.getInstance(getApplicationContext())
                            .trackerFixDao()
                            .getMostRecentRun(petId, MAX_FIXES);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                render(fixes);
            });
        }).start();
    }

    private void render(List<TrackerFix> fixes) {
        if (fixes == null || fixes.isEmpty()) {
            trackView.setVisibility(View.GONE);
            tvTrackEmpty.setVisibility(View.VISIBLE);
            tvTrackLegend.setVisibility(View.GONE);
            tvTrackPoints.setText(R.string.track_no_fixes);
            tvTrackDistance.setText(R.string.em_dash);
            tvTrackSatellites.setText(R.string.em_dash);
            tvTrackLatest.setText(R.string.em_dash);
            return;
        }

        // One outing, not everything ever recorded. The query returns the last
        // N fixes whenever they happened, which is a count and not a run: a
        // week of readings taken at a desk arrived on this screen joined to
        // yesterday's walk and summarised as a single 194-hour journey.
        long[] all = new long[fixes.size()];
        for (int i = 0; i < all.length; i++) all[i] = fixes.get(i).getTimestampMillis();
        int runStart = Geo.mostRecentRunStart(all);
        if (runStart > 0) fixes = fixes.subList(runStart, fixes.size());

        trackView.setVisibility(View.VISIBLE);
        tvTrackEmpty.setVisibility(View.GONE);
        tvTrackLegend.setVisibility(fixes.size() > 1 ? View.VISIBLE : View.GONE);
        trackView.setFixes(fixes);

        int count = fixes.size();
        TrackerFix first = fixes.get(0);
        TrackerFix last = fixes.get(count - 1);

        long spanMillis = last.getTimestampMillis() - first.getTimestampMillis();
        tvTrackPoints.setText(getString(R.string.track_points,
                count, describeDuration(spanMillis)));

        double[] lats = new double[count];
        double[] lons = new double[count];
        int minSatellites = Integer.MAX_VALUE;
        int maxSatellites = 0;
        for (int i = 0; i < count; i++) {
            TrackerFix fix = fixes.get(i);
            lats[i] = fix.getLatitude();
            lons[i] = fix.getLongitude();
            minSatellites = Math.min(minSatellites, fix.getSatellites());
            maxSatellites = Math.max(maxSatellites, fix.getSatellites());
        }

        long[] stamps = new long[count];
        for (int i = 0; i < count; i++) stamps[i] = fixes.get(i).getTimestampMillis();
        int gaps = Geo.countGaps(stamps);
        tvTrackLegend.setText(gaps == 0
                ? getString(R.string.track_legend)
                : getResources().getQuantityString(R.plurals.track_legend_gaps, gaps, gaps));

        double metres = Geo.routeLengthMetres(lats, lons, stamps);
        tvTrackDistance.setText(metres >= 1000
                ? getString(R.string.track_distance_km, metres / 1000)
                : getString(R.string.track_distance_m, Math.round(metres)));

        tvTrackSatellites.setText(minSatellites == maxSatellites
                ? getString(R.string.track_satellites_flat, maxSatellites)
                : getString(R.string.track_satellites_range,
                        minSatellites, maxSatellites));

        String clock = new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(last.getTimestampMillis()));
        tvTrackLatest.setText(getString(R.string.track_latest,
                last.getLatitude(), last.getLongitude(), clock));
    }

    /**
     * "12 min", "1 h 04", or "48 s".
     *
     * Seconds are shown below a minute rather than rounded to "0 min", because
     * a very short run is exactly what a demo produces and reading zero would
     * look like a fault.
     */
    private String describeDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) {
            return getString(R.string.duration_seconds, seconds);
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return getString(R.string.duration_minutes, minutes);
        }
        return getString(R.string.duration_hours, minutes / 60, minutes % 60);
    }
}
