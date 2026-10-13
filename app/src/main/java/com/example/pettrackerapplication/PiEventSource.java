package com.example.pettrackerapplication;

import java.io.IOException;

/** Where readings come from, independent of HTTP. */
public interface PiEventSource {

    PiHealth health() throws IOException;

    PiEventPage events(long since) throws IOException;
}
