package com.example.pettrackerapplication;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Talks to the Pi's JSON endpoints over plain {@link HttpURLConnection}.
 *
 * No HTTP library, for the same reason the Pi service has no dependencies: this
 * is two GETs against a device on the same LAN, and a client stack would be more
 * code to keep current than the code it replaces. The MJPEG stream is not routed
 * through here — that is a long-lived connection with completely different
 * needs, and {@link MjpegInputStream} already handles it.
 *
 * Timeouts are short on purpose. The Pi is one hop away on the LAN, so a poll
 * that has not answered in a few seconds is not slow, it is gone — usually the
 * phone has left the house — and the caller should find that out quickly and try
 * again later rather than holding a thread open.
 *
 * Every method blocks. Call from a background thread.
 */
public final class PiClient implements PiEventSource {

    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS    = 8_000;

    /**
     * Refuse to buffer more than this from one reply.
     *
     * The Pi caps a page at 500 events, so a legitimate reply is tens of
     * kilobytes. This is not about the Pi misbehaving: it is about what happens
     * when the saved address turns out to be a router's admin page, or anything
     * else on the LAN that answers a GET with a stream that never ends.
     */
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final String baseUrl;

    public PiClient(String baseUrl) {
        // Trailing slash or not is the caller's business, not every call site's.
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** A client for the paired Pi, or null when no address has been saved. */
    public static PiClient forSavedPi(Context context) {
        String base = PiConnectionManager.getBaseUrl(context);
        return base == null ? null : new PiClient(base);
    }

    @Override
    public PiHealth health() throws IOException {
        return PiHealth.fromJson(get("/health"));
    }

    @Override
    public PiEventPage events(long since) throws IOException {
        return PiEventPage.fromJson(get("/events?since=" + Math.max(0, since)), since);
    }

    private JSONObject get(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path)
                .openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        // The Pi answers one request at a time from a small thread pool; leaving
        // sockets open on its behalf is not a kindness.
        connection.setRequestProperty("Connection", "close");

        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Pi answered " + status + " for " + path);
            }
            String body = readBody(connection.getInputStream());
            try {
                return new JSONObject(body);
            } catch (JSONException e) {
                // Something answered, but it was not this service. Saying which
                // path is what turns "sync failed" into "that is your router".
                throw new IOException("Pi sent something that is not JSON for " + path, e);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
            if (out.size() > MAX_RESPONSE_BYTES) {
                throw new IOException("Pi reply exceeded " + MAX_RESPONSE_BYTES + " bytes");
            }
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
