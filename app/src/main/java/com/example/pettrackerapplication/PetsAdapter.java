package com.example.pettrackerapplication;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

public class PetsAdapter extends RecyclerView.Adapter<PetsAdapter.PetViewHolder> {

    public interface OnPetClickListener {
        void onPetClick(Pet pet);
    }

    private List<Pet> pets = new ArrayList<>();
    private final OnPetClickListener listener;

    public PetsAdapter(List<Pet> pets, OnPetClickListener listener) {
        if (pets != null) {
            this.pets = pets;
        }
        this.listener = listener;
    }

    public void setPets(List<Pet> pets) {
        this.pets = pets != null ? pets : new ArrayList<>();
        notifyDataSetChanged();
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
        holder.bind(pet, listener);
    }

    @Override
    public int getItemCount() {
        return pets.size();
    }

    static class PetViewHolder extends RecyclerView.ViewHolder {

        TextView tvName;
        TextView tvLastSeen;
        ImageView imgPet;

        PetViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.Petname_id);
            tvLastSeen = itemView.findViewById(R.id.lastseen_id);
            imgPet = itemView.findViewById(R.id.imgPet);
        }

        void bind(final Pet pet, final OnPetClickListener listener) {
            tvName.setText(pet.getName());
            String lastSeen = pet.getLastSeen();
            tvLastSeen.setText(lastSeen == null || lastSeen.isEmpty() ? "Last seen: —" : lastSeen);

            String photoUri = pet.getPhotoUri();
            if (photoUri != null && !photoUri.isEmpty()) {
                Glide.with(itemView.getContext())
                        .load(Uri.parse(photoUri))
                        .centerCrop()
                        .placeholder(R.drawable.ic_pets_24)
                        .into(imgPet);
            } else {
                imgPet.setImageResource(R.drawable.ic_pets_24);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onPetClick(pet);
            });
        }
    }
}
