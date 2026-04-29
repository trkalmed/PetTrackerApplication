package com.example.pettrackerapplication;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class Petmenu extends AppCompatActivity {

    // Views
    private MaterialToolbar toolbar;
    private ImageView        imgPetPhoto;
    private TextView         tvPetNameDetail;
    private TextView         tvSpeciesDetail;
    private TextView         tvBreedDetail;
    private TextView         tvWeightDetail;
    private TextView         tvSexDetail;
    private TextView         tvBirthdateDetail;
    private TextView         tvNotesDetail;
    private TextView         tvLocationText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.pet_menu);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.pet_menu), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // ── Toolbar ──────────────────────────────────────────────────────────
        toolbar = findViewById(R.id.topAppBar);
        toolbar.setNavigationOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        // ── Bind views ───────────────────────────────────────────────────────
        imgPetPhoto       = findViewById(R.id.petImage);
        tvPetNameDetail   = findViewById(R.id.petName);
        tvSpeciesDetail   = findViewById(R.id.detailSpeciesValue);
        tvBreedDetail     = findViewById(R.id.detailBreed);
        tvWeightDetail    = findViewById(R.id.detailWeight);
        tvSexDetail       = findViewById(R.id.tvSexDetail);
        tvBirthdateDetail = findViewById(R.id.tvBirthdateDetail);
        tvNotesDetail     = findViewById(R.id.tvNotesDetail);
        tvLocationText    = findViewById(R.id.locationText);

        imgPetPhoto.setImageResource(R.drawable.ic_pets_24);

        // ── Button listeners ────────────────────────────────────────────────
        MaterialButton btnTracking = findViewById(R.id.btnTracking);
        btnTracking.setOnClickListener(v -> handleTrackingClick());

        MaterialButton btnFeeding = findViewById(R.id.btnFeeding);
        btnFeeding.setOnClickListener(v ->
                Toast.makeText(this, "Feeding log — coming soon", Toast.LENGTH_SHORT).show());

        MaterialButton btnHistory = findViewById(R.id.btnHistory);
        btnHistory.setOnClickListener(v ->
                Toast.makeText(this, "Activity history — coming soon", Toast.LENGTH_SHORT).show());

        // ── Load pet from database ──────────────────────────────────────────
        long petId = getIntent().getLongExtra("pet_id", -1);
        if (petId == -1) {
            Toast.makeText(this, "No pet selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        loadPetFromDb(petId);
    }

    private void loadPetFromDb(long petId) {
        new Thread(() -> {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Please log in again", Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }

            AppDatabase db  = AppDatabase.getInstance(getApplicationContext());
            Pet         pet = db.petDao().getPetById(petId, user.getUid());

            runOnUiThread(() -> {
                if (pet == null) {
                    Toast.makeText(this, "Pet not found", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    bindPetToViews(pet);
                }
            });
        }).start();
    }

    private void bindPetToViews(Pet pet) {
        toolbar.setTitle(pet.getName());
        tvPetNameDetail.setText(pet.getName());

        tvSpeciesDetail.setText(emptyOrValue(pet.getSpecies(), "Unknown"));
        tvBreedDetail.setText(emptyOrValue(pet.getBreed(), "—"));
        tvWeightDetail.setText(
                pet.getWeightKg() != null ? pet.getWeightKg() + " kg" : "—");
        tvSexDetail.setText(emptyOrValue(pet.getSex(), "—"));
        tvBirthdateDetail.setText(emptyOrValue(pet.getBirthdate(), "—"));
        tvNotesDetail.setText(emptyOrValue(pet.getNotes(), "No notes"));

        TextView tvSteps    = findViewById(R.id.stepsText);
        TextView tvDistance = findViewById(R.id.distanceText);
        tvSteps.setText("Steps today: —");
        tvDistance.setText("Distance today: —");

        String lastSeen = pet.getLastSeen();
        tvLocationText.setText(
                (lastSeen != null && !lastSeen.isEmpty()) ? lastSeen : "Last GPS update: —");

        String photoUri = pet.getPhotoUri();
        if (photoUri != null && !photoUri.isEmpty()) {
            imgPetPhoto.setImageURI(Uri.parse(photoUri));
        } else {
            imgPetPhoto.setImageResource(R.drawable.ic_pets_24);
        }
    }

    // ── Pi connection / tracking ────────────────────────────────────────────

    private void handleTrackingClick() {
        if (PiConnectionManager.hasSavedIp(this)) {
            launchTracking(PiConnectionManager.getStreamUrl(this));
        } else {
            showIpDialog();
        }
    }

    private void showIpDialog() {
        EditText input = new EditText(this);
        input.setHint("e.g.  192.168.1.42");
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setPadding(48, 32, 48, 32);

        String existing = PiConnectionManager.getSavedIp(this);
        if (!existing.isEmpty()) {
            input.setText(existing);
            input.setSelection(existing.length());
        }

        new AlertDialog.Builder(this)
                .setTitle("Raspberry Pi IP address")
                .setMessage(
                        "Enter your Pi's local IP address to connect to the live camera stream.")
                .setView(input)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    if (ip.isEmpty()) {
                        Toast.makeText(this,
                                "IP address cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    PiConnectionManager.saveIp(this, ip);
                    launchTracking(PiConnectionManager.getStreamUrl(this));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchTracking(String streamUrl) {
        Intent intent = new Intent(this, LiveObjectDetectionActivity.class);
        intent.putExtra("stream_url", streamUrl);
        startActivity(intent);
    }

    private String emptyOrValue(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value;
    }
}