package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The contract every dashboard screen renders against. The important property is
 * that "not reporting" stays distinguishable from "reported zero" — the whole
 * reason the fields are boxed types and the screens show setup prompts.
 */
public class DashboardStatsTest {

    @Test
    public void aFreshInstanceReportsNothingRatherThanZero() {
        DashboardStats stats = new DashboardStats();

        // null means "no feeder paired", which is not the same as "ate 0 g".
        assertNull(stats.gramsEaten);
        assertNull(stats.currentRoom);
        assertNull(stats.lastGpsFixAgo);
        assertNull(stats.lastDetectionTime);
    }

    @Test
    public void collectionsAreEmptyNotNull() {
        DashboardStats stats = new DashboardStats();

        // The render loops iterate these without a null check.
        assertTrue(stats.meals.isEmpty());
        assertTrue(stats.rooms.isEmpty());
    }

    @Test
    public void totalRoomMinutesIsZeroWithNoRooms() {
        assertEquals(0, new DashboardStats().totalRoomMinutes());
    }

    @Test
    public void totalRoomMinutesSumsEveryStay() {
        DashboardStats stats = new DashboardStats();
        stats.rooms.add(new DashboardStats.RoomStay("Living room", 90));
        stats.rooms.add(new DashboardStats.RoomStay("Kitchen", 35));
        stats.rooms.add(new DashboardStats.RoomStay("Hall", 5));

        assertEquals(130, stats.totalRoomMinutes());
    }

    @Test
    public void forPetTodayStillReportsNothing() {
        // Guards the honest empty state: until the feeder, sensors, camera and
        // GPS are wired up, this must not start inventing numbers. When that
        // work lands (#10), this test should be replaced, not deleted quietly.
        Pet pet = new Pet("Biscuit", "Dog", null, null, null, null, null, null, null, "owner-1");

        DashboardStats stats = DashboardStats.forPetToday(pet);

        assertNull(stats.gramsEaten);
        assertTrue(stats.rooms.isEmpty());
        assertEquals(0, stats.detectionsToday);
    }

    @Test
    public void defaultGramsTargetIsNonZeroSoPercentagesDoNotDivideByZero() {
        // Both FeedingActivity and MainMenuActivity compute grams/target * 100.
        assertTrue(new DashboardStats().gramsTarget > 0);
    }
}
