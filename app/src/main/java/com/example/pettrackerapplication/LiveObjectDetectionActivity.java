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
import java.util.concurrent.TimeUnit;
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
            tvCameraName.setText(R.string.pi_camera);
            graphicOverlay.setScaleMode(BoxMapper.ScaleMode.FIT_CENTER);
            detectionRecorder = createRecorder(DetectionEvent.SOURCE_PI);
            startPiStream();
            loadPiCameras();
        } else {
            tvCameraName.setText(R.string.phone_camera);
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

    private DetectionRecorder createRecorder(String source) {
        long petId = Prefs.getActivePetId(this);
        if (petId <= 0) {
            Log.i(TAG, "No active pet; detections will not be recorded");
            return null;
        }
        return new DetectionRecorder(this, petId, source);
    }

    private final Object detectorLock = new Object();
    private volatile boolean detectorClosed = false;

    private final ExecutorService detectionExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (detectionRecorder != null) detectionRecorder.close();

        // Stop the frame producers and wait for them before freeing the detector.
        stopPiStream();
        detectionExecutor.shutdownNow();
        try {
            detectionExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
            try {
                cameraExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (detectorLock) {
            detectorClosed = true;
            if (cocoDetector != null) cocoDetector.close();
        }
    }


    private final java.util.List<String> piCameras = new java.util.ArrayList<>();
    private int piCameraIndex = 0;

    private void loadPiCameras() {
        PiClient client = PiClient.forSavedPi(this);
        if (client == null) return;

        new Thread(() -> {
            final java.util.List<String> found = new java.util.ArrayList<>();
            try {
                for (java.util.Map.Entry<String, Boolean> entry
                        : client.health().camerasOrLegacy().entrySet()) {
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

                    if (!detectionBusy) {
                        detectionBusy = true;
                        detectionExecutor.execute(() -> analyzeFrameBitmap(frame));
                    }
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

    private void analyzeFrameBitmap(Bitmap bitmap) {
        try {
            // Held for the whole call so onDestroy cannot free the detector during inference.
            synchronized (detectorLock) {
                if (detectorClosed || cocoDetector == null) return;
                List<Detection> detections = cocoDetector.detect(bitmap);
                graphicOverlay.setResults(detections, bitmap.getWidth(), bitmap.getHeight());
                if (detectionRecorder != null) detectionRecorder.onDetections(detections);
            }
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

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(ImageProxy imageProxy) {
        try {
            if (detectionBusy) return;

            Bitmap bitmap = imageProxy.toBitmap();
            if (bitmap == null) return;

            // Rotate the frame upright before detection.
            bitmap = rotateUpright(bitmap, imageProxy.getImageInfo().getRotationDegrees());

            detectionBusy = true;
            synchronized (detectorLock) {
                if (detectorClosed || cocoDetector == null) return;
                List<Detection> detections = cocoDetector.detect(bitmap);
                graphicOverlay.setResults(detections, bitmap.getWidth(), bitmap.getHeight());
                if (detectionRecorder != null) detectionRecorder.onDetections(detections);
            }
            detectionBusy = false;
        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
            detectionBusy = false;
        } finally {
            imageProxy.close();
        }
    }

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