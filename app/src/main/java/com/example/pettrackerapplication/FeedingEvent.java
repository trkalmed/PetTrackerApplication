package com.example.pettrackerapplication;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** One reading from the feeder load cell. */
@Entity(
        tableName = "feeding_events",
        foreignKeys = @ForeignKey(
                entity = Pet.class,
                parentColumns = "id",
                childColumns = "petId",
                onDelete = ForeignKey.CASCADE),
        indices = {
                @Index(value = {"petId", "timestampMillis"}),
                @Index(value = {"petId", "source", "remoteId"}, unique = true)
        })
public class FeedingEvent {

    public static final String TYPE_DISPENSED = "DISPENSED";
    public static final String TYPE_EATEN = "EATEN";

    @PrimaryKey(autoGenerate = true)
    private long id;

    private long petId;

    @ColumnInfo(name = "timestampMillis")
    private long timestampMillis;

    private int grams;

    @NonNull
    private String type;

    private String source;

    private Long remoteId;

    public FeedingEvent(long petId, long timestampMillis, int grams,
                        @NonNull String type, String source) {
        this.petId = petId;
        this.timestampMillis = timestampMillis;
        this.grams = grams;
        this.type = type;
        this.source = source;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getPetId() { return petId; }
    public void setPetId(long petId) { this.petId = petId; }

    public long getTimestampMillis() { return timestampMillis; }
    public void setTimestampMillis(long timestampMillis) { this.timestampMillis = timestampMillis; }

    public int getGrams() { return grams; }
    public void setGrams(int grams) { this.grams = grams; }

    @NonNull
    public String getType() { return type; }
    public void setType(@NonNull String type) { this.type = type; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Long getRemoteId() { return remoteId; }
    public void setRemoteId(Long remoteId) { this.remoteId = remoteId; }
}
