package com.example.pettrackerapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;

/**
 * My pets. Tapping a row picks the pet the dashboard tracks; the overflow on
 * each row holds Details / Edit / Delete.
 */
public class PetListActivity extends ThemedActivity implements Petmenu.PetsAdapter.OnPetClickListener {

    private RecyclerView rvPets;
    private View         emptyPets;
    private Petmenu.PetsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pet_list);

        findViewById(R.id.btnBack).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        ((FloatingActionButton) findViewById(R.id.fabAddPetFromList)).setOnClickListener(v ->
                startActivity(new Intent(this, AddPetActivity.class)));

        rvPets    = findViewById(R.id.rvPets);
        emptyPets = findViewById(R.id.emptyPets);

        adapter = new Petmenu.PetsAdapter(new ArrayList<>(), this);
        rvPets.setLayoutManager(new LinearLayoutManager(this));
        rvPets.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();   // a pet may have been added, edited or deleted since we were last shown
    }

    // ── Data ────────────────────────────────────────────────────────────────

    private void load() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        String ownerId = user.getUid();

        new Thread(() -> {
            List<Pet> pets = AppDatabase
                    .getInstance(getApplicationContext())
                    .petDao()
                    .getPetsForOwner(ownerId);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                render(pets);
            });
        }).start();
    }

    private void render(List<Pet> pets) {
        List<Pet> safe = pets != null ? pets : new ArrayList<>();

        boolean empty = safe.isEmpty();
        emptyPets.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvPets.setVisibility(empty ? View.GONE : View.VISIBLE);

        long active = Prefs.getActivePetId(this);

        // The stored active pet can be stale — deleted, or belonging to a
        // previous account. Fall back to the first pet so the dashboard always
        // has something to show.
        if (!empty && !containsId(safe, active)) {
            active = safe.get(0).getId();
            Prefs.setActivePetId(this, active);
        } else if (empty) {
            Prefs.clearActivePetId(this);
        }

        adapter.setPets(safe, active);
    }

    private static boolean containsId(List<Pet> pets, long id) {
        for (Pet p : pets) {
            if (p.getId() == id) return true;
        }
        return false;
    }

    // ── Row actions ─────────────────────────────────────────────────────────

    @Override
    public void onPetClick(Pet pet) {
        Prefs.setActivePetId(this, pet.getId());
        Toast.makeText(this, getString(R.string.now_tracking_pet, pet.getName()),
                Toast.LENGTH_SHORT).show();
        finish();   // straight back to the dashboard, which reloads in onResume
    }

    @Override
    public void onPetMenuClick(Pet pet, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.action_details);
        menu.getMenu().add(0, 2, 1, R.string.action_edit);
        menu.getMenu().add(0, 3, 2, R.string.action_delete);

        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    startActivity(new Intent(this, Petmenu.class)
                            .putExtra("pet_id", pet.getId()));
                    return true;
                case 2:
                    startActivity(new Intent(this, AddPetActivity.class)
                            .putExtra("pet_id", pet.getId()));
                    return true;
                case 3:
                    confirmDelete(pet);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void confirmDelete(Pet pet) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_pet_title, pet.getName()))
                .setMessage(R.string.delete_pet_message)
                .setPositiveButton(R.string.action_delete, (d, w) -> delete(pet))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void delete(Pet pet) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        String ownerId = user.getUid();

        new Thread(() -> {
            // Delete by id + ownerId so a stale Pet object cannot remove
            // somebody else's row.
            int removed = AppDatabase
                    .getInstance(getApplicationContext())
                    .petDao()
                    .deleteById(pet.getId(), ownerId);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (removed > 0 && Prefs.getActivePetId(this) == pet.getId()) {
                    Prefs.clearActivePetId(this);   // render() picks a new one
                }
                Toast.makeText(this,
                        removed > 0 ? getString(R.string.pet_deleted, pet.getName())
                                    : getString(R.string.could_not_delete_pet),
                        Toast.LENGTH_SHORT).show();
                load();
            });
        }).start();
    }
}
