package com.example.pettrackerapplication;

import android.content.Context;
import android.util.Log;

import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.vision.detector.Detection;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns a stream of per-frame detections into sightings worth storing.
 *
 * Two filters, both load-bearing:
 *
 *  1. **Animals only**, per {@link PetLabels}. COCO knows eighty classes and a
 *     living room is full of sofas and potted plants; storing a chair as a pet
 *     sighting would make "detections today" meaningless.
 *  2. **Throttling.** The detector runs on every frame it can keep up with. One
 *     row per frame would be tens of thousands of inserts an hour and would not
 *     say anything a sighting every {@value #THROTTLE_MS} ms does not.
 *
 * Writes happen on a single background thread, because the detector calls this
 * from the analysis thread and blocking that stalls the live preview.
 */
public final class DetectionRecorder {

    private static final String TAG = "DetectionRecorder";

    /** Minimum gap between stored sightings of the same label. */
    private static final long THROTTLE_MS = 30_000L;

    private final PetDataRepository repository;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    private final long petId;
    private final String source;

    /** When the last stored sighting happened, per label. */
    private final java.util.Map<String, Long> lastStoredAt = new java.util.HashMap<>();

    public DetectionRecorder(Context context, long petId, String source) {
        this.repository = new PetDataRepository(context);
        this.petId = petId;
        this.source = source;
    }

    /**
     * Offers one frame's detections for storage. Cheap and non-blocking: the
     * filtering happens inline, the insert is handed to the writer thread.
     */
    public void onDetections(List<Detection> detections) {
        if (petId <= 0 || detections == null || detections.isEmpty()) return;

        long now = System.currentTimeMillis();

        for (Detection detection : detections) {
            List<Category> categories = detection.getCategories();
            if (categories.isEmpty()) continue;

            Category best = categories.get(0);
            String label = best.getLabel();
            // Belt and braces: the detector already allow-lists these, but this
            // class must not depend on how it was configured.
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
                    // A failed sighting must never take the live view down with it.
                    Log.e(TAG, "Could not store detection", e);
                }
            });
        }
    }

    /** Call from the owning activity's onDestroy. */
    public void close() {
        writer.shutdown();
    }
}
