package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The wire format between the two halves of this project, parsed.
 *
 * Every field name here is copied from a real reply in pi/README.md. If someone
 * renames one on the Pi, this is where it should break — the alternative is a
 * phone that syncs successfully and stores nothing, which reads as "the sensors
 * are quiet" and is the hardest kind of failure to notice.
 */
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
        // Parsed, then ignored by the sync — but it must not break the page it
        // arrives in, and it arrives once a minute.
        PiEvent event = PiEvent.fromJson(json("{"
                + "'id': 45, 'kind': 'weight', 'timestamp_ms': " + JULY_2025 + ","
                + "'source': 'load-cell', 'grams': 118.4}"));

        assertNotNull(event);
        assertEquals(PiEvent.KIND_WEIGHT, event.kind);
    }

    @Test
    public void anUnknownKindStillParses() {
        // The Pi's contract says adding a kind is safe. A newer Pi talking to an
        // older app must not be a parse failure — the sync skips what it does
        // not recognise.
        PiEvent event = PiEvent.fromJson(json("{"
                + "'id': 46, 'kind': 'humidity', 'timestamp_ms': " + JULY_2025 + "}"));

        assertNotNull(event);
        assertEquals("humidity", event.kind);
    }

    @Test
    public void rejectsAnEventWithNoId() {
        // The id is the cursor. Without it the sync cannot say where it got to.
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
        // A Pi has no real-time clock: between boot and its first NTP sync it
        // stamps events in 1970. Storing those would file a meal under a day
        // that is not on any dashboard; moving them to "now" would invent one.
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
        // The repository rejects a blank room; it must not receive "null" or ""
        // dressed up as a real one.
        assertNull(event.room);
        assertNull(event.source);
    }

    // ── The page around the events ──────────────────────────────────────────

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
        // Rewinding to 0 on a malformed reply would replay the Pi's whole log.
        // Harmless, thanks to the remote ids — but pointless work on a phone.
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

    // ── Health ──────────────────────────────────────────────────────────────

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
        // The distinction the whole /health endpoint exists for: which sensors
        // are actually live, one at a time, while the rig is being wired.
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
