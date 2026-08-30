package com.example.pettrackerapplication;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One GPS fix from the Raspberry Pi — where the <em>tracker</em> was, not the pet.
 *
 * The distinction is the whole reason this class is named as it is. The Pi is a
 * box in the hall with a GNSS module wired to it; its position is the house. A
 * cat's position inside that house comes from the PIR sensors and lives in
 * {@link RoomEvent}. Naming this table after the pet, or feeding it into
 * anything that answers "where is the cat", would have the app reporting a
 * stationary object as a moving animal — accurate to five decimal places and
 * wrong about the only thing anyone is asking.
 *
 * What it is good for: proving the location subsystem works end to end, and
 * telling you where the installation is. If a collar tag ever exists, it lands
 * in its own table alongside this one rather than replacing it — the two answer
 * different questions.
 *
 * Rows carry {@link #petId} for the same reason every other reading does: the
 * sync runs against the pet the app is currently showing, and cascade delete
 * keeps a removed pet from leaving readings behind. It is bookkeeping, not a
 * claim that this fix describes that animal.
 */
@Entity(
        tableName = "tracker_fixes",
        foreignKeys = @ForeignKey(
                entity = Pet.class,
                parentColumns = "id",
                childColumns = "petId",
                onDelete = ForeignKey.CASCADE),
        indices = {
                @Index(value = {"petId", "timestampMillis"}),
                @Index(value = {"petId", "source", "remoteId"}, unique = true)
        })
public class TrackerFix {

    @PrimaryKey(autoGenerate = true)
    private long id;

    private long petId;

    /** When the Pi computed the fix, epoch millis. */
    @ColumnInfo(name = "timestampMillis")
    private long timestampMillis;

    /** Decimal degrees, north positive. */
    private double latitude;

    /** Decimal degrees, east positive. */
    private double longitude;

    /**
     * Satellites the fix was computed from.
     *
     * Stored because it is the only thing here that says how much to trust the
     * coordinates. A three-satellite fix wanders by tens of metres; a twelve-
     * satellite one does not. Without it a bad fix and a good one look identical
     * in the database.
     */
    private int satellites;

    /** Free-text origin, e.g. "gps". */
    private String source;

    /** The id this event had on the Pi, or null when it came from elsewhere. */
    private Long remoteId;

    public TrackerFix(long petId, long timestampMillis, double latitude,
                      double longitude, int satellites, String source) {
        this.petId = petId;
        this.timestampMillis = timestampMillis;
        this.latitude = latitude;
        this.longitude = longitude;
        this.satellites = satellites;
        this.source = source;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getPetId() { return petId; }
    public void setPetId(long petId) { this.petId = petId; }

    public long getTimestampMillis() { return timestampMillis; }
    public void setTimestampMillis(long timestampMillis) { this.timestampMillis = timestampMillis; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public int getSatellites() { return satellites; }
    public void setSatellites(int satellites) { this.satellites = satellites; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Long getRemoteId() { return remoteId; }
    public void setRemoteId(Long remoteId) { this.remoteId = remoteId; }
}
