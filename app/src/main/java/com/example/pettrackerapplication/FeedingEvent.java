package com.example.pettrackerapplication;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One reading from the auto feeder / load cell.
 *
 * The hardware is still being built, so this deliberately records what was
 * measured rather than assuming how. A load cell under the bowl gives you a
 * weight that falls as the pet eats ({@link #TYPE_EATEN}); the feeder itself
 * knows what it put in ({@link #TYPE_DISPENSED}). Both are useful and they are
 * not the same number, so the type is stored rather than inferred.
 *
 * Rows are deleted with their pet — a meal without a pet means nothing.
 */
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

    /** Food the feeder put into the bowl. */
    public static final String TYPE_DISPENSED = "DISPENSED";
    /** Food that left the bowl, i.e. a drop in load-cell weight. */
    public static final String TYPE_EATEN = "EATEN";

    @PrimaryKey(autoGenerate = true)
    private long id;

    private long petId;

    /** Wall-clock time of the reading, epoch millis. */
    @ColumnInfo(name = "timestampMillis")
    private long timestampMillis;

    /** Whole grams. Negative values are rejected on the way in. */
    private int grams;

    @NonNull
    private String type;

    /** Free-text origin, e.g. "feeder-01". Useful once there is more than one. */
    private String source;

    /**
     * The id this reading had on the Pi, or null when it was recorded locally.
     *
     * Paired with the unique index over (petId, source, remoteId), this is what
     * makes re-polling the Pi harmless: a reading that arrives twice collides
     * and is dropped rather than counted twice. See {@link PiEventSync}.
     */
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
