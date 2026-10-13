package com.example.pettrackerapplication;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.vision.detector.Detection;

import java.util.ArrayList;
import java.util.List;

/** Draws detector boxes over the camera feed. */
public class GraphicOverlay extends View {

    private final Paint boxPaint;
    private final Paint textPaint;
    private final Paint bgPaint;

    private static final long HOLD_MS = 400L;

    private final List<Detection> detections = new ArrayList<>();
    private long lastNonEmptyAt = 0L;
    private int imageWidth = 0;
    private int imageHeight = 0;
    private BoxMapper.ScaleMode scaleMode = BoxMapper.ScaleMode.FILL_CENTER;

    private String displayName;

    private final int[] colors = {
            Color.parseColor("#FF5722"),
            Color.parseColor("#4CAF50"),
            Color.parseColor("#2196F3"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#FFC107"),
            Color.parseColor("#E91E63")
    };

    public GraphicOverlay(Context context) {
        this(context, null);
    }

    public GraphicOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(6f);
        boxPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(42f);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);

        bgPaint = new Paint();
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setAlpha(160);
    }

    public void setScaleMode(BoxMapper.ScaleMode mode) {
        this.scaleMode = mode;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public synchronized void setResults(
            List<Detection> newDetections, int imgWidth, int imgHeight) {
        boolean empty = newDetections == null || newDetections.isEmpty();

        // Hold the last result briefly so the boxes do not flicker on dropped frames.
        if (empty && !detections.isEmpty()
                && System.currentTimeMillis() - lastNonEmptyAt < HOLD_MS) {
            return;
        }

        detections.clear();
        if (!empty) {
            detections.addAll(newDetections);
            lastNonEmptyAt = System.currentTimeMillis();
        }
        imageWidth = imgWidth;
        imageHeight = imgHeight;
        postInvalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageWidth == 0 || imageHeight == 0) return;

        for (int i = 0; i < detections.size(); i++) {
            Detection detection = detections.get(i);

            RectF box = detection.getBoundingBox();
            float[] mapped = BoxMapper.map(box.left, box.top, box.right, box.bottom,
                    imageWidth, imageHeight, getWidth(), getHeight(), scaleMode);
            float left = mapped[0], top = mapped[1];
            float right = mapped[2], bottom = mapped[3];

            int color = colors[i % colors.length];
            boxPaint.setColor(color);
            canvas.drawRect(left, top, right, bottom, boxPaint);

            String label = buildLabel(detection);

            float textWidth  = textPaint.measureText(label);
            float textHeight = textPaint.getTextSize();

            float captionBottom = top < textHeight + 8 ? bottom + textHeight + 8 : top;

            bgPaint.setColor(color);
            canvas.drawRect(left, captionBottom - textHeight - 8,
                    left + textWidth + 16, captionBottom, bgPaint);
            canvas.drawText(label, left + 8, captionBottom - 8, textPaint);
        }
    }

    private String buildLabel(Detection detection) {
        List<Category> categories = detection.getCategories();
        if (categories.isEmpty()) return displayName != null ? displayName : "Object";
        Category best = categories.get(0);
        String name = displayName != null ? displayName : best.getLabel();
        return name + " (" + String.format("%.0f", best.getScore() * 100f) + "%)";
    }
}
