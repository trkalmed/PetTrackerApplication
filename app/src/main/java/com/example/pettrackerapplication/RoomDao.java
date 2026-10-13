package com.example.pettrackerapplication;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RoomDao {

    /** IGNORE: a Pi event delivered twice collides with the unique index and is dropped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(RoomEvent event);

    @Query("SELECT * FROM room_events "
            + "WHERE petId = :petId "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis "
            + "ORDER BY timestampMillis ASC")
    List<RoomEvent> getInRange(long petId, long fromMillis, long toMillis);

    @Query("SELECT EXISTS(SELECT 1 FROM room_events WHERE petId = :petId)")
    boolean hasAnyReading(long petId);

    @Query("SELECT * FROM room_events "
            + "WHERE petId = :petId AND snapshotName IS NOT NULL "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis "
            + "ORDER BY timestampMillis DESC LIMIT 1")
    RoomEvent getMostRecentWithSnapshot(long petId, long fromMillis, long toMillis);

    @Query("SELECT * FROM room_events "
            + "WHERE petId = :petId "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis "
            + "ORDER BY timestampMillis DESC")
    List<RoomEvent> getInRangeNewestFirst(long petId, long fromMillis, long toMillis);

    @Query("DELETE FROM room_events WHERE timestampMillis < :beforeMillis")
    int deleteOlderThan(long beforeMillis);
}
