package com.example.pettrackerapplication;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import android.app.DatePickerDialog;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Locale;

public class AddPetActivity extends ThemedActivity {
    private static final String TAG = "AddPetActivity";
    private MaterialCardView      cardPhoto;
    private ImageView             imgPreview;
    private View                  vSegmentingOverlay;
    private ProgressBar           progressSegmentation;
    private TextInputEditText     etName, etBreed, etWeight, etBirthdate, etNotes;
    private AutoCompleteTextView  autoSpecies, autoSex;
    private MaterialButton        btnSavePet;
    private Uri    selectedImageUri = null;
    private Bitmap segmentedBitmap  = null;
    private SubjectSegmenter subjectSegmenter;
    private ActivityResultLauncher<PickVisualMediaRequest> pickImageLauncher;

    private long editingPetId = -1L;
    private Pet  editingPet;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.add_pet_menu);
        cardPhoto            = findViewById(R.id.cardPhoto);
        imgPreview           = findViewById(R.id.imgPreview);
        vSegmentingOverlay   = findViewById(R.id.vSegmentingOverlay);
        progressSegmentation = findViewById(R.id.progressSegmentation);
        etName               = findViewById(R.id.etPetName);
        autoSpecies          = findViewById(R.id.autoSpecies);
        etBreed              = findViewById(R.id.etBreed);
        etWeight             = findViewById(R.id.etWeight);
        autoSex              = findViewById(R.id.autoSex);
        etBirthdate          = findViewById(R.id.etBirthdate);
        etNotes              = findViewById(R.id.etNotes);
        btnSavePet           = findViewById(R.id.btnSavePet);

        subjectSegmenter = SubjectSegmentation.getClient(
                new SubjectSegmenterOptions.Builder()
                        .enableForegroundBitmap()
                        .build());

        // The system photo picker needs no storage permission.
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null) {
                        selectedImageUri = uri;
                        segmentedBitmap  = null;
                        PhotoTint.clearFor(imgPreview);
                        Glide.with(this).load(uri).centerCrop().into(imgPreview);
                        runSubjectSegmentation(uri);
                    }
                });


        String[] speciesItems = getResources().getStringArray(R.array.species_options);
        autoSpecies.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, speciesItems));
        autoSpecies.setOnClickListener(v -> autoSpecies.showDropDown());
        autoSpecies.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) autoSpecies.showDropDown();
        });

        String[] sexItems = getResources().getStringArray(R.array.sex_options);
        autoSex.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, sexItems));
        autoSex.setOnClickListener(v -> autoSex.showDropDown());
        autoSex.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) autoSex.showDropDown();
        });
        etBirthdate.setFocusable(false);
        etBirthdate.setKeyListener(null);
        etBirthdate.setOnClickListener(v -> showDatePicker());
        cardPhoto.setOnClickListener(v -> pickImageLauncher.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
        btnSavePet.setOnClickListener(v -> savePet());

        editingPetId = getIntent().getLongExtra("pet_id", -1L);
        if (editingPetId != -1L) {
            loadPetForEditing(editingPetId);
        }
    }

    private void loadPetForEditing(long petId) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, getString(R.string.you_must_be_logged_in_to),
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        String ownerId = user.getUid();

        btnSavePet.setEnabled(false);
        new Thread(() -> {
            Pet pet = AppDatabase.getInstance(getApplicationContext())
                    .petDao().getPetById(petId, ownerId);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnSavePet.setEnabled(true);
                if (pet == null) {
                    Toast.makeText(this, getString(R.string.pet_not_found), Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    editingPet = pet;
                    fillFormFrom(pet);
                }
            });
        }).start();
    }

    private void fillFormFrom(Pet pet) {
        ((TextView) findViewById(R.id.tvAddPetTitle))
                .setText(getString(R.string.edit_pet_title, pet.getName()));
        ((TextView) findViewById(R.id.tvAddPetSubtitle))
                .setText(R.string.update_the_details_or_photo_then);
        btnSavePet.setText(R.string.save_changes);

        etName.setText(pet.getName());
        autoSpecies.setText(pet.getSpecies(), false);
        etBreed.setText(pet.getBreed());
        etWeight.setText(pet.getWeightKg() != null ? String.valueOf(pet.getWeightKg()) : "");
        autoSex.setText(pet.getSex(), false);
        etBirthdate.setText(pet.getBirthdate());
        etNotes.setText(pet.getNotes());

        String photoUri = pet.getPhotoUri();
        if (photoUri != null && !photoUri.isEmpty()) {
            PhotoTint.clearFor(imgPreview);
            Glide.with(this).load(Uri.parse(photoUri)).centerCrop().into(imgPreview);
        }
    }

    private void showDatePicker() {
        Calendar cal   = Calendar.getInstance();
        int      year  = cal.get(Calendar.YEAR);
        int      month = cal.get(Calendar.MONTH);
        int      day   = cal.get(Calendar.DAY_OF_MONTH);

        new DatePickerDialog(this, (view, y, m, d) -> {
            etBirthdate.setText(String.format(
                    Locale.getDefault(), "%02d/%02d/%04d", d, m + 1, y));
        }, year, month, day).show();
    }


    private void runSubjectSegmentation(Uri imageUri) {
        setSegmentationLoadingState(true);
        try {
            InputImage image = InputImage.fromFilePath(this, imageUri);
            subjectSegmenter
                    .process(image)
                    .addOnSuccessListener(this::handleSegmentationResult)
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Segmentation failed", e);
                        setSegmentationLoadingState(false);
                        Toast.makeText(this,
                                getString(R.string.could_not_remove_background_original_photo),
                                Toast.LENGTH_SHORT).show();
                    });
        } catch (IOException e) {
            Log.e(TAG, "Could not load image for segmentation", e);
            setSegmentationLoadingState(false);
            Toast.makeText(this, getString(R.string.could_not_load_image), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSegmentationResult(SubjectSegmentationResult result) {
        setSegmentationLoadingState(false);

        Bitmap foreground = result.getForegroundBitmap();
        if (foreground != null && hasVisibleSubject(foreground)) {
            segmentedBitmap = foreground;
            imgPreview.setImageBitmap(foreground);
        } else {
            Toast.makeText(this,
                    getString(R.string.no_subject_found_original_photo_will),
                    Toast.LENGTH_SHORT).show();
        }
    }

    static boolean hasVisibleSubject(Bitmap bitmap) {
        final int samplesPerAxis = 64;
        final int minAlpha = 32;
        final double minVisibleFraction = 0.01;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return false;

        if (!bitmap.hasAlpha()) {
            return true;
        }

        int rowStep = Math.max(1, height / samplesPerAxis);
        int colStep = Math.max(1, width / samplesPerAxis);

        int[] row = new int[width];
        int sampled = 0;
        int visible = 0;
        for (int y = 0; y < height; y += rowStep) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x += colStep) {
                sampled++;
                if (((row[x] >>> 24) & 0xFF) >= minAlpha) visible++;
            }
        }

        return sampled > 0 && (double) visible / sampled >= minVisibleFraction;
    }

    private void setSegmentationLoadingState(boolean loading) {
        vSegmentingOverlay.setVisibility(  loading ? View.VISIBLE : View.GONE);
        progressSegmentation.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSavePet.setEnabled(!loading);
        btnSavePet.setText(loading
                ? "Processing photo…"
                : getString(editingPetId != -1L
                        ? R.string.save_changes
                        : R.string.save_pet));
    }

    private Uri saveBitmapToFile(Bitmap bitmap) {
        try {
            File dir = new File(getFilesDir(), "pet_photos");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "seg_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return Uri.fromFile(file);
        } catch (IOException e) {
            Log.e(TAG, "Failed to save segmented bitmap", e);
            return null;
        }
    }

    private Uri copyPickedImageToFile(Uri source) {
        try {
            File dir = new File(getFilesDir(), "pet_photos");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "pet_" + System.currentTimeMillis() + ".jpg");

            try (InputStream in = getContentResolver().openInputStream(source);
                 FileOutputStream out = new FileOutputStream(file)) {
                if (in == null) return null;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return Uri.fromFile(file);
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy picked image", e);
            return null;
        }
    }

    private void savePet() {
        String name      = etName.getText()      != null ? etName.getText().toString().trim()      : "";
        String species   = autoSpecies.getText() != null ? autoSpecies.getText().toString().trim() : "";
        String breed     = etBreed.getText()     != null ? etBreed.getText().toString().trim()     : "";
        String weightStr = etWeight.getText()    != null ? etWeight.getText().toString().trim()    : "";
        String sex       = autoSex.getText()     != null ? autoSex.getText().toString().trim()     : "";
        String birthdate = etBirthdate.getText() != null ? etBirthdate.getText().toString().trim() : "";
        String notes     = etNotes.getText()     != null ? etNotes.getText().toString().trim()     : "";

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this,
                    getString(R.string.you_must_be_logged_in_to_2), Toast.LENGTH_SHORT).show();
            return;
        }
        String ownerId = user.getUid();

        if (TextUtils.isEmpty(name)) {
            etName.setError("Name is required");
            etName.requestFocus();
            return;
        }

        Double weight = null;
        if (!weightStr.isEmpty()) {
            try {
                weight = Double.parseDouble(weightStr);
            } catch (NumberFormatException e) {
                etWeight.setError("Invalid number");
                etWeight.requestFocus();
                return;
            }
        }

        final Double  finalWeight   = weight;
        final boolean hasSegmented  = segmentedBitmap != null;
        final Bitmap  bitmapToSave  = segmentedBitmap;
        final Uri     fallbackUri   = selectedImageUri;

        btnSavePet.setEnabled(false);
        btnSavePet.setText(R.string.saving);

        final boolean editing     = editingPetId != -1L && editingPet != null;
        final Pet     existingPet = editingPet;

        new Thread(() -> {
            String photoUriString = null;
            if (hasSegmented) {
                Uri segUri = saveBitmapToFile(bitmapToSave);
                photoUriString = (segUri != null)
                        ? segUri.toString()
                        : (fallbackUri != null ? fallbackUri.toString() : null);
            } else if (fallbackUri != null) {
                Uri localUri = copyPickedImageToFile(fallbackUri);
                photoUriString = localUri != null ? localUri.toString() : null;
            } else if (editing) {
                photoUriString = existingPet.getPhotoUri();
            }

            AppDatabase db = AppDatabase.getInstance(getApplicationContext());

            if (editing) {
                Pet updated = new Pet(
                        name, species, breed, finalWeight,
                        sex, birthdate, notes,
                        photoUriString,
                        existingPet.getLastSeen(),
                        ownerId);
                updated.setId(existingPet.getId());
                // The update replaces the whole row, so carry over the fields this form does not edit.
                updated.setDailyFoodGrams(existingPet.getDailyFoodGrams());
                db.petDao().update(updated);
            } else {
                db.petDao().insert(new Pet(
                        name, species, breed, finalWeight,
                        sex, birthdate, notes,
                        photoUriString,
                        "Last seen: —",
                        ownerId));
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(AddPetActivity.this,
                        editing ? "Changes saved" : "Pet saved",
                        Toast.LENGTH_SHORT).show();
                finish();
            });
        }).start();
    }
}