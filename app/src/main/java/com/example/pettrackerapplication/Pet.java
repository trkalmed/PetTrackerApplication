package com.example.pettrackerapplication;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "pets")
public class Pet {

    @PrimaryKey(autoGenerate = true)
    private long id;

    @NonNull
    private String name;

    private String species;
    private String breed;
    private Double weightKg;
    private String sex;
    private String birthdate;
    private String notes;
    private String photoUri;
    private String lastSeen;


    private Integer dailyFoodGrams;

    @NonNull
    private String ownerId;

    public Pet(@NonNull String name,
               String species,
               String breed,
               Double weightKg,
               String sex,
               String birthdate,
               String notes,
               String photoUri,
               String lastSeen,
               @NonNull String ownerId) {
        this.name = name;
        this.species = species;
        this.breed = breed;
        this.weightKg = weightKg;
        this.sex = sex;
        this.birthdate = birthdate;
        this.notes = notes;
        this.photoUri = photoUri;
        this.lastSeen = lastSeen;
        this.ownerId = ownerId;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    @NonNull
    public String getName() { return name; }
    public void setName(@NonNull String name) { this.name = name; }

    public String getSpecies() { return species; }
    public void setSpecies(String species) { this.species = species; }

    public String getBreed() { return breed; }
    public void setBreed(String breed) { this.breed = breed; }

    public Double getWeightKg() { return weightKg; }
    public void setWeightKg(Double weightKg) { this.weightKg = weightKg; }

    public String getSex() { return sex; }
    public void setSex(String sex) { this.sex = sex; }

    public String getBirthdate() { return birthdate; }
    public void setBirthdate(String birthdate) { this.birthdate = birthdate; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getPhotoUri() { return photoUri; }
    public void setPhotoUri(String photoUri) { this.photoUri = photoUri; }

    public String getLastSeen() { return lastSeen; }
    public void setLastSeen(String lastSeen) { this.lastSeen = lastSeen; }

    @NonNull
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(@NonNull String ownerId) { this.ownerId = ownerId; }

    public Integer getDailyFoodGrams() { return dailyFoodGrams; }
    public void setDailyFoodGrams(Integer dailyFoodGrams) {
        this.dailyFoodGrams = dailyFoodGrams;
    }
}
