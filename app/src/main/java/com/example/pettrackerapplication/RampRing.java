package com.example.pettrackerapplication;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/** The food progress ring, drawn in the ramp colours. */
public class RampRing extends View {

    private static final float START_ANGLE = -90f;
    private static final long ANIMATION_MS = 600L;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private int[] rampColors;
    private float strokeWidth;
    private int progress;
    private float sweptProgress;
    @Nullable private ValueAnimator animator;

    public RampRing(Context context) {
        this(context, null);
    }

    public RampRing(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        strokeWidth = getResources().getDisplayMetrics().density * 14f;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokeWidth);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(strokeWidth);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);

        readColors();
    }

    private void readColors() {
        rampColors = new int[]{
                ContextCompat.getColor(getContext(), R.color.ramp_1),
                ContextCompat.getColor(getContext(), R.color.ramp_2),
                ContextCompat.getColor(getContext(), R.color.ramp_3),
                ContextCompat.getColor(getContext(), R.color.ramp_4),
        };
        trackPaint.setColor(0x33FFFFFF);
    }

    public void setTrackColor(int color) {
        trackPaint.setColor(color);
        invalidate();
    }

    public void setRingWidthPx(float px) {
        strokeWidth = px;
        trackPaint.setStrokeWidth(px);
        progressPaint.setStrokeWidth(px);
        requestLayout();
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress, boolean animate) {
        int clamped = Math.max(0, Math.min(100, progress));
        this.progress = clamped;

        if (animator != null) {
            animator.cancel();
            animator = null;
        }

        if (!animate) {
            sweptProgress = clamped;
            invalidate();
            return;
        }

        animator = ValueAnimator.ofFloat(sweptProgress, clamped);
        animator.setDuration(ANIMATION_MS);
        animator.addUpdateListener(a -> {
            sweptProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        float inset = strokeWidth / 2f;
        float size = Math.min(w, h) - strokeWidth;
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        bounds.set(left + inset - inset, top, left + size, top + size);
        bounds.set(left, top, left + size, top + size);

        SweepGradient gradient = new SweepGradient(
                bounds.centerX(), bounds.centerY(),
                new int[]{rampColors[0], rampColors[1], rampColors[2], rampColors[3], rampColors[0]},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f});
        android.graphics.Matrix rotate = new android.graphics.Matrix();
        rotate.setRotate(START_ANGLE, bounds.centerX(), bounds.centerY());
        gradient.setLocalMatrix(rotate);
        progressPaint.setShader(gradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bounds.isEmpty()) return;

        canvas.drawArc(bounds, 0f, 360f, false, trackPaint);

        if (sweptProgress > 0f) {
            canvas.drawArc(bounds, START_ANGLE, 360f * sweptProgress / 100f,
                    false, progressPaint);
        }
    }
}
