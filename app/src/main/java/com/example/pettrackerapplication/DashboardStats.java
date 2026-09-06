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

    /**
     * Used until somebody sets a target for the pet.
     *
     * A plausible day for an average adult cat on dry food, and deliberately
     * not presented as advice: the app has no idea what any particular animal
     * needs, which is exactly why the number is editable.
     */
    public static final int DEFAULT_DAILY_GRAMS = 300;

    // Feeder
    public Integer gramsEaten;              // null = no feeder paired
    /**
     * The daily target this pet is measured against.
     *
     * Comes from the pet when one has been set, and from
     * {@link #DEFAULT_DAILY_GRAMS} otherwise — so a pet nobody has configured
     * still renders a sensible ring rather than dividing by zero.
     */
    public int gramsTarget = DEFAULT_DAILY_GRAMS;
    public List<Meal> meals = new ArrayList<>();

    // Room motion sensors
    public List<RoomStay> rooms = new ArrayList<>();

    /**
     * Whether any motion sensor has ever reported for this pet.
     *
     * The same distinction the feeder makes with a null {@link #gramsEaten}:
     * no sensors paired is a setup prompt, while paired and quiet since
     * midnight is a real, empty day. An empty {@link #rooms} alone cannot tell
     * those apart.
     */
    public boolean roomsReporting;

    public String currentRoom;              // null = nowhere known right now
    public String currentRoomSince;         // "14:20", when this visit started

    /** When motion was last seen, set only while {@link #currentRoom} is null. */
    public String lastMotionTime;           // "09:12"

    /**
     * The most recent motion snapshot of the day: the Pi's file name for it,
     * the room it was taken in, and when.
     *
     * The name alone, never a URL — the Pi's address is a DHCP lease and the
     * screen rebuilds the address when it draws. Null when the rig has no
     * camera, or when today's sightings all happened to miss.
     */
    public String lastSnapshotName;         // "motion-20260828-152031-812-kitchen.jpg"
    public String lastSnapshotRoom;         // "Kitchen"
    public String lastSnapshotTime;         // "15:20"

    // Pi camera
    public boolean cameraOnline;
    public int detectionsToday;
    public String lastDetectionTime;        // "15:02"

    /*
     * GPS — the tracker's position, never the pet's.
     *
     * The Raspberry Pi is a box in the hall. Its coordinates locate the
     * installation, and the pet's position inside that installation is what
     * {@link #rooms} holds, from the PIR sensors. These fields are named for
     * the tracker so that nothing downstream can quietly start presenting them
     * as an animal's whereabouts; see {@link TrackerFix}.
     */
    public String trackerFixTime;           // "19:42", or "29 Aug 18:04" if older
    public Double trackerLatitude;          // null together with the others
    public Double trackerLongitude;
    public int trackerSatellites;

    public int totalRoomMinutes() {
        int total = 0;
        for (RoomStay s : rooms) total += s.minutes;
        return total;
    }

    /**
     * Today's numbers for one pet, read from the local database.
     *
     * Feeder grams, room occupancy, camera detections and the tracker's GPS fix
     * all come from real stored readings, and all but the detections arrive from
     * the Pi through {@link PiEventSync}.
     *
     * The GPS fields deliberately answer a smaller question than they look like
     * they do. They say where the Raspberry Pi is, because that is what the
     * module on it can measure. Where the <em>pet</em> is comes from the motion
     * sensors, and would only come from GPS if someone built a collar tag. The
     * screen labels the difference rather than blurring it.
     *
     * Hits the database, so call it from a background thread.
     */
    public static DashboardStats forPetToday(Context context, Pet pet) {
        return new PetDataRepository(context).statsForToday(pet);
    }
}
