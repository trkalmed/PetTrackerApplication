package com.example.pettrackerapplication;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Schema export is on, so version 2 is recorded in app/schemas as the baseline
 * every future migration is written against. Bump the version and Room will
 * refuse to build until a Migration exists — which is the point.
 */
@Database(entities = {Pet.class}, version = 2, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;
    public abstract PetDao petDao();
    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "pets_db")
                            // Version 1 predates schema export, so there is no
                            // baseline to migrate it from — wiping is the only
                            // option there. Every later version must ship a real
                            // Migration; a blanket fallback would silently destroy
                            // a user's pets on the next schema change.
                            .fallbackToDestructiveMigrationFrom(1)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
