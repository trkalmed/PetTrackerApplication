package com.example.pettrackerapplication;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

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

    /**
     * Fixes inside a window, <b>oldest first</b> — the order a route is walked.
     *
     * The order is load-bearing rather than cosmetic. These are joined point to
     * point to draw the track, so reversing them does not reverse the picture,
     * it scrambles it: every consecutive pair would be a jump between the wrong
     * two places. {@link #getMostRecent} deliberately sorts the other way, and
     * the two exist separately so neither call site has to remember to flip.
     */
    @Query("SELECT * FROM tracker_fixes WHERE petId = :petId "
            + "AND timestampMillis >= :fromMillis AND timestampMillis < :toMillis "
            + "ORDER BY timestampMillis ASC")
    List<TrackerFix> getInRange(long petId, long fromMillis, long toMillis);

    /**
     * The most recent run of fixes, however many are asked for, oldest first.
     *
     * Not day-bounded, unlike the room and feeding queries. A walk that starts
     * at 23:40 is one walk, and cutting it at midnight would draw half a route
     * and call it a day's tracking.
     */
    @Query("SELECT * FROM (SELECT * FROM tracker_fixes WHERE petId = :petId "
            + "ORDER BY timestampMillis DESC LIMIT :limit) ORDER BY timestampMillis ASC")
    List<TrackerFix> getMostRecentRun(long petId, int limit);

    @Query("DELETE FROM tracker_fixes WHERE timestampMillis < :beforeMillis")
    int deleteOlderThan(long beforeMillis);
}
