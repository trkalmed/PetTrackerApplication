package com.example.pettrackerapplication;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FeedingDao {

    /** IGNORE: a Pi reading delivered twice collides with the unique index and is dropped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(FeedingEvent event);

    @Insert
    void insertAll(List<FeedingEvent> events);

    @Query("SELECT * FROM feeding_events "
            + "WHERE petId = :petId AND type = :type "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis "
            + "ORDER BY timestampMillis ASC")
    List<FeedingEvent> getInRange(long petId, String type, long fromMillis, long toMillis);

    @Query("SELECT COALESCE(SUM(grams), 0) FROM feeding_events "
            + "WHERE petId = :petId AND type = :type "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis")
    int getTotalGramsInRange(long petId, String type, long fromMillis, long toMillis);

    @Query("SELECT EXISTS(SELECT 1 FROM feeding_events WHERE petId = :petId)")
    boolean hasAnyReading(long petId);

    @Query("DELETE FROM feeding_events WHERE timestampMillis < :beforeMillis")
    int deleteOlderThan(long beforeMillis);
}
