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

public class GraphicOverlay extends View {

    private final Paint boxPaint;
    private final Paint textPaint;
    private final Paint bgPaint;

    private final List<Detection> detections = new ArrayList<>();
    private int imageWidth = 0;
    private int imageHeight = 0;

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

    /**
     * Each Detection from the COCO TFLite model already carries its own
     * label + confidence score, so no separate label map is needed here
     * (unlike the old ML Kit version of this class).
     */
    public synchronized void setResults(
            List<Detection> newDetections, int imgWidth, int imgHeight) {
        detections.clear();
        if (newDetections != null) detections.addAll(newDetections);
        imageWidth = imgWidth;
        imageHeight = imgHeight;
        postInvalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageWidth == 0 || imageHeight == 0) return;

        float scaleX = getWidth() / (float) imageWidth;
        float scaleY = getHeight() / (float) imageHeight;

        for (int i = 0; i < detections.size(); i++) {
            Detection detection = detections.get(i);
            RectF box = detection.getBoundingBox();

            float left   = box.left   * scaleX;
            float top    = box.top    * scaleY;
            float right  = box.right  * scaleX;
            float bottom = box.bottom * scaleY;

            int color = colors[i % colors.length];
            boxPaint.setColor(color);
            canvas.drawRect(left, top, right, bottom, boxPaint);

            String label = buildLabel(detection);

            float textWidth  = textPaint.measureText(label);
            float textHeight = textPaint.getTextSize();
            bgPaint.setColor(color);
            canvas.drawRect(left, top - textHeight - 8,
                    left + textWidth + 16, top, bgPaint);

            canvas.drawText(label, left + 8, top - 8, textPaint);
        }
    }

    private String buildLabel(Detection detection) {
        List<Category> categories = detection.getCategories();
        if (categories.isEmpty()) return "Object";
        Category best = categories.get(0);
        return best.getLabel() + " (" +
                String.format("%.0f", best.getScore() * 100f) + "%)";
    }
}