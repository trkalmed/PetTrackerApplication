package com.example.pettrackerapplication;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;

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
    private TextView         tvFoodToday;
    private TextView         tvRoomsToday;

    /** The loaded pet, so Edit and Delete know what they are acting on. */
    private Pet currentPet;
    private long petId = -1L;

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

        // Edit and Delete stay hidden until a pet has actually loaded — acting on
        // a pet that is not there yet is the sort of thing that deletes the wrong row.
        toolbar.inflateMenu(R.menu.menu_pet_detail);
        setDetailMenuVisible(false);
        toolbar.setOnMenuItemClickListener(item -> {
            if (currentPet == null) return false;
            int id = item.getItemId();
            if (id == R.id.action_edit_pet) {
                startActivity(new Intent(this, AddPetActivity.class)
                        .putExtra("pet_id", currentPet.getId()));
                return true;
            }
            if (id == R.id.action_delete_pet) {
                confirmDelete(currentPet);
                return true;
            }
            return false;
        });

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
        tvFoodToday       = findViewById(R.id.tvFoodToday);
        tvRoomsToday      = findViewById(R.id.tvRoomsToday);

        imgPetPhoto.setImageResource(R.drawable.ic_pets_24);

        // ── Button listeners ────────────────────────────────────────────────
        MaterialButton btnTracking = findViewById(R.id.btnTracking);
        btnTracking.setOnClickListener(v -> handleTrackingClick());

        MaterialButton btnFeeding = findViewById(R.id.btnFeeding);
        btnFeeding.setOnClickListener(v ->
                startActivity(new Intent(this, FeedingActivity.class)));

        MaterialButton btnHistory = findViewById(R.id.btnHistory);
        btnHistory.setOnClickListener(v ->
                Toast.makeText(this, getString(R.string.activity_history_coming_soon), Toast.LENGTH_SHORT).show());

        // ── Load pet from database ──────────────────────────────────────────
        petId = getIntent().getLongExtra("pet_id", -1);
        if (petId == -1) {
            Toast.makeText(this, getString(R.string.no_pet_selected), Toast.LENGTH_SHORT).show();
            finish();
        }
        // The load happens in onResume so returning from an edit shows the new values.
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (petId != -1L) loadPetFromDb(petId);
    }

    private void loadPetFromDb(long petId) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, getString(R.string.please_log_in_again), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        String ownerId = user.getUid();

        new Thread(() -> {
            AppDatabase db  = AppDatabase.getInstance(getApplicationContext());
            Pet         pet = db.petDao().getPetById(petId, ownerId);

            // Read the stats here too — bindPetToViews runs on the UI thread,
            // and forPetToday queries the database.
            final DashboardStats stats = pet == null
                    ? null : DashboardStats.forPetToday(getApplicationContext(), pet);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                if (pet == null) {
                    Toast.makeText(this, getString(R.string.pet_not_found), Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    bindPetToViews(pet, stats);
                }
            });
        }).start();
    }

    private void setDetailMenuVisible(boolean visible) {
        toolbar.getMenu().setGroupVisible(0, visible);
    }

    private void confirmDelete(Pet pet) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_pet_title, pet.getName()))
                .setMessage(R.string.delete_pet_message)
                .setPositiveButton(R.string.action_delete, (d, w) -> deletePet(pet))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void deletePet(Pet pet) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        String ownerId = user.getUid();

        new Thread(() -> {
            int removed = AppDatabase.getInstance(getApplicationContext())
                    .petDao().deleteById(pet.getId(), ownerId);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (removed > 0) {
                    if (Prefs.getActivePetId(this) == pet.getId()) {
                        // The dashboard re-picks on its next load.
                        Prefs.clearActivePetId(this);
                    }
                    Toast.makeText(this, getString(R.string.pet_deleted, pet.getName()),
                            Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(this, getString(R.string.could_not_delete_pet),
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void bindPetToViews(Pet pet, DashboardStats stats) {
        currentPet = pet;
        setDetailMenuVisible(true);
        toolbar.setTitle(pet.getName());
        tvPetNameDetail.setText(pet.getName());

        tvSpeciesDetail.setText(emptyOrValue(pet.getSpecies(), R.string.unknown));
        tvBreedDetail.setText(emptyOrValue(pet.getBreed(), R.string.em_dash));
        tvWeightDetail.setText(
                pet.getWeightKg() != null ? pet.getWeightKg() + " kg" : "—");
        tvSexDetail.setText(emptyOrValue(pet.getSex(), R.string.em_dash));
        tvBirthdateDetail.setText(emptyOrValue(pet.getBirthdate(), R.string.em_dash));
        tvNotesDetail.setText(emptyOrValue(pet.getNotes(), R.string.no_notes_value));

        // These two rows were repurposed by the dashboard redesign: steps and
        // distance are gone (no hardware produces them), replaced by grams from
        // the feeder and minutes from the room sensors. Grams are real now;
        // rooms stay empty until motion sensors exist.
        tvFoodToday.setText(stats.gramsEaten == null
                ? "Food today: no feeder paired"
                : "Food today: " + stats.gramsEaten + " g");

        tvRoomsToday.setText(stats.rooms.isEmpty()
                ? "Rooms visited today: no sensors reporting"
                : "Rooms visited today: " + stats.rooms.size());

        String lastSeen = pet.getLastSeen();
        tvLocationText.setText(
                (lastSeen != null && !lastSeen.isEmpty()) ? lastSeen : "Last GPS update: —");

        String photoUri = pet.getPhotoUri();
        if (photoUri != null && !photoUri.isEmpty()) {
            Glide.with(this)
                    .load(Uri.parse(photoUri))
                    .centerCrop()
                    .placeholder(R.drawable.ic_pets_24)
                    .into(imgPetPhoto);
        } else {
            Glide.with(this).clear(imgPetPhoto);
            imgPetPhoto.setImageResource(R.drawable.ic_pets_24);
        }
    }

    // ── Pi connection / tracking ────────────────────────────────────────────

    private void handleTrackingClick() {
        // Unlike the bottom-nav Live tab, this button is explicitly about the Pi,
        // so ask for the IP rather than silently falling back to the phone camera.
        if (PiConnectionManager.hasSavedIp(this)) {
            launchTracking();
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
                .setTitle(R.string.pi_ip_dialog_title)
                .setMessage(
                        "Enter your Pi's local IP address to connect to the live camera stream.")
                .setView(input)
                .setPositiveButton(R.string.action_connect, (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    if (ip.isEmpty()) {
                        Toast.makeText(this,
                                getString(R.string.ip_address_cannot_be_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    PiConnectionManager.saveIp(this, ip);
                    launchTracking();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void launchTracking() {
        startActivity(PiConnectionManager.streamIntent(this));
    }

    /** The value, or the given fallback resource when it is blank. */
    private String emptyOrValue(String value, @StringRes int fallbackRes) {
        if (value == null || value.trim().isEmpty()) return getString(fallbackRes);
        return value;
    }

    public static class PetsAdapter extends RecyclerView.Adapter<PetsAdapter.PetViewHolder> {

        /** Row tap selects the pet; the overflow opens the per-pet menu. */
        public interface OnPetClickListener {
            void onPetClick(Pet pet);
            void onPetMenuClick(Pet pet, View anchor);
        }

        private List<Pet> pets = new ArrayList<>();
        private final OnPetClickListener listener;

        /** Which pet the dashboard is tracking, so the row can say so. */
        private long activePetId = -1L;

        public PetsAdapter(List<Pet> pets, OnPetClickListener listener) {
            if (pets != null) {
                this.pets = pets;
            }
            this.listener = listener;
        }

        public void setPets(List<Pet> newPets, long activePetId) {
            List<Pet> incoming = newPets != null ? newPets : new ArrayList<>();
            long previousActive = this.activePetId;
            this.activePetId = activePetId;

            // DiffUtil rather than notifyDataSetChanged: the list is edited in place
            // (delete, rename, change which pet is active) and a blanket invalidate
            // kills the animations and rebinds every visible row.
            List<Pet> old = this.pets;
            DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return old.size(); }
                @Override public int getNewListSize() { return incoming.size(); }

                @Override
                public boolean areItemsTheSame(int oldPos, int newPos) {
                    return old.get(oldPos).getId() == incoming.get(newPos).getId();
                }

                @Override
                public boolean areContentsTheSame(int oldPos, int newPos) {
                    Pet a = old.get(oldPos), b = incoming.get(newPos);
                    return same(a.getName(), b.getName())
                            && same(a.getSpecies(), b.getSpecies())
                            && same(a.getLastSeen(), b.getLastSeen())
                            && same(a.getPhotoUri(), b.getPhotoUri())
                            && (a.getId() == previousActive) == (b.getId() == activePetId);
                }
            });

            this.pets = incoming;
            result.dispatchUpdatesTo(this);
        }

        private static boolean same(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        @NonNull
        @Override
        public PetViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pet, parent, false);
            return new PetViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PetViewHolder holder, int position) {
            Pet pet = pets.get(position);
            holder.bind(pet, listener, pet.getId() == activePetId);
        }

        @Override
        public int getItemCount() {
            return pets.size();
        }

        static class PetViewHolder extends RecyclerView.ViewHolder {

            TextView  tvName;
            TextView  tvSpecies;
            TextView  tvLastSeen;
            TextView  tagActive;
            ImageView imgPet;
            View      btnMenu;

            PetViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName     = itemView.findViewById(R.id.Petname_id);
                tvSpecies  = itemView.findViewById(R.id.speciesText);
                tvLastSeen = itemView.findViewById(R.id.lastseen_id);
                tagActive  = itemView.findViewById(R.id.tagActive);
                imgPet     = itemView.findViewById(R.id.imgPet);
                btnMenu    = itemView.findViewById(R.id.btnPetMenu);
            }

            void bind(final Pet pet, final OnPetClickListener listener, boolean isActive) {
                tvName.setText(pet.getName());

                String species = pet.getSpecies();
                tvSpecies.setText(species == null || species.isEmpty()
                        ? itemView.getContext().getString(R.string.pet_profile) : species);

                String lastSeen = pet.getLastSeen();
                tvLastSeen.setText(lastSeen == null || lastSeen.isEmpty()
                        ? itemView.getContext().getString(R.string.last_seen_unknown) : lastSeen);

                tagActive.setVisibility(isActive ? View.VISIBLE : View.GONE);

                String photoUri = pet.getPhotoUri();
                if (photoUri != null && !photoUri.isEmpty()) {
                    Glide.with(itemView.getContext())
                            .load(Uri.parse(photoUri))
                            .centerCrop()
                            .placeholder(R.drawable.ic_pets_24)
                            .into(imgPet);
                } else {
                    Glide.with(itemView.getContext()).clear(imgPet);
                    imgPet.setImageResource(R.drawable.ic_pets_24);
                }

                itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onPetClick(pet);
                });
                btnMenu.setOnClickListener(v -> {
                    if (listener != null) listener.onPetMenuClick(pet, v);
                });
            }
        }
    }
}