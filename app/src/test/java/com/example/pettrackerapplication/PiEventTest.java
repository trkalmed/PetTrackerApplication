package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class PiEventTest {

    private static final long JULY_2025 = 1_752_000_000_000L;

    private static JSONObject json(String raw) {
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            throw new AssertionError("bad test fixture", e);
        }
    }

    @Test
    public void parsesAFeedingEvent() {
        PiEvent event = PiEvent.fromJson(json("{"
                + "'id': 43, 'kind': 'feeding', 'timestamp_ms': " + JULY_2025 + ","
                + "'source': 'load-cell', 'grams': 40, 'type': 'EATEN'}"));

        assertNotNull(event);
        assertEquals(43, event.id);
        assertEquals(PiEvent.KIND_FEEDING, event.kind);
        assertEquals(JULY_2025, event.timestampMillis);
        assertEquals("load-cell", event.source);
        assertEquals(Integer.valueOf(40), event.grams);
        assertEquals(FeedingEvent.TYPE_EATEN, event.type);
        assertNull(event.room);
    }

    @Test
    public void parsesALocationEvent() {
        PiEvent event = PiEvent.fromJson(json("{"
                + "'id': 421, 'kind': 'location', 'timestamp_ms': " + JULY_2025 + ","
                + "'source': 'gps', 'lat': 32.00964, 'lon': 35.76567,"
                + "'satellites': 12}"));

        assertNotNull(event);
        assertEquals(PiEvent.KIND_LOCATION, event.kind);
        assertEquals(32.00964, event.latitude, 0.000005);
        assertEquals(35.76567, event.longitude, 0.000005);
        assertEquals(Integer.valueOf(12), event.satellites);
        assertNull(event.room);
        assertNull(event.grams);
    }

    @Test
    public void parsesALocationEventWithNoSatelliteCount() {
        PiEvent event = PiEvent.fromJson(json("{"
                + "'id': 422, 'kind': 'location', 'timestamp_ms': " + JULY_2025 + ","
                + "'source': 'gps', 'lat': 32.00964, 'lon': 35.76567}"));

        assertNotNull(event);
        assertNull(event.satellites);
        assertEquals(32.00964, event.latitude, 0.000005);
    }

    @Test
    public void nonLocationEventsHaveNoCoordinates() {
        PiEvent event = PiEvent.fromJson(json("{"
                + "'id': 45, 'kind': 'motion', 'timestamp_ms': " + JULY_2025 + ","
                + "'room': 'Hall'}"));

        assertNotNull(event);
        assertNull(event.latitude);
        assertNull(event.longitude);
        assertNull(event.satellites);
    }

    @Test
    public void parsesAMotionEvent() {
        PiEvent event = PiEvent.fromJson(json("{"
                + "'id': 44, 'kind': 'motion', 'timestamp_ms': " + JULY_2025 + ","
                + "'source': 'pir', 'room': 'Kitchen'}"));

        assertNotNull(event);
        assertEquals(PiEvent.KIND_MOTION, event.kind);
        assertEquals("Kitchen", event.room);
        assertNull(event.grams);
    }

    @Test
    public void parsesAWeightEvent() {
        PiEvent event = PiEvent.fromJson(json("{"
                + "'id': 45, 'kind': 'weight', 'timestamp_ms': " + JULY_2025 + ","
                + "'source': 'load-cell', 'grams': 118.4}"));

        assertNotNull(event);
        assertEquals(PiEvent.KIND_WEIGHT, event.kind);
    }

    @Test
    public void anUnknownKindStillParses() {
        PiEvent event = PiEvent.fromJson(json("{"
                + "'id': 46, 'kind': 'humidity', 'timestamp_ms': " + JULY_2025 + "}"));

        assertNotNull(event);
        assertEquals("humidity", event.kind);
    }

    @Test
    public void rejectsAnEventWithNoId() {
        assertNull(PiEvent.fromJson(json(
                "{'kind': 'motion', 'timestamp_ms': " + JULY_2025 + "}")));
    }

    @Test
    public void rejectsAnEventWithNoKind() {
        assertNull(PiEvent.fromJson(json(
                "{'id': 5, 'timestamp_ms': " + JULY_2025 + "}")));
    }

    @Test
    public void rejectsATimestampFromBeforeThePiKnewTheTime() {
        assertNull(PiEvent.fromJson(json(
                "{'id': 5, 'kind': 'motion', 'timestamp_ms': 12000, 'room': 'Hall'}")));
    }

    @Test
    public void rejectsNull() {
        assertNull(PiEvent.fromJson(null));
    }

    @Test
    public void missingOptionalFieldsAreNullNotEmptyStrings() {
        PiEvent event = PiEvent.fromJson(json(
                "{'id': 7, 'kind': 'motion', 'timestamp_ms': " + JULY_2025 + "}"));

        assertNotNull(event);
        assertNull(event.room);
        assertNull(event.source);
    }

    @Test
    public void parsesAPageAndItsCursor() {
        PiEventPage page = PiEventPage.fromJson(json("{'events': ["
                + "{'id': 43, 'kind': 'motion', 'timestamp_ms': " + JULY_2025 + ", 'room': 'Hall'},"
                + "{'id': 44, 'kind': 'motion', 'timestamp_ms': " + JULY_2025 + ", 'room': 'Kitchen'}"
                + "], 'next_cursor': 44, 'has_more': false}"), 0);

        assertEquals(2, page.events.size());
        assertEquals(44, page.nextCursor);
        assertFalse(page.hasMore);
        assertEquals(0, page.malformed);
    }

    @Test
    public void anUnusableEventCostsThatEventAndNotThePage() {
        PiEventPage page = PiEventPage.fromJson(json("{'events': ["
                + "{'id': 43, 'kind': 'motion', 'timestamp_ms': " + JULY_2025 + ", 'room': 'Hall'},"
                + "{'kind': 'motion', 'timestamp_ms': " + JULY_2025 + "},"
                + "{'id': 45, 'kind': 'motion', 'timestamp_ms': " + JULY_2025 + ", 'room': 'Kitchen'}"
                + "], 'next_cursor': 45, 'has_more': true}"), 0);

        assertEquals(2, page.events.size());
        assertEquals(1, page.malformed);
        assertTrue(page.hasMore);
    }

    @Test
    public void aReplyWithNoCursorLeavesTheAppWhereItWas() {
        PiEventPage page = PiEventPage.fromJson(json("{'events': []}"), 137);

        assertEquals(137, page.nextCursor);
        assertTrue(page.events.isEmpty());
    }

    @Test
    public void anEmptyPageIsNotAnError() {
        PiEventPage page = PiEventPage.fromJson(
                json("{'events': [], 'next_cursor': 42, 'has_more': false}"), 42);

        assertTrue(page.events.isEmpty());
        assertEquals(42, page.nextCursor);
    }

    @Test
    public void parsesHealthIncludingPerSensorAvailability() {
        PiHealth health = PiHealth.fromJson(json("{"
                + "'ok': true, 'time_ms': " + JULY_2025 + ", 'uptime_s': 3600,"
                + "'latest_cursor': 42, 'latest_weight_grams': 118.4,"
                + "'sensors': {'scale': true, 'feeder': false, 'camera': true,"
                + " 'location': false,"
                + " 'motion': {'Kitchen': true, 'Living room': true, 'Hall': false}}}"));

        assertTrue(health.ok);
        assertEquals(42, health.latestCursor);
        assertTrue(health.scaleAvailable);
        assertFalse(health.feederAvailable);
        assertEquals(3, health.motionSensors.size());
        assertTrue(health.motionSensors.get("Kitchen"));
        assertFalse(health.motionSensors.get("Hall"));
        assertTrue(health.anyMotionSensorAvailable());
    }

    @Test
    public void healthWithNoSensorsBlockIsNotACrash() {
        PiHealth health = PiHealth.fromJson(json("{'ok': true, 'latest_cursor': 3}"));

        assertEquals(3, health.latestCursor);
        assertTrue(health.motionSensors.isEmpty());
        assertFalse(health.anyMotionSensorAvailable());
    }
}
