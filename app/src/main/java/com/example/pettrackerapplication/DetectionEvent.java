package com.example.pettrackerapplication;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A sighting from the object detector.
 *
 * The detector already produced these on every frame and threw them away; this
 * is where they land now. One row is a sighting, not a frame — the recorder
 * throttles, because at ~15 fps a frame-per-row would be tens of thousands of
 * inserts an hour for no extra information.
 */
@Entity(
        tableName = "detection_events",
        foreignKeys = @ForeignKey(
                entity = Pet.class,
                parentColumns = "id",
                childColumns = "petId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index(value = {"petId", "timestampMillis"})})
public class DetectionEvent {

    /** Frames off the Raspberry Pi's MJPEG stream. */
    public static final String SOURCE_PI = "PI";
    /** Frames off the phone's own camera. */
    public static final String SOURCE_PHONE = "PHONE";

    @PrimaryKey(autoGenerate = true)
    private long id;

    private long petId;

    private long timestampMillis;

    /** COCO label, e.g. "cat" or "dog". */
    @NonNull
    private String label;

    /** Detector confidence, 0..1. */
    private float confidence;

    @NonNull
    private String source;

    public DetectionEvent(long petId, long timestampMillis, @NonNull String label,
                          float confidence, @NonNull String source) {
        this.petId = petId;
        this.timestampMillis = timestampMillis;
        this.label = label;
        this.confidence = confidence;
        this.source = source;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getPetId() { return petId; }
    public void setPetId(long petId) { this.petId = petId; }

    public long getTimestampMillis() { return timestampMillis; }
    public void setTimestampMillis(long timestampMillis) { this.timestampMillis = timestampMillis; }

    @NonNull
    public String getLabel() { return label; }
    public void setLabel(@NonNull String label) { this.label = label; }

    public float getConfidence() { return confidence; }
    public void setConfidence(float confidence) { this.confidence = confidence; }

    @NonNull
    public String getSource() { return source; }
    public void setSource(@NonNull String source) { this.source = source; }
}
