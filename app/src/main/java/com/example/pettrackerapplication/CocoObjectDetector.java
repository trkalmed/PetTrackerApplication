package com.example.pettrackerapplication;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;

import java.io.IOException;
import java.util.List;


public class CocoObjectDetector {

    private static final String MODEL_FILE      = "coco_ssd_mobilenet_v1.tflite";
    private static final float  SCORE_THRESHOLD = 0.5f;
    private static final int    MAX_RESULTS     = 5;

    private final ObjectDetector detector;

    public CocoObjectDetector(Context context) throws IOException {
        ObjectDetector.ObjectDetectorOptions options =
                ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
                        .setScoreThreshold(SCORE_THRESHOLD)
                        .setMaxResults(MAX_RESULTS)
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