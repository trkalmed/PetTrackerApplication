package com.example.pettrackerapplication;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One motion sighting: a PIR sensor in a named room fired at a moment in time.
 *
 * A PIR reports movement, not presence — it says "something moved in the
 * kitchen at 14:02", never "the cat is in the kitchen". Turning a sequence of
 * these into minutes per room is a judgement made when reading, in
 * {@link PetDataRepository}, not when writing: store what the sensor said and
 * keep the interpretation in one place where it can be changed and tested.
 *
 * {@link #remoteId} is the Pi's own event id, and the unique index over
 * (petId, source, remoteId) is what makes re-polling harmless — see
 * {@link PiEventSync}.
 *
 * Rows are deleted with their pet, like every other reading.
 */
@Entity(
        tableName = "room_events",
        foreignKeys = @ForeignKey(
                entity = Pet.class,
                parentColumns = "id",
                childColumns = "petId",
                onDelete = ForeignKey.CASCADE),
        indices = {
                @Index(value = {"petId", "timestampMillis"}),
                @Index(value = {"petId", "source", "remoteId"}, unique = true)
        })
public class RoomEvent {

    @PrimaryKey(autoGenerate = true)
    private long id;

    private long petId;

    /** Wall-clock time of the sighting, epoch millis. */
    @ColumnInfo(name = "timestampMillis")
    private long timestampMillis;

    /** The room name the sensor is configured with, e.g. "Kitchen". */
    @NonNull
    private String room;

    /** Free-text origin, e.g. "pir". */
    private String source;

    /** The id this event had on the Pi, or null when it came from somewhere else. */
    private Long remoteId;

    /**
     * The Pi's file name for the frame it grabbed when this sensor fired, or
     * null when there was no camera.
     *
     * Only the name is kept, never the address it came from: the Pi's IP is
     * whatever DHCP handed out this week, and a URL stored today would rot. The
     * dashboard rebuilds the URL from the currently paired Pi when it draws.
     */
    private String snapshotName;

    public RoomEvent(long petId, long timestampMillis, @NonNull String room, String source) {
        this.petId = petId;
        this.timestampMillis = timestampMillis;
        this.room = room;
        this.source = source;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getPetId() { return petId; }
    public void setPetId(long petId) { this.petId = petId; }

    public long getTimestampMillis() { return timestampMillis; }
    public void setTimestampMillis(long timestampMillis) { this.timestampMillis = timestampMillis; }

    @NonNull
    public String getRoom() { return room; }
    public void setRoom(@NonNull String room) { this.room = room; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Long getRemoteId() { return remoteId; }
    public void setRemoteId(Long remoteId) { this.remoteId = remoteId; }

    public String getSnapshotName() { return snapshotName; }
    public void setSnapshotName(String snapshotName) { this.snapshotName = snapshotName; }
}
