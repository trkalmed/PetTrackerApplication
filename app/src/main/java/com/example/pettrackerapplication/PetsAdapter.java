package com.example.pettrackerapplication;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

public class PetsAdapter extends RecyclerView.Adapter<PetsAdapter.PetViewHolder> {

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
