package com.example.pettrackerapplication;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TrackerFixDao {

    /** IGNORE: a Pi fix delivered twice collides with the unique index and is dropped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(TrackerFix fix);

    @Query("SELECT * FROM tracker_fixes WHERE petId = :petId "
            + "ORDER BY timestampMillis DESC LIMIT 1")
    TrackerFix getMostRecent(long petId);

    @Query("SELECT EXISTS(SELECT 1 FROM tracker_fixes WHERE petId = :petId)")
    boolean hasAnyFix(long petId);

    /** Oldest first: fixes are joined in this order to draw the route. */
    @Query("SELECT * FROM tracker_fixes WHERE petId = :petId "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis "
            + "ORDER BY timestampMillis ASC")
    List<TrackerFix> getInRange(long petId, long fromMillis, long toMillis);

    @Query("SELECT * FROM (SELECT * FROM tracker_fixes WHERE petId = :petId "
            + "ORDER BY timestampMillis DESC LIMIT :limit) ORDER BY timestampMillis ASC")
    List<TrackerFix> getMostRecentRun(long petId, int limit);

    @Query("DELETE FROM tracker_fixes WHERE timestampMillis < :beforeMillis")
    int deleteOlderThan(long beforeMillis);
}
