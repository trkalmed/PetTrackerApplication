package com.example.pettrackerapplication;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;

import java.io.IOException;
import java.util.List;

/** Pet detector running EfficientDet Lite2 on the phone, limited to animal classes. */
public class CocoObjectDetector {

    private static final String MODEL_FILE = "efficientdet_lite2.tflite";

    private static final float SCORE_THRESHOLD = 0.4f;

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
                        // Filter to animals here so the score threshold and result cap apply to animals only.
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
