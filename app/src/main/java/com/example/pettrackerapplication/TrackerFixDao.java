package com.example.pettrackerapplication;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface TrackerFixDao {

    /**
     * IGNORE, for the same reason as every other Pi-fed table: the unique index
     * over (petId, source, remoteId) makes a re-delivered event collide, and a
     * collision means "already have it" rather than an error. Returns -1 when
     * the row was dropped as a duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(TrackerFix fix);

    /**
     * The newest fix, whenever it happened.
     *
     * Deliberately not restricted to today. A tracker that has not seen sky
     * since yesterday is still somewhere, and "located yesterday at 18:04" is
     * more useful — and more honest — than pretending there is no fix at all.
     * The caller decides how stale is too stale to show.
     */
    @Query("SELECT * FROM tracker_fixes WHERE petId = :petId "
            + "ORDER BY timestampMillis DESC LIMIT 1")
    TrackerFix getMostRecent(long petId);

    @Query("SELECT EXISTS(SELECT 1 FROM tracker_fixes WHERE petId = :petId)")
    boolean hasAnyFix(long petId);

    @Query("DELETE FROM tracker_fixes WHERE timestampMillis < :beforeMillis")
    int deleteOlderThan(long beforeMillis);
}
