package com.example.pettrackerapplication;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RoomDao {

    /**
     * IGNORE, not ABORT: the unique index over (petId, source, remoteId) makes a
     * re-delivered Pi event collide, and a collision here means "already have
     * it", not an error. Returns -1 when the row was dropped as a duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(RoomEvent event);

    /** Sightings inside a window, oldest first — the order the dwell maths needs. */
    @Query("SELECT * FROM room_events "
            + "WHERE petId = :petId "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis "
            + "ORDER BY timestampMillis ASC")
    List<RoomEvent> getInRange(long petId, long fromMillis, long toMillis);

    /**
     * Whether any motion sensor has ever reported for this pet — "no sensors
     * paired" against "paired, and the pet has not moved today".
     */
    @Query("SELECT EXISTS(SELECT 1 FROM room_events WHERE petId = :petId)")
    boolean hasAnyReading(long petId);

    @Query("DELETE FROM room_events WHERE timestampMillis < :beforeMillis")
    int deleteOlderThan(long beforeMillis);
}
