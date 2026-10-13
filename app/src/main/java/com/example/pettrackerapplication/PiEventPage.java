package com.example.pettrackerapplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One reply from GET /events?since=N. */
public final class PiEventPage {

    public final List<PiEvent> events;

    public final long nextCursor;

    public final boolean hasMore;

    public final int malformed;

    private PiEventPage(List<PiEvent> events, long nextCursor, boolean hasMore,
                        int malformed) {
        this.events = Collections.unmodifiableList(events);
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
        this.malformed = malformed;
    }

    public static PiEventPage fromJson(JSONObject json, long since) {
        List<PiEvent> parsed = new ArrayList<>();
        int malformed = 0;

        JSONArray array = json.optJSONArray("events");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                PiEvent event = PiEvent.fromJson(array.optJSONObject(i));
                if (event == null) {
                    malformed++;
                } else {
                    parsed.add(event);
                }
            }
        }

        // Fall back to since so a bad reply never rewinds the cursor.
        return new PiEventPage(
                parsed,
                json.optLong("next_cursor", since),
                json.optBoolean("has_more", false),
                malformed);
    }

    public static PiEventPage of(List<PiEvent> events, long nextCursor, boolean hasMore) {
        return new PiEventPage(new ArrayList<>(events), nextCursor, hasMore, 0);
    }
}
