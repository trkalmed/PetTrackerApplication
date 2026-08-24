package com.example.pettrackerapplication;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PetDao {

    @Insert
    long insert(Pet pet);

    @Update
    void update(Pet pet);

    /** Ownership is checked by the caller; kept for the delete-by-id path. */
    @Query("DELETE FROM pets WHERE id = :id AND ownerId = :ownerId")
    int deleteById(long id, String ownerId);


    @Query("SELECT * FROM pets WHERE ownerId = :ownerId ORDER BY name")
    List<Pet> getPetsForOwner(String ownerId);


    @Query("SELECT * FROM pets WHERE id = :id AND ownerId = :ownerId LIMIT 1")
    Pet getPetById(long id, String ownerId);

    @Delete
    void delete(Pet pet);
}
