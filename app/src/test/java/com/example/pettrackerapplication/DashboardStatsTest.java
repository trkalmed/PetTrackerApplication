package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DashboardStatsTest {

    @Test
    public void aFreshInstanceReportsNothingRatherThanZero() {
        DashboardStats stats = new DashboardStats();

        assertNull(stats.gramsEaten);
        assertNull(stats.currentRoom);
        assertNull(stats.trackerFixTime);
        assertNull(stats.trackerLatitude);
        assertNull(stats.trackerLongitude);
        assertNull(stats.lastDetectionTime);
    }

    @Test
    public void collectionsAreEmptyNotNull() {
        DashboardStats stats = new DashboardStats();

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
    public void defaultGramsTargetIsNonZeroSoPercentagesDoNotDivideByZero() {
        assertTrue(new DashboardStats().gramsTarget > 0);
    }
}
