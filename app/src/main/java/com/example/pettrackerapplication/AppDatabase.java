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
 * every later migration is written against. Bump the version and Room will
 * refuse to build until a Migration exists — which is the point.
 */
@Database(
        entities = {Pet.class, FeedingEvent.class, DetectionEvent.class, RoomEvent.class},
        version = 5,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract PetDao petDao();
    public abstract FeedingDao feedingDao();
    public abstract DetectionDao detectionDao();
    public abstract RoomDao roomDao();

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
    /**
     * Adds motion sightings, and the dedupe keys the Pi poller needs.
     *
     * Two things happen here. { room_events} is new — the PIR sensors
     * finally have somewhere to land. And { feeding_events} gains a
     * nullable { remoteId} plus a unique index over
     * (petId, source, remoteId), which is what makes polling the Pi idempotent:
     * a reading re-delivered after a cursor reset collides and is dropped
     * instead of being counted a second time. Existing rows get a NULL
     * remoteId, and SQLite treats NULLs in a unique index as distinct, so
     * nothing already stored collides with anything else.
     *
     * app/schemas/…/4.json is the reference; MigrationTest fails if these
     * statements drift from it.
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE `feeding_events` ADD COLUMN `remoteId` INTEGER");
            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS "
                            + "`index_feeding_events_petId_source_remoteId` "
                            + "ON `feeding_events` (`petId`, `source`, `remoteId`)");

            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `room_events` ("
                            + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`petId` INTEGER NOT NULL, "
                            + "`timestampMillis` INTEGER NOT NULL, "
                            + "`room` TEXT NOT NULL, "
                            + "`source` TEXT, "
                            + "`remoteId` INTEGER, "
                            + "FOREIGN KEY(`petId`) REFERENCES `pets`(`id`) "
                            + "ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_room_events_petId_timestampMillis` "
                            + "ON `room_events` (`petId`, `timestampMillis`)");
            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS "
                            + "`index_room_events_petId_source_remoteId` "
                            + "ON `room_events` (`petId`, `source`, `remoteId`)");
        }
    };

    /**
     * Remembers which picture belongs to a motion sighting.
     *
     * The Pi now saves a frame when a sensor fires and names it in the event.
     * Only the name is stored: the Pi's address changes with the lease, so a URL
     * recorded today would be wrong next month, and the dashboard rebuilds it
     * from whichever Pi is paired when it draws.
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE `room_events` ADD COLUMN `snapshotName` TEXT");
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
                            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
