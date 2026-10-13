package com.example.pettrackerapplication;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** One GPS fix from the Pi: where the tracker was, not the pet. */
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

    @ColumnInfo(name = "timestampMillis")
    private long timestampMillis;

    private double latitude;

    private double longitude;

    private int satellites;

    private String source;

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
