package com.example.pettrackerapplication;

import java.io.IOException;

/**
 * Where readings come from, stated without HTTP.
 *
 * {@link PiEventSync} holds all the judgement — what a duplicate is, when a
 * cursor has gone stale, which kinds are worth storing — and none of the
 * transport. Keeping that behind this interface is what lets the sync be tested
 * against scripted pages, including the failures a real Pi produces rarely and
 * at the worst possible moment: a log that has been pruned, a cursor that has
 * rewound, a page that arrives half-read.
 *
 * Every method blocks on the network. Call from a background thread.
 */
public interface PiEventSource {

    /** @throws IOException if the Pi cannot be reached or answers unusably */
    PiHealth health() throws IOException;

    /** Events after {@code since}, oldest first. Exclusive cursor. */
    PiEventPage events(long since) throws IOException;
}
