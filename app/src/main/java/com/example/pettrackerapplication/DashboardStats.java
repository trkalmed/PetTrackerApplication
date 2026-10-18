package com.example.pettrackerapplication;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** One day of dashboard data. A null field means that hardware is not reporting. */
public class DashboardStats {

    public static class Meal {
        public final String time;
        public final int grams;
        public Meal(String time, int grams) { this.time = time; this.grams = grams; }
    }

    public static class RoomStay {
        public final String room;
        public final int minutes;
        public RoomStay(String room, int minutes) { this.room = room; this.minutes = minutes; }
    }

    public static final int DEFAULT_DAILY_GRAMS = 300;

    public Integer gramsEaten;              // null means no feeder is paired
    public int gramsTarget = DEFAULT_DAILY_GRAMS;
    public List<Meal> meals = new ArrayList<>();

    public List<RoomStay> rooms = new ArrayList<>();

    public boolean roomsReporting;    // whether sensors are paired at all, not whether one fired today

    public String currentRoom;              // null means the location is unknown right now
    public String currentRoomSince;

    public String lastMotionTime;

    public String lastSnapshotName;
    public String lastSnapshotRoom;
    public String lastSnapshotTime;

    public boolean cameraOnline;      // live, or seen something recently - the stream may still be down
    public int detectionsToday;
    public String lastDetectionTime;

    // GPS: the tracker's position, never the pet's.
    public String trackerFixTime;
    public Double trackerLatitude;    // null together with the longitude, until the first fix
    public Double trackerLongitude;
    public int trackerSatellites;

    public int totalRoomMinutes() {
        int total = 0;
        for (RoomStay s : rooms) total += s.minutes;
        return total;
    }

    public static DashboardStats forPetToday(Context context, Pet pet) {
        return new PetDataRepository(context).statsForToday(pet);
    }
}
