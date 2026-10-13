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

/** Draws a GPS route without a map underneath it. */
public class TrackView extends View {

    private static final float STROKE_DP = 3f;
    private static final float MARKER_RADIUS_DP = 6f;
    private static final float PADDING_DP = 20f;

    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path gapPath = new Path();

    private final List<double[]> points = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private final List<Integer> sats = new ArrayList<>();

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

        // Scale longitude by cos(latitude) so the route is not stretched east to west.
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

        double span = Math.max(spanX, spanY);
        float scale = span <= 0 ? 0 : (float) (Math.min(usableWidth, usableHeight) / span);

        float offsetX = pad + (usableWidth - (float) (spanX * scale)) / 2f;
        float offsetY = pad + (usableHeight - (float) (spanY * scale)) / 2f;

        long gapThreshold = Geo.gapThresholdMillis(timestamps());

        path.reset();
        gapPath.reset();
        float firstX = 0, firstY = 0, lastX = 0, lastY = 0;
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            float x = offsetX + (float) ((p[1] * lonScale - minX) * scale);
            // Screen y grows downwards while latitude grows northwards.
            float y = offsetY + (float) ((maxY - p[0]) * scale);

            if (i == 0) {
                path.moveTo(x, y);
                firstX = x; firstY = y;
            } else if (!Geo.isTrusted(sats.get(i)) || !Geo.isTrusted(sats.get(i - 1))
                    || times.get(i) - times.get(i - 1) > gapThreshold) {
                // A break in coverage: drawn as a broken line and not measured.
                gapPath.moveTo(lastX, lastY);
                gapPath.lineTo(x, y);
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            lastX = x; lastY = y;
        }

        if (points.size() == 1) {
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
