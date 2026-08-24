package com.example.pettrackerapplication;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;

import java.io.IOException;
import java.util.List;

/**
 * The pet detector.
 *
 * Runs EfficientDet-Lite2 rather than the SSD MobileNet v1 this started with.
 * The old model dates from 2017 and scores about 21 mAP on COCO; Lite2 scores
 * roughly 34, and the gap is widest exactly where a pet camera lives — animals
 * that are partly out of frame, at odd angles, or lying down. It costs about
 * 3 MB and some milliseconds per frame, which is a good trade when frames are
 * dropped under load anyway.
 *
 * Detection is restricted to {@link PetLabels}, so the overlay boxes animals
 * and not the furniture they are sitting on.
 */
public class CocoObjectDetector {

    private static final String MODEL_FILE = "efficientdet_lite2.tflite";

    /**
     * Lower than the old 0.5. EfficientDet is better calibrated, and a cat that
     * is half behind a chair leg often lands in the 0.35–0.5 band — which is a
     * sighting worth having on a pet camera, where a missed detection costs more
     * than an occasional wrong box.
     */
    private static final float SCORE_THRESHOLD = 0.4f;

    /** Enough for several pets in frame at once without unbounded work. */
    private static final int MAX_RESULTS = 10;

    private static final int NUM_THREADS = 4;

    private final ObjectDetector detector;

    public CocoObjectDetector(Context context) throws IOException {
        ObjectDetector.ObjectDetectorOptions options =
                ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder()
                                .setNumThreads(NUM_THREADS)
                                .build())
                        .setScoreThreshold(SCORE_THRESHOLD)
                        .setMaxResults(MAX_RESULTS)
                        // Filtering here rather than after the fact means the
                        // score threshold and result cap apply to animals only,
                        // so a room full of furniture cannot crowd out the pet.
                        .setLabelAllowList(PetLabels.allowList())
                        .build();
        detector = ObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options);
    }

    public List<Detection> detect(Bitmap bitmap) {
        TensorImage tensorImage = TensorImage.fromBitmap(bitmap);
        return detector.detect(tensorImage);
    }

    public void close() {
        if (detector != null) detector.close();
    }
}
