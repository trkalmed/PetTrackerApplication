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
     * Longer, because a dispense blocks until the food has actually moved.
     *
     * A paddle wheel takes roughly a second per pocket and the Pi allows twenty
     * pockets in one request, so a legitimate reply can be twenty seconds away.
     * Timing out early would leave the phone reporting a failure while the
     * hopper is still turning, which is the one wrong answer here: the user
     * would press it again.
     */
    private static final int FEED_TIMEOUT_MS    = 30_000;

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

    /**
     * Asks the feeder to dispense, and waits for it to finish.
     *
     * The reply says what was <em>requested</em>, never what fell. A servo
     * controls how long a hopper is open or how many pockets turn, not how much
     * kibble comes out of either — the load cell measures that, the Pi's meal
     * detector turns it into a feeding event, and the app hears about it on the
     * next poll. So this returns nothing: treating the request as a meal would
     * put a number on the dashboard that nothing weighed.
     *
     * Blocks for the whole dispense, which for a paddle wheel is about a second
     * per pocket. The read timeout allows for that; a request for ten pockets
     * is close to it, which is why the Pi caps a single request at 500 g.
     *
     * @throws IOException with the Pi's own words when it refuses — an
     *         unavailable feeder and an unreachable Pi are different problems
     *         and the person holding the phone can act on the difference.
     */
    public void feed(int grams) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("grams", grams);
        } catch (JSONException e) {
            throw new IOException("could not build the feed request", e);
        }
        post("/feed", body);
    }

    /**
     * Shuts a hopper that stays open until it is told to.
     *
     * Harmless against the metered feeders, which answer that they close
     * themselves — so a screen may call this without first knowing which design
     * is on the other end.
     */
    public void closeFeeder() throws IOException {
        post("/close", new JSONObject());
    }

    /** Zeroes the scale, so "empty bowl" reads as 0 g rather than the bowl. */
    public void tare() throws IOException {
        post("/tare", new JSONObject());
    }

    private JSONObject post(String path, JSONObject body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path)
                .openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(FEED_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Connection", "close");

        try {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException(errorFrom(connection, status, path));
            }
            try {
                return new JSONObject(readBody(connection.getInputStream()));
            } catch (JSONException e) {
                throw new IOException("Pi sent something that is not JSON for " + path, e);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * The Pi's explanation, if it gave one.
     *
     * It answers a refusal with {@code {"error": "feeder unavailable"}}, which
     * is worth surfacing verbatim: "503" tells the user nothing, and "the feeder
     * is not connected" tells them exactly which cable to look at.
     */
    private String errorFrom(HttpURLConnection connection, int status, String path) {
        InputStream errors = connection.getErrorStream();
        if (errors != null) {
            try {
                String message = new JSONObject(readBody(errors)).optString("error", "");
                if (!message.isEmpty()) return message;
            } catch (IOException | JSONException ignored) {
                // No usable explanation. The status code below is all there is.
            }
        }
        return "Pi answered " + status + " for " + path;
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
