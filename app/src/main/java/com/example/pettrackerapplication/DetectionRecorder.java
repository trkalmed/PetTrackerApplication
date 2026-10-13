package com.example.pettrackerapplication;

import android.content.Context;
import android.util.Log;

import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.vision.detector.Detection;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Turns per frame detections into sightings worth storing: animals only, throttled per label. */
public final class DetectionRecorder {

    private static final String TAG = "DetectionRecorder";

    private static final long THROTTLE_MS = 30_000L;

    private final PetDataRepository repository;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    private final long petId;
    private final String source;

    private final java.util.Map<String, Long> lastStoredAt = new java.util.HashMap<>();

    public DetectionRecorder(Context context, long petId, String source) {
        this.repository = new PetDataRepository(context);
        this.petId = petId;
        this.source = source;
    }

    public void onDetections(List<Detection> detections) {
        if (petId <= 0 || detections == null || detections.isEmpty()) return;

        long now = System.currentTimeMillis();

        for (Detection detection : detections) {
            List<Category> categories = detection.getCategories();
            if (categories.isEmpty()) continue;

            Category best = categories.get(0);
            String label = best.getLabel();
            if (!PetLabels.isAnimal(label)) continue;

            Long previous = lastStoredAt.get(label);
            if (previous != null && now - previous < THROTTLE_MS) continue;
            lastStoredAt.put(label, now);

            final float confidence = best.getScore();
            final String storedLabel = label;
            writer.execute(() -> {
                try {
                    repository.recordDetection(petId, now, storedLabel, confidence, source);
                } catch (Exception e) {
                    Log.e(TAG, "Could not store detection", e);
                }
            });
        }
    }

    public void close() {
        writer.shutdown();
    }
}
