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
import androidx.appcompat.app.AppCompatActivity;

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

public class AddPetActivity extends AppCompatActivity {
    private static final String TAG = "AddPetActivity";
    private MaterialCardView      cardPhoto;
    private ImageView             imgPreview;
    private View                  vSegmentingOverlay;   // dim layer during ML processing
    private ProgressBar           progressSegmentation; // spinner during ML processing
    private TextInputEditText     etName, etBreed, etWeight, etBirthdate, etNotes;
    private AutoCompleteTextView  autoSpecies, autoSex;
    private MaterialButton        btnSavePet;
    private Uri    selectedImageUri = null;
    private Bitmap segmentedBitmap  = null;
    private SubjectSegmenter subjectSegmenter;
    private ActivityResultLauncher<PickVisualMediaRequest> pickImageLauncher;

    /** -1 = adding a new pet; anything else = editing that pet. */
    private long editingPetId = -1L;
    /** The row being edited, loaded before the form is filled in. */
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

        // The photo picker needs no storage permission at all: the system picker
        // runs out of process and hands back one URI the user chose. This is why
        // READ_MEDIA_IMAGES and READ_EXTERNAL_STORAGE are gone from the manifest.
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null) {
                        selectedImageUri = uri;
                        segmentedBitmap  = null;
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
                        // ImageOnly is a Kotlin object, hence INSTANCE from Java.
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
        btnSavePet.setOnClickListener(v -> savePet());

        editingPetId = getIntent().getLongExtra("pet_id", -1L);
        if (editingPetId != -1L) {
            loadPetForEditing(editingPetId);
        }
    }

    // ── Edit mode ───────────────────────────────────────────────────────────

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
            // Keep the saved photo unless the user picks a new one. Not assigned
            // to selectedImageUri: that field means "a new pick to process".
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
        if (foreground != null) {
            segmentedBitmap = foreground;
            imgPreview.setImageBitmap(foreground);
        } else {
            Toast.makeText(this,
                    getString(R.string.no_subject_found_original_photo_will),
                    Toast.LENGTH_SHORT).show();
        }
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

    /**
     * Copies a picked image into app storage and returns the local URI.
     *
     * The picker grants read access to its URI only for this task's lifetime, so
     * persisting that URI in the database gives a photo that silently stops
     * loading later. Segmented photos were already written to disk; this does the
     * same for the original when segmentation finds no subject or fails.
     */
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
                photoUriString = existingPet.getPhotoUri();   // keep the saved photo
            }

            AppDatabase db = AppDatabase.getInstance(getApplicationContext());

            if (editing) {
                Pet updated = new Pet(
                        name, species, breed, finalWeight,
                        sex, birthdate, notes,
                        photoUriString,
                        existingPet.getLastSeen(),   // not ours to reset
                        ownerId);
                updated.setId(existingPet.getId());
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