package com.example.pettrackerapplication;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a route from GPS fixes, with no map underneath it.
 *
 * <b>Why no map.</b> A tiled map means a maps SDK, an API key, a billing
 * account and a network round trip to show something the app already knows. The
 * question this screen answers is "did the tracker follow a path, and what
 * shape was it" — and the shape is entirely in the fixes. A street map would
 * make it prettier and would also make an offline demo depend on a CDN.
 *
 * <b>The projection.</b> Latitude and longitude are not interchangeable units.
 * A degree of latitude is the same length everywhere; a degree of longitude
 * shrinks towards the poles, so plotting one against the other stretches the
 * route east-west — at 32 degrees north, by about 15%. Longitudes are scaled by
 * cos(latitude) before anything is drawn, which is the standard equirectangular
 * projection and is accurate enough over a walk to be indistinguishable from
 * the truth. The aspect ratio is then preserved when fitting to the view, so a
 * square walk looks square.
 *
 * A single fix draws as one dot rather than an empty view: "the tracker is
 * here, and has not moved" is a real answer.
 */
public class TrackView extends View {

    /** Route line, in device-independent pixels. */
    private static final float STROKE_DP = 3f;
    /** Start and end markers. */
    private static final float MARKER_RADIUS_DP = 6f;
    /** Breathing room so markers are not clipped by the edge. */
    private static final float PADDING_DP = 20f;

    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path gapPath = new Path();

    private final List<double[]> points = new ArrayList<>();   // {lat, lon}
    private final List<Long> times = new ArrayList<>();        // epoch millis
    private final List<Integer> sats = new ArrayList<>();      // satellites per fix

    /**
     * Segments where the receiver stopped reporting, drawn as a broken line.
     *
     * Joining across a gap with the same confident stroke as the rest claims a
     * path nothing measured — the straight lines cutting across the first
     * walk's route were all of this kind. What is unknown is the shape of the
     * route between them, and a dashed line says exactly that.
     *
     * Two things are drawn this way, because they are the same claim. The
     * receiver going quiet is one. The receiver staying talkative while it
     * stops knowing where it is — three satellites, a fix every two seconds,
     * positions scattered over tens of metres — is the other, and it is the
     * one that used to pass for a measured route.
     */
    private final Paint gapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TrackView(Context context) {
        this(context, null);
    }

    public TrackView(Context context, AttributeSet attrs) {
        super(context, attrs);

        float density = getResources().getDisplayMetrics().density;

        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(STROKE_DP * density);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);
        routePaint.setColor(ContextCompat.getColor(context, R.color.accent_2));

        startPaint.setStyle(Paint.Style.FILL);
        startPaint.setColor(ContextCompat.getColor(context, R.color.accent_2));

        endPaint.setStyle(Paint.Style.FILL);
        endPaint.setColor(ContextCompat.getColor(context, R.color.text_primary));

        gapPaint.setStyle(Paint.Style.STROKE);
        gapPaint.setStrokeWidth(STROKE_DP * density * 0.6f);
        gapPaint.setColor(ContextCompat.getColor(context, R.color.accent_2));
        gapPaint.setAlpha(90);
        gapPaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{6 * density, 6 * density}, 0));
    }

    /** Fixes in the order they were recorded, oldest first. */
    public void setFixes(List<TrackerFix> fixes) {
        points.clear();
        times.clear();
        sats.clear();
        if (fixes != null) {
            for (TrackerFix fix : fixes) {
                points.add(new double[]{fix.getLatitude(), fix.getLongitude()});
                times.add(fix.getTimestampMillis());
                sats.add(fix.getSatellites());
            }
        }
        invalidate();
    }

    public boolean hasAnythingToDraw() {
        return !points.isEmpty();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        float pad = PADDING_DP * density;
        float markerRadius = MARKER_RADIUS_DP * density;

        float usableWidth = getWidth() - 2 * pad;
        float usableHeight = getHeight() - 2 * pad;
        if (usableWidth <= 0 || usableHeight <= 0) return;

        // Longitude is scaled by cos(latitude) so the projection is not
        // stretched east-west. One scale for the whole route, taken at its
        // middle: over a walk the difference across the route is nil.
        double midLatitude = midLatitude();
        double lonScale = Geo.longitudeScale(midLatitude);

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : points) {
            double x = p[1] * lonScale;
            double y = p[0];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }

        double spanX = maxX - minX;
        double spanY = maxY - minY;

        // One scale factor for both axes, so the route keeps its shape rather
        // than being stretched to fill the view. A route that is mostly a
        // straight line has almost no span on one axis, and dividing by it
        // would magnify GPS jitter across the whole screen.
        double span = Math.max(spanX, spanY);
        float scale = span <= 0 ? 0 : (float) (Math.min(usableWidth, usableHeight) / span);

        // Centre whatever the route's real proportions turn out to be.
        float offsetX = pad + (usableWidth - (float) (spanX * scale)) / 2f;
        float offsetY = pad + (usableHeight - (float) (spanY * scale)) / 2f;

        long gapThreshold = Geo.gapThresholdMillis(timestamps());

        path.reset();
        gapPath.reset();
        float firstX = 0, firstY = 0, lastX = 0, lastY = 0;
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            float x = offsetX + (float) ((p[1] * lonScale - minX) * scale);
            // Screen y grows downwards and latitude grows northwards, so this
            // is measured from the top. Without the flip, north is at the
            // bottom and every route is drawn upside down.
            float y = offsetY + (float) ((maxY - p[0]) * scale);

            if (i == 0) {
                path.moveTo(x, y);
                firstX = x; firstY = y;
            } else if (!Geo.isTrusted(sats.get(i)) || !Geo.isTrusted(sats.get(i - 1))
                    || times.get(i) - times.get(i - 1) > gapThreshold) {
                // Either the receiver stopped reporting between these two
                // fixes, or it reported from too few satellites to be placed.
                // The tracker travelled from one to the other either way; the
                // path it took is unknown, and drawing it as a confident line
                // is the one way this picture can lie.
                gapPath.moveTo(lastX, lastY);
                gapPath.lineTo(x, y);
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            lastX = x; lastY = y;
        }

        if (points.size() == 1) {
            // Not a route. One fix, drawn where it is, which is the honest
            // picture of a tracker that has not moved.
            canvas.drawCircle(firstX, firstY, markerRadius, startPaint);
            return;
        }

        canvas.drawPath(gapPath, gapPaint);
        canvas.drawPath(path, routePaint);
        canvas.drawCircle(firstX, firstY, markerRadius, startPaint);
        canvas.drawCircle(lastX, lastY, markerRadius, endPaint);
    }

    private long[] timestamps() {
        long[] out = new long[times.size()];
        for (int i = 0; i < out.length; i++) out[i] = times.get(i);
        return out;
    }

    private double midLatitude() {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double[] p : points) {
            min = Math.min(min, p[0]);
            max = Math.max(max, p[0]);
        }
        return (min + max) / 2;
    }
}
