package com.example.pettrackerapplication;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DetectionDao {

    @Insert
    long insert(DetectionEvent event);

    @Query("SELECT COUNT(*) FROM detection_events "
            + "WHERE petId = :petId "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis")
    int countInRange(long petId, long fromMillis, long toMillis);

    @Query("SELECT * FROM detection_events "
            + "WHERE petId = :petId "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis "
            + "ORDER BY timestampMillis DESC LIMIT 1")
    DetectionEvent getMostRecentInRange(long petId, long fromMillis, long toMillis);

    /**
     * Timestamp of the newest sighting, or null if there are none.
     * Used as the camera's liveness proxy: a detection means the camera was
     * demonstrably working at that moment.
     */
    @Query("SELECT MAX(timestampMillis) FROM detection_events WHERE petId = :petId")
    Long getMostRecentTimestamp(long petId);

    @Query("SELECT * FROM detection_events "
            + "WHERE petId = :petId "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis "
            + "ORDER BY timestampMillis DESC")
    List<DetectionEvent> getInRange(long petId, long fromMillis, long toMillis);

    @Query("DELETE FROM detection_events WHERE timestampMillis < :beforeMillis")
    int deleteOlderThan(long beforeMillis);
}
