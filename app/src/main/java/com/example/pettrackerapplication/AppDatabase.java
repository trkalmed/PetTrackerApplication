package com.example.pettrackerapplication;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * Schema export is on, so version 2 is recorded in app/schemas as the baseline
 * every future migration is written against. Bump the version and Room will
 * refuse to build until a Migration exists — which is the point.
 */
@Database(
        entities = {Pet.class, FeedingEvent.class, DetectionEvent.class},
        version = 3,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract PetDao petDao();
    public abstract FeedingDao feedingDao();
    public abstract DetectionDao detectionDao();

    /**
     * Adds the sensor-reading tables. Nothing about the pets table changes, so
     * existing pets survive — which is the entire reason this is a real
     * Migration rather than another destructive fallback.
     *
     * The CREATE statements must match what Room generates exactly, including
     * index names and column order; app/schemas/…/3.json is the reference, and
     * the migration test will fail loudly if they drift apart.
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feeding_events` ("
                            + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`petId` INTEGER NOT NULL, "
                            + "`timestampMillis` INTEGER NOT NULL, "
                            + "`grams` INTEGER NOT NULL, "
                            + "`type` TEXT NOT NULL, "
                            + "`source` TEXT, "
                            + "FOREIGN KEY(`petId`) REFERENCES `pets`(`id`) "
                            + "ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_feeding_events_petId_timestampMillis` "
                            + "ON `feeding_events` (`petId`, `timestampMillis`)");

            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `detection_events` ("
                            + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`petId` INTEGER NOT NULL, "
                            + "`timestampMillis` INTEGER NOT NULL, "
                            + "`label` TEXT NOT NULL, "
                            + "`confidence` REAL NOT NULL, "
                            + "`source` TEXT NOT NULL, "
                            + "FOREIGN KEY(`petId`) REFERENCES `pets`(`id`) "
                            + "ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_detection_events_petId_timestampMillis` "
                            + "ON `detection_events` (`petId`, `timestampMillis`)");
        }
    };
    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "pets_db")
                            .addMigrations(MIGRATION_2_3)
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
