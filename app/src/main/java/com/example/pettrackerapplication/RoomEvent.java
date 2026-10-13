package com.example.pettrackerapplication;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** One motion sighting: a PIR in a named room fired at a moment in time. */
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

    @ColumnInfo(name = "timestampMillis")
    private long timestampMillis;

    @NonNull
    private String room;

    private String source;

    private Long remoteId;

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
