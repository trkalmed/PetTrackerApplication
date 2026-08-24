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

/**
 * Draws detector output over the camera feed.
 *
 * The tricky part is mapping detector coordinates onto the view. Boxes come back
 * in the analysed image's pixel space, but the view underneath does not show that
 * image one-to-one: {@code PreviewView} fills and crops, the Pi's {@code ImageView}
 * letterboxes. The old code scaled x and y independently by the view/image ratio,
 * which is only correct if the view happens to match the image aspect exactly —
 * otherwise every box is offset and the wrong shape. {@link ScaleMode} exists so
 * the same maths as the view underneath is applied here.
 */
public class GraphicOverlay extends View {

    private final Paint boxPaint;
    private final Paint textPaint;
    private final Paint bgPaint;

    private final List<Detection> detections = new ArrayList<>();
    private int imageWidth = 0;
    private int imageHeight = 0;
    private BoxMapper.ScaleMode scaleMode = BoxMapper.ScaleMode.FILL_CENTER;

    /** Overrides the detector's label, so a box can say "Biscuit" not "cat". */
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

    /** Name shown instead of the COCO class, or null to show the class. */
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * @param imgWidth  width of the bitmap the detector actually saw — after any
     *                  rotation, not the raw sensor buffer
     * @param imgHeight height of that same bitmap
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

            // Keep the caption on screen when the box touches the top edge,
            // otherwise it is drawn off the canvas and simply never seen.
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
