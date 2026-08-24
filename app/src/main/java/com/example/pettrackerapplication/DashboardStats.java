package com.example.pettrackerapplication;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * One day of dashboard data. Every field maps to hardware that exists:
 * grams from the feeder, minutes from the room motion sensors, counts from the
 * Pi camera detector, and the last GPS fix.
 *
 * A null field means "not reporting" — the dashboard then shows a setup prompt
 * instead of a zero, which is the whole point of the redesign.
 */
public class DashboardStats {

    public static class Meal {
        public final String time;   // "07:40"
        public final int grams;
        public Meal(String time, int grams) { this.time = time; this.grams = grams; }
    }

    public static class RoomStay {
        public final String room;   // "Living room"
        public final int minutes;
        public RoomStay(String room, int minutes) { this.room = room; this.minutes = minutes; }
    }

    // Feeder
    public Integer gramsEaten;              // null = no feeder paired
    public int gramsTarget = 300;
    public List<Meal> meals = new ArrayList<>();

    // Room motion sensors
    public List<RoomStay> rooms = new ArrayList<>();
    public String currentRoom;              // null = no sensor reporting
    public String currentRoomSince;         // "14:20"

    // Pi camera
    public boolean cameraOnline;
    public int detectionsToday;
    public String lastDetectionTime;        // "15:02"

    // GPS
    public String lastGpsFixAgo;            // "2 min ago", null = no fix yet

    public int totalRoomMinutes() {
        int total = 0;
        for (RoomStay s : rooms) total += s.minutes;
        return total;
    }

    /**
     * Today's numbers for one pet, read from the local database.
     *
     * Feeder grams and camera detections come from real stored readings now.
     * Room occupancy and GPS still have no source — no motion sensors, no collar
     * tag — so those fields stay empty and the screens keep showing setup
     * prompts, which is the honest thing to render.
     *
     * Hits the database, so call it from a background thread.
     */
    public static DashboardStats forPetToday(Context context, Pet pet) {
        return new PetDataRepository(context).statsForToday(pet);
    }
}
