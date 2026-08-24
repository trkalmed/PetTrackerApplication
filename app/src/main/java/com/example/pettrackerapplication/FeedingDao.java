package com.example.pettrackerapplication;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FeedingDao {

    @Insert
    long insert(FeedingEvent event);

    @Insert
    void insertAll(List<FeedingEvent> events);

    /** Readings inside a window, oldest first — the order the meal list renders in. */
    @Query("SELECT * FROM feeding_events "
            + "WHERE petId = :petId AND type = :type "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis "
            + "ORDER BY timestampMillis ASC")
    List<FeedingEvent> getInRange(long petId, String type, long fromMillis, long toMillis);

    @Query("SELECT COALESCE(SUM(grams), 0) FROM feeding_events "
            + "WHERE petId = :petId AND type = :type "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis")
    int getTotalGramsInRange(long petId, String type, long fromMillis, long toMillis);

    /**
     * Whether this pet has ever had a feeder reading.
     *
     * This is what separates "no feeder paired" from "the feeder is paired and
     * the pet has not eaten yet today" — the dashboard shows a setup prompt for
     * the first and a real zero for the second.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM feeding_events WHERE petId = :petId)")
    boolean hasAnyReading(long petId);

    /** Housekeeping for later: these tables grow forever otherwise. */
    @Query("DELETE FROM feeding_events WHERE timestampMillis < :beforeMillis")
    int deleteOlderThan(long beforeMillis);
}
