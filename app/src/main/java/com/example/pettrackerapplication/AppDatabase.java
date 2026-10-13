package com.example.pettrackerapplication;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Room database. Version 2 is the exported schema baseline, and every later version ships a migration. */
@Database(
        entities = {Pet.class, FeedingEvent.class, DetectionEvent.class, RoomEvent.class,
                TrackerFix.class},
        version = 7,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract PetDao petDao();
    public abstract FeedingDao feedingDao();
    public abstract DetectionDao detectionDao();
    public abstract RoomDao roomDao();
    public abstract TrackerFixDao trackerFixDao();

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

    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE `room_events` ADD COLUMN `snapshotName` TEXT");
        }
    };

    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracker_fixes` ("
                            + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`petId` INTEGER NOT NULL, "
                            + "`timestampMillis` INTEGER NOT NULL, "
                            + "`latitude` REAL NOT NULL, "
                            + "`longitude` REAL NOT NULL, "
                            + "`satellites` INTEGER NOT NULL, "
                            + "`source` TEXT, "
                            + "`remoteId` INTEGER, "
                            + "FOREIGN KEY(`petId`) REFERENCES `pets`(`id`) "
                            + "ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_tracker_fixes_petId_timestampMillis` "
                            + "ON `tracker_fixes` (`petId`, `timestampMillis`)");
            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS "
                            + "`index_tracker_fixes_petId_source_remoteId` "
                            + "ON `tracker_fixes` (`petId`, `source`, `remoteId`)");
        }
    };

    static void closeForTests() {
        synchronized (AppDatabase.class) {
            if (INSTANCE != null) {
                INSTANCE.close();
                INSTANCE = null;
            }
        }
    }

    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE `pets` ADD COLUMN `dailyFoodGrams` INTEGER");
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
                            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                                    MIGRATION_5_6, MIGRATION_6_7)
                            // Version 1 predates schema export, so it is the only version allowed to be wiped.
                            .fallbackToDestructiveMigrationFrom(1)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
