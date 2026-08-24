package com.example.pettrackerapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
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
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

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
    private ActivityResultLauncher<Intent>  pickImageLauncher;
    private ActivityResultLauncher<String>  permissionLauncher;

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

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            selectedImageUri = uri;
                            segmentedBitmap  = null;
                            imgPreview.setImageURI(uri);
                            runSubjectSegmentation(uri);
                        }
                    }
                });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        openImagePicker();
                    } else {
                        Toast.makeText(this,
                                "Permission denied. Cannot pick photo.",
                                Toast.LENGTH_SHORT).show();
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
        cardPhoto.setOnClickListener(v -> checkPhotoPermissionAndPick());
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
            Toast.makeText(this, "You must be logged in to edit a pet",
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
                    Toast.makeText(this, "Pet not found", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    editingPet = pet;
                    fillFormFrom(pet);
                }
            });
        }).start();
    }

    private void fillFormFrom(Pet pet) {
        ((TextView) findViewById(R.id.tvAddPetTitle)).setText("Edit " + pet.getName());
        ((TextView) findViewById(R.id.tvAddPetSubtitle))
                .setText("Update the details or photo, then save.");
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

    private void checkPhotoPermissionAndPick() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED) {
            openImagePicker();
        } else {
            permissionLauncher.launch(permission);
        }
    }

    private void openImagePicker() {
        Intent intent = new Intent(
                Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        pickImageLauncher.launch(intent);
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
                                "Could not remove background. Original photo will be saved.",
                                Toast.LENGTH_SHORT).show();
                    });
        } catch (IOException e) {
            Log.e(TAG, "Could not load image for segmentation", e);
            setSegmentationLoadingState(false);
            Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show();
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
                    "No subject found — original photo will be saved.",
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
                    "You must be logged in to save a pet", Toast.LENGTH_SHORT).show();
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
        btnSavePet.setText("Saving…");

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
                photoUriString = fallbackUri.toString();
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