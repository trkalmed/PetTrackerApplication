package com.example.pettrackerapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.task.vision.detector.Detection;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LiveObjectDetectionActivity extends ThemedActivity {

    private static final String TAG = "LiveObjectDetection";

    private GraphicOverlay graphicOverlay;
    private PreviewView    previewView;
    private ImageView      piStreamView;
    private TextView       tvStreamStatus;
    private TextView       tvCameraName;

    private CocoObjectDetector cocoDetector;
    private volatile boolean detectionBusy = false;

    /** Stores sightings. Null when no pet is active — nothing to attribute them to. */
    private DetectionRecorder detectionRecorder;

    private ExecutorService cameraExecutor;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show();
                    finish();
                }
            });
    private String  streamUrl;
    private volatile boolean piStreamRunning = false;
    private Thread  piStreamThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_object_detection);
        findViewById(R.id.btnBack).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());
        graphicOverlay = findViewById(R.id.GraphicOverlay);
        previewView    = findViewById(R.id.previewView);
        piStreamView   = findViewById(R.id.piStreamView);
        tvStreamStatus = findViewById(R.id.tvStreamStatus);
        tvCameraName   = findViewById(R.id.tvCameraName);

        try {
            cocoDetector = new CocoObjectDetector(this);
        } catch (IOException e) {
            Log.e(TAG, "Failed to load COCO model", e);
            Toast.makeText(this,
                    getString(R.string.failed_to_load_detection_model_check),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        streamUrl = getIntent().getStringExtra("stream_url");
        if (streamUrl != null && !streamUrl.isEmpty()) {
            // The layout shipped with a hardcoded "Kitchen"; say which feed this
            // actually is, since the two look nothing alike in practice.
            tvCameraName.setText(R.string.pi_camera);
            // piStreamView is fitCenter: the whole frame is visible, letterboxed.
            graphicOverlay.setScaleMode(BoxMapper.ScaleMode.FIT_CENTER);
            detectionRecorder = createRecorder(DetectionEvent.SOURCE_PI);
            startPiStream();
            loadPiCameras();
        } else {
            tvCameraName.setText(R.string.phone_camera);
            // PreviewView defaults to FILL_CENTER: fills the view and crops.
            graphicOverlay.setScaleMode(BoxMapper.ScaleMode.FILL_CENTER);
            detectionRecorder = createRecorder(DetectionEvent.SOURCE_PHONE);

            cameraExecutor = Executors.newSingleThreadExecutor();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        }
    }

    /**
     * Sightings are attributed to the pet the dashboard is tracking. With no
     * pet chosen there is nothing to attribute them to, so nothing is stored —
     * the live view still works, it just does not log.
     */
    private DetectionRecorder createRecorder(String source) {
        long petId = Prefs.getActivePetId(this);
        if (petId <= 0) {
            Log.i(TAG, "No active pet; detections will not be recorded");
            return null;
        }
        return new DetectionRecorder(this, petId, source);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (detectionRecorder != null) detectionRecorder.close();

        piStreamRunning = false;
        if (piStreamThread != null) {
            piStreamThread.interrupt();
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
        }

        if (cocoDetector != null) cocoDetector.close();
    }


    /**
     * Names of the cameras this Pi has, from /health, in the Pi's own order.
     *
     * Empty until the first health reply lands, and empty forever on a Pi that
     * predates named cameras — in which case the screen behaves exactly as it
     * did before, streaming the one camera with no way to switch.
     */
    private final java.util.List<String> piCameras = new java.util.ArrayList<>();
    private int piCameraIndex = 0;

    /**
     * Asks which cameras exist, and offers a switch only if there are two.
     *
     * A control that switches between one thing is worse than no control: it
     * invites a tap that appears to do nothing. So the label only becomes
     * tappable once the Pi has actually reported more than one camera.
     */
    private void loadPiCameras() {
        PiClient client = PiClient.forSavedPi(this);
        if (client == null) return;

        new Thread(() -> {
            final java.util.List<String> found = new java.util.ArrayList<>();
            try {
                for (java.util.Map.Entry<String, Boolean> entry
                        : client.health().camerasOrLegacy().entrySet()) {
                    // Only cameras the Pi says are up. Offering to switch to one
                    // that is unplugged would trade a working picture for an
                    // error, which is not a choice worth presenting.
                    if (Boolean.TRUE.equals(entry.getValue())) found.add(entry.getKey());
                }
            } catch (IOException e) {
                Log.d(TAG, "could not list cameras: " + e.getMessage());
                return;
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                piCameras.clear();
                piCameras.addAll(found);
                if (piCameras.size() > 1) {
                    tvCameraName.setText(getString(R.string.pi_camera_named,
                            piCameras.get(piCameraIndex)));
                    tvCameraName.setOnClickListener(v -> switchPiCamera());
                    Toast.makeText(this, R.string.tap_to_switch_camera,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /** Moves to the next camera and reconnects the stream to it. */
    private void switchPiCamera() {
        if (piCameras.size() < 2) return;

        piCameraIndex = (piCameraIndex + 1) % piCameras.size();
        String name = piCameras.get(piCameraIndex);

        String base = PiConnectionManager.getBaseUrl(this);
        if (base == null) return;
        streamUrl = base + "/stream?camera=" + android.net.Uri.encode(name);

        tvCameraName.setText(getString(R.string.pi_camera_named, name));
        stopPiStream();
        startPiStream();
    }

    /**
     * Ends the current stream and waits briefly for its thread to notice.
     *
     * The wait matters: without it the old thread can deliver one more frame
     * after the new one has started, and a frame from the previous camera
     * appears under the new camera's label.
     */
    private void stopPiStream() {
        piStreamRunning = false;
        if (piStreamThread != null) {
            piStreamThread.interrupt();
            try {
                piStreamThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            piStreamThread = null;
        }
    }

    private void startPiStream() {
        previewView.setVisibility(View.GONE);
        piStreamView.setVisibility(View.VISIBLE);
        tvStreamStatus.setVisibility(View.VISIBLE);
        tvStreamStatus.setText(R.string.connecting_to_pi_camera);
        piStreamRunning = true;

        piStreamThread = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(streamUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(10_000);
                connection.connect();
                runOnUiThread(() -> tvStreamStatus.setText(R.string.pi_camera_live));
                MjpegInputStream mjpeg =
                        new MjpegInputStream(connection.getInputStream());
                while (piStreamRunning && !Thread.currentThread().isInterrupted()) {
                    byte[] jpegBytes = mjpeg.readJpegFrame();
                    if (jpegBytes == null) break;
                    Bitmap frame = BitmapFactory.decodeByteArray(
                            jpegBytes, 0, jpegBytes.length);
                    if (frame == null) continue;
                    runOnUiThread(() -> piStreamView.setImageBitmap(frame));
                    analyzeFrameBitmap(frame);
                }
                runOnUiThread(() -> tvStreamStatus.setText(R.string.stream_ended));

            } catch (IOException e) {
                Log.e(TAG, "Pi stream error: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    tvStreamStatus.setText(R.string.connection_failed);
                    Toast.makeText(
                            LiveObjectDetectionActivity.this,
                            getString(R.string.could_not_reach_pi_camera_ncheck),
                            Toast.LENGTH_LONG
                    ).show();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
        piStreamThread.setDaemon(true);
        piStreamThread.start();
    }

    /**
     * Runs the COCO TFLite detector on a single frame from the Pi stream.
     * Called from the background piStreamThread -- detect() is a blocking
     * CPU call, and graphicOverlay.setResults() is safe to call from any
     * thread (it uses postInvalidate() internally).
     */
    private void analyzeFrameBitmap(Bitmap bitmap) {
        if (detectionBusy) return;
        detectionBusy = true;
        try {
            List<Detection> detections = cocoDetector.detect(bitmap);
            graphicOverlay.setResults(detections, bitmap.getWidth(), bitmap.getHeight());
            if (detectionRecorder != null) detectionRecorder.onDetections(detections);
        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
        } finally {
            detectionBusy = false;
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(new ResolutionSelector.Builder()
                                .setResolutionStrategy(new ResolutionStrategy(
                                        new Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build())
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera start failed: " + e.getMessage(), e);
                runOnUiThread(() -> Toast.makeText(
                        this,
                        getString(R.string.failed_to_start_camera) + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * NOTE: imageProxy.toBitmap() requires androidx.camera:camera-core 1.3.0+.
     * If your project is on an older CameraX version, this call won't exist --
     * bump the camera-core/camera-camera2/camera-lifecycle/camera-view
     * dependencies to 1.3.0 or newer in build.gradle.
     */
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(ImageProxy imageProxy) {
        try {
            if (detectionBusy) return;

            Bitmap bitmap = imageProxy.toBitmap();
            if (bitmap == null) return;

            // toBitmap() hands back the sensor buffer as-is; the rotation needed
            // to make it upright lives in the image info. Skipping this feeds the
            // detector a sideways scene, and COCO models are trained on upright
            // images, so accuracy falls off a cliff in portrait.
            bitmap = rotateUpright(bitmap, imageProxy.getImageInfo().getRotationDegrees());

            detectionBusy = true;
            List<Detection> detections = cocoDetector.detect(bitmap);
            graphicOverlay.setResults(detections, bitmap.getWidth(), bitmap.getHeight());
            if (detectionRecorder != null) detectionRecorder.onDetections(detections);
            detectionBusy = false;
        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
            detectionBusy = false;
        } finally {
            imageProxy.close();
        }
    }

    /** Returns an upright copy, or the original when there is nothing to do. */
    private static Bitmap rotateUpright(Bitmap source, int rotationDegrees) {
        if (rotationDegrees == 0) return source;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotated = Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (rotated != source) source.recycle();
        return rotated;
    }

}