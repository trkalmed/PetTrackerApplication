package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

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

        assertEquals(-1, insertRoomEvent(db, 7L, "pir", 99L));
    }

    @Test
    public void migrate3To4_localReadingsDoNotCollideWithEachOther() throws IOException {
        SupportSQLiteDatabase db = migratedToFour();

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
        try (Cursor c = migrated.query("SELECT COUNT(*) FROM tracker_fixes")) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
        }
    }

    @Test
    public void everyMigrationIsActuallyRegisteredWithTheBuilder() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        AppDatabase.closeForTests();
        context.deleteDatabase("pets_db");

        SupportSQLiteDatabase old = helper.createDatabase("pets_db", 2);
        ContentValues pet = new ContentValues();
        pet.put("id", 9L);
        pet.put("name", "Nutmeg");
        pet.put("ownerId", "owner-1");
        old.insert("pets", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, pet);
        old.close();

        AppDatabase.closeForTests();
        AppDatabase db = AppDatabase.getInstance(context);
        Pet survivor = db.petDao().getPetById(9L, "owner-1");

        assertNotNull("the pet should have survived every migration", survivor);
        assertEquals("Nutmeg", survivor.getName());
        assertNull("a pet from before the column has no target", survivor.getDailyFoodGrams());

        AppDatabase.closeForTests();
        context.deleteDatabase("pets_db");
    }

    @Test
    public void migrate6To7_schemaMatchesTheExportedVersion7() throws IOException {
        helper.createDatabase(TEST_DB, 6).close();

        helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7);
    }

    @Test
    public void migrate6To7_existingPetsHaveNoTargetRatherThanZero() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 6);

        ContentValues pet = new ContentValues();
        pet.put("id", 5L);
        pet.put("name", "Nutmeg");
        pet.put("ownerId", "owner-1");
        db.insert("pets", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, pet);
        db.close();

        SupportSQLiteDatabase migrated =
                helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7);

        try (Cursor c = migrated.query(
                "SELECT name, dailyFoodGrams FROM pets WHERE id = 5")) {
            assertTrue("the pet should have survived", c.moveToFirst());
            assertEquals("Nutmeg", c.getString(0));
            assertTrue("no target set is null, not 0", c.isNull(1));
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
