package com.example.pettrackerapplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One reply from {@code GET /events?since=…}. */
public final class PiEventPage {

    public final List<PiEvent> events;

    /**
     * Where to resume from. Exclusive: sending it back returns what comes after,
     * never these events again.
     *
     * It is the last id the Pi handed out, not the highest it holds — with more
     * than one page pending the two differ, which is what {@link #hasMore} is for.
     */
    public final long nextCursor;

    public final boolean hasMore;

    /** Entries the Pi sent that could not be parsed. See {@link PiEvent#fromJson}. */
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

        // Falling back to `since` matters: a reply with no usable cursor must
        // leave the app where it was rather than rewinding it to the start of
        // the Pi's log and replaying everything.
        return new PiEventPage(
                parsed,
                json.optLong("next_cursor", since),
                json.optBoolean("has_more", false),
                malformed);
    }

    /** For tests and for the sync's own paging. */
    public static PiEventPage of(List<PiEvent> events, long nextCursor, boolean hasMore) {
        return new PiEventPage(new ArrayList<>(events), nextCursor, hasMore, 0);
    }
}
