package com.example.pettrackerapplication;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.vision.detector.Detection;

import java.io.IOException;
import java.util.List;
import java.util.Random;

/**
 * Loads the real model and runs inference on it.
 *
 * A model swap fails in exactly two ways, both of them silent until someone
 * opens the live screen: the asset does not parse (wrong file, missing
 * metadata, no embedded labels) or the options are rejected (an allow-list
 * naming labels the model does not have). Both surface here instead.
 *
 * These deliberately do not assert that a specific animal is found — that needs
 * real photographs and belongs in a fixture-driven accuracy suite, not a build
 * check.
 */
@RunWith(AndroidJUnit4.class)
public class CocoObjectDetectorTest {

    private Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    private static Bitmap syntheticScene(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(120, 120, 120));

        // Deterministic noise plus a few shapes: enough structure for the model
        // to do real work rather than short-circuit on a flat image.
        Random random = new Random(42);
        Paint paint = new Paint();
        for (int i = 0; i < 40; i++) {
            paint.setColor(Color.rgb(
                    random.nextInt(256), random.nextInt(256), random.nextInt(256)));
            float left = random.nextInt(width);
            float top = random.nextInt(height);
            canvas.drawRect(left, top, left + 60, top + 60, paint);
        }
        return bitmap;
    }

    @Test
    public void theModelLoadsAndTheAllowListIsAccepted() {
        // Construction parses the .tflite, reads its embedded labelmap, and
        // validates the allow-list against it. If the asset were the wrong file
        // or a label were misspelled, this throws.
        try {
            CocoObjectDetector detector = new CocoObjectDetector(context());
            assertNotNull(detector);
            detector.close();
        } catch (IOException e) {
            fail("model failed to load: " + e.getMessage());
        }
    }

    @Test
    public void inferenceRunsAndReturnsAResult() throws IOException {
        CocoObjectDetector detector = new CocoObjectDetector(context());
        try {
            List<Detection> detections = detector.detect(syntheticScene(640, 480));

            // Never null, and never more than the configured cap.
            assertNotNull(detections);
            assertTrue("max results should be respected", detections.size() <= 10);
        } finally {
            detector.close();
        }
    }

    @Test
    public void onlyAnimalsComeBack() throws IOException {
        // The allow-list is the reason the overlay does not box furniture. If it
        // silently stopped applying, this catches it.
        CocoObjectDetector detector = new CocoObjectDetector(context());
        try {
            List<Detection> detections = detector.detect(syntheticScene(640, 480));

            for (Detection detection : detections) {
                for (Category category : detection.getCategories()) {
                    assertTrue(
                            "non-animal label leaked through: " + category.getLabel(),
                            PetLabels.isAnimal(category.getLabel()));
                }
            }
        } finally {
            detector.close();
        }
    }

    @Test
    public void oddlyShapedFramesAreHandled() throws IOException {
        // The Pi stream and the phone camera do not agree on aspect ratio, and a
        // rotated portrait frame is taller than it is wide.
        CocoObjectDetector detector = new CocoObjectDetector(context());
        try {
            assertNotNull(detector.detect(syntheticScene(720, 1280)));
            assertNotNull(detector.detect(syntheticScene(1280, 720)));
            assertNotNull(detector.detect(syntheticScene(200, 200)));
        } finally {
            detector.close();
        }
    }
}
