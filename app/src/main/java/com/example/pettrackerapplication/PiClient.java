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

/** Talks to the Pi's JSON endpoints over HttpURLConnection. */
public final class PiClient implements PiEventSource {

    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS    = 8_000;

    private static final int FEED_TIMEOUT_MS    = 30_000;

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final String baseUrl;

    public PiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

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

    public void feed(int grams) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("grams", grams);
        } catch (JSONException e) {
            throw new IOException("could not build the feed request", e);
        }
        post("/feed", body);
    }

    public void closeFeeder() throws IOException {
        post("/close", new JSONObject());
    }

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

    private String errorFrom(HttpURLConnection connection, int status, String path) {
        InputStream errors = connection.getErrorStream();
        if (errors != null) {
            try {
                String message = new JSONObject(readBody(errors)).optString("error", "");
                if (!message.isEmpty()) return message;
            } catch (IOException | JSONException ignored) {
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
