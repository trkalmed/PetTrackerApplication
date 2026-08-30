package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

/**
 * Drives the hand-written {@link AppDatabase#MIGRATION_2_3} through real SQLite.
 *
 * This is what the exported schema JSON is for. {@code runMigrationsAndValidate}
 * compares the migrated database against the generated schema for version 3, so
 * a typo in the CREATE statements — a missing index, a nullable column that
 * should not be, a column in the wrong order — fails here instead of on a user's
 * phone after they have already lost their pets.
 */
@RunWith(AndroidJUnit4.class)
public class MigrationTest {

    private static final String TEST_DB = "migration-test";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase.class,
            new java.util.ArrayList<>(),
            new FrameworkSQLiteOpenHelperFactory());

    @Test
    public void migrate2To3_schemaMatchesTheExportedVersion3() throws IOException {
        helper.createDatabase(TEST_DB, 2).close();

        // Validation is the assertion: it throws if the resulting schema differs
        // from app/schemas/…/3.json in any respect.
        helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3);
    }

    @Test
    public void migrate2To3_keepsExistingPets() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 2);

        ContentValues pet = new ContentValues();
        pet.put("id", 1L);
        pet.put("name", "Biscuit");
        pet.put("species", "Dog");
        pet.put("ownerId", "owner-1");
        db.insert("pets", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, pet);
        db.close();

        SupportSQLiteDatabase migrated =
                helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3);

        // The whole point of a real Migration over a destructive fallback.
        try (Cursor c = migrated.query("SELECT name, ownerId FROM pets WHERE id = 1")) {
            assertTrue("the pet should have survived the migration", c.moveToFirst());
            assertEquals("Biscuit", c.getString(0));
            assertEquals("owner-1", c.getString(1));
        }
    }

    @Test
    public void migrate3To4_schemaMatchesTheExportedVersion4() throws IOException {
        helper.createDatabase(TEST_DB, 3).close();

        helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4);
    }

    @Test
    public void migrate3To4_keepsReadingsThatWereAlreadyStored() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 3);

        ContentValues pet = new ContentValues();
        pet.put("id", 1L);
        pet.put("name", "Biscuit");
        pet.put("ownerId", "owner-1");
        db.insert("pets", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, pet);

        ContentValues meal = new ContentValues();
        meal.put("petId", 1L);
        meal.put("timestampMillis", 1_752_000_000_000L);
        meal.put("grams", 40);
        meal.put("type", FeedingEvent.TYPE_EATEN);
        meal.put("source", "load-cell");
        db.insert("feeding_events",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, meal);
        db.close();

        SupportSQLiteDatabase migrated =
                helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4);

        // This one adds a column to a table that already holds a user's history,
        // rather than only creating new ones.
        try (Cursor c = migrated.query(
                "SELECT grams, remoteId FROM feeding_events WHERE petId = 1")) {
            assertTrue("the meal should have survived", c.moveToFirst());
            assertEquals(40, c.getInt(0));
            assertTrue("a reading recorded before the Pi existed has no remote id",
                    c.isNull(1));
        }
    }

    @Test
    public void migrate3To4_theSamePiEventCannotBeStoredTwice() throws IOException {
        SupportSQLiteDatabase db = migratedToFour();

        assertTrue(insertRoomEvent(db, 7L, "pir", 99L) > 0);

        // The unique index is what makes re-polling the Pi free, and it has to
        // exist in a *migrated* database, not only in a freshly created one.
        assertEquals(-1, insertRoomEvent(db, 7L, "pir", 99L));
    }

    @Test
    public void migrate3To4_localReadingsDoNotCollideWithEachOther() throws IOException {
        SupportSQLiteDatabase db = migratedToFour();

        // Every locally recorded reading has a null remote id. SQLite treats
        // nulls in a unique index as distinct, which is the only reason a unique
        // index over a mostly-null column is usable at all — worth pinning,
        // because the opposite behaviour would silently drop every reading after
        // the first.
        assertTrue(insertRoomEvent(db, 7L, "local", null) > 0);
        assertTrue(insertRoomEvent(db, 7L, "local", null) > 0);
    }

    private SupportSQLiteDatabase migratedToFour() throws IOException {
        helper.createDatabase(TEST_DB, 3).close();
        SupportSQLiteDatabase db =
                helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4);

        ContentValues pet = new ContentValues();
        pet.put("id", 7L);
        pet.put("name", "Nutmeg");
        pet.put("ownerId", "owner-1");
        db.insert("pets", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, pet);
        return db;
    }

    private long insertRoomEvent(SupportSQLiteDatabase db, long petId, String source,
                                 Long remoteId) {
        ContentValues sighting = new ContentValues();
        sighting.put("petId", petId);
        sighting.put("timestampMillis", 1_752_000_000_000L);
        sighting.put("room", "Kitchen");
        sighting.put("source", source);
        sighting.put("remoteId", remoteId);
        return db.insert("room_events",
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE, sighting);
    }

    @Test
    public void migrate4To5_schemaMatchesTheExportedVersion5() throws IOException {
        helper.createDatabase(TEST_DB, 4).close();

        helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5);
    }

    @Test
    public void migrate4To5_keepsSightingsRecordedBeforeThereWasACamera() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 4);

        ContentValues pet = new ContentValues();
        pet.put("id", 3L);
        pet.put("name", "Nutmeg");
        pet.put("ownerId", "owner-1");
        db.insert("pets", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, pet);

        ContentValues sighting = new ContentValues();
        sighting.put("petId", 3L);
        sighting.put("timestampMillis", 1_752_000_000_000L);
        sighting.put("room", "Kitchen");
        sighting.put("source", "pir");
        db.insert("room_events",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, sighting);
        db.close();

        SupportSQLiteDatabase migrated =
                helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5);

        try (Cursor c = migrated.query(
                "SELECT room, snapshotName FROM room_events WHERE petId = 3")) {
            assertTrue("the sighting should have survived", c.moveToFirst());
            assertEquals("Kitchen", c.getString(0));
            assertTrue("a sighting from before the camera has no picture",
                       c.isNull(1));
        }
    }

    @Test
    public void migrate5To6_schemaMatchesTheExportedVersion6() throws IOException {
        helper.createDatabase(TEST_DB, 5).close();

        helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6);
    }

    @Test
    public void migrate5To6_keepsEverythingRecordedBeforeThereWasAGps() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 5);

        ContentValues pet = new ContentValues();
        pet.put("id", 4L);
        pet.put("name", "Nutmeg");
        pet.put("ownerId", "owner-1");
        db.insert("pets", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, pet);

        ContentValues sighting = new ContentValues();
        sighting.put("petId", 4L);
        sighting.put("timestampMillis", 1_752_000_000_000L);
        sighting.put("room", "Hall");
        sighting.put("source", "pir");
        db.insert("room_events",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, sighting);
        db.close();

        SupportSQLiteDatabase migrated =
                helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6);

        try (Cursor c = migrated.query(
                "SELECT room FROM room_events WHERE petId = 4")) {
            assertTrue("the sighting should have survived", c.moveToFirst());
            assertEquals("Hall", c.getString(0));
        }
        // The new table exists and starts empty, rather than the migration
        // having quietly invented a fix for a rig that had no GPS.
        try (Cursor c = migrated.query("SELECT COUNT(*) FROM tracker_fixes")) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
        }
    }

    @Test
    public void migrate2To3_newTablesAreUsable() throws IOException {
        helper.createDatabase(TEST_DB, 2).close();
        SupportSQLiteDatabase db =
                helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3);

        ContentValues pet = new ContentValues();
        pet.put("id", 7L);
        pet.put("name", "Nutmeg");
        pet.put("ownerId", "owner-1");
        db.insert("pets", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, pet);

        ContentValues meal = new ContentValues();
        meal.put("petId", 7L);
        meal.put("timestampMillis", 1_700_000_000_000L);
        meal.put("grams", 42);
        meal.put("type", FeedingEvent.TYPE_EATEN);
        long mealId = db.insert("feeding_events",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, meal);
        assertTrue("insert into feeding_events should succeed", mealId > 0);

        try (Cursor c = db.query("SELECT grams FROM feeding_events WHERE petId = 7")) {
            assertTrue(c.moveToFirst());
            assertEquals(42, c.getInt(0));
        }
    }
}
