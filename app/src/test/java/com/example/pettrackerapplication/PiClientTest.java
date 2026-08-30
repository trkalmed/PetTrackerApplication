package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@link PiClient} against a real server on a real socket.
 *
 * Not a mock, because the interesting parts are exactly what a mock would stand
 * in for: that the query string is built the way the Pi parses it, that a
 * non-200 is a failure rather than an empty page, and that something else on the
 * LAN answering the same port produces a clear error instead of silence. The
 * fake below answers what pi/README.md documents, byte for byte.
 *
 * It speaks HTTP over a plain socket rather than using a server library: the
 * JDK's own is not on the Android unit-test classpath, and thirty lines here is
 * cheaper than an emulator for a test this fast.
 */
public class PiClientTest {

    private FakePi pi;
    private PiClient client;

    @Before
    public void startServer() throws IOException {
        pi = new FakePi();
        client = new PiClient("http://127.0.0.1:" + pi.port());
    }

    @After
    public void stopServer() throws IOException {
        pi.close();
    }

    @Test
    public void readsHealth() throws IOException {
        PiHealth health = client.health();

        assertTrue(health.ok);
        assertEquals(42, health.latestCursor);
        assertTrue(health.motionSensors.get("Kitchen"));
    }

    // ── Feeding ─────────────────────────────────────────────────────────────

    @Test
    public void feedPostsTheGramsThePiExpects() throws IOException {
        client.feed(40);

        assertEquals("POST", pi.methods.get(0));
        assertEquals("/feed", pi.requests.get(0));
        // The Pi rejects anything that is not a positive integer under the key
        // "grams", so the shape of this body is the contract.
        assertEquals("{\"grams\":40}", pi.bodies.get(0));
    }

    @Test
    public void tarePostsToTheRightPath() throws IOException {
        client.tare();

        assertEquals("POST", pi.methods.get(0));
        assertEquals("/tare", pi.requests.get(0));
    }

    /**
     * A refusal arrives as the Pi's own sentence, not as a status code.
     *
     * "Pi answered 503" sends someone to the logs. "feeder unavailable" sends
     * them to the servo's plug, which is where the problem is.
     */
    @Test
    public void aRefusedFeedCarriesThePisExplanation() {
        pi.status = 503;
        pi.postBody = "{'error': 'feeder unavailable'}";

        try {
            client.feed(40);
            fail("a 503 should not look like success");
        } catch (IOException e) {
            assertEquals("feeder unavailable", e.getMessage());
        }
    }

    @Test
    public void aRefusalWithNoExplanationStillFails() {
        pi.status = 400;
        pi.postBody = "{}";

        try {
            client.feed(40);
            fail("a 400 should not look like success");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("400"));
        }
    }

    @Test
    public void sendsTheCursorTheWayThePiParsesIt() throws IOException {
        client.events(42);

        // The Pi reads ?since=<int>. Getting the name or the shape of this wrong
        // means every poll returns the whole log from the beginning — which the
        // remote ids would quietly absorb, so nothing would ever look broken.
        assertEquals("/events?since=42", pi.requests.get(0));
    }

    @Test
    public void aNegativeCursorIsSentAsZero() throws IOException {
        client.events(-5);

        assertEquals("/events?since=0", pi.requests.get(0));
    }

    @Test
    public void readsAPageOfEvents() throws IOException {
        pi.eventsBody = "{'events': ["
                + "{'id': 43, 'kind': 'feeding', 'timestamp_ms': 1752000000000,"
                + " 'source': 'load-cell', 'grams': 40, 'type': 'EATEN'},"
                + "{'id': 44, 'kind': 'motion', 'timestamp_ms': 1752000005000,"
                + " 'source': 'pir', 'room': 'Kitchen'}"
                + "], 'next_cursor': 44, 'has_more': true}";

        PiEventPage page = client.events(0);

        assertEquals(2, page.events.size());
        assertEquals(44, page.nextCursor);
        assertTrue(page.hasMore);
    }

    @Test
    public void anErrorStatusIsAFailureNotAnEmptyPage() {
        pi.status = 500;
        pi.eventsBody = "{'error': 'boom'}";

        try {
            client.events(0);
            fail("a 500 must not read as 'nothing happened'");
        } catch (IOException expected) {
            // An empty page would advance nothing and look like a healthy quiet
            // Pi, so the failure would never surface anywhere.
            assertTrue(expected.getMessage().contains("500"));
        }
    }

    @Test
    public void aReplyThatIsNotJsonSaysSo() {
        // Typing the router's address into the Pi Connection dialog is a thing
        // people do, and it answers a GET perfectly happily.
        pi.healthBody = "<html><body>Admin login</body></html>";

        try {
            client.health();
            fail("an HTML page is not a health reply");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not JSON"));
        }
    }

    @Test
    public void aConnectionCutMidReplyIsAFailure() {
        pi.truncateReply = true;
        pi.eventsBody = "{'events': [{'id': 43, 'kind': 'motion',"
                + " 'timestamp_ms': 1752000000000, 'room': 'Kitchen'}],"
                + " 'next_cursor': 43, 'has_more': false}";

        try {
            client.events(0);
            fail("half a page of JSON is not a page");
        } catch (IOException expected) {
            // Wi-Fi dropping mid-poll. The cursor must not advance over events
            // that were never fully received.
        }
    }

    @Test
    public void aClosedPortFails() {
        PiClient nothingThere = new PiClient("http://127.0.0.1:1");

        try {
            nothingThere.health();
            fail("nothing is listening on port 1");
        } catch (IOException expected) {
            // The ordinary case — the phone is not on the house network — and it
            // has to arrive as an IOException the sync can report calmly.
        }
    }

    @Test
    public void aTrailingSlashOnTheBaseUrlIsNotTwoSlashes() throws IOException {
        PiClient sloppy = new PiClient("http://127.0.0.1:" + pi.port() + "/");

        sloppy.health();

        assertEquals("/health", pi.requests.get(0));
    }

    /** A Pi, near enough: one connection at a time, canned replies per path. */
    private static final class FakePi implements Closeable {

        private final ServerSocket socket;
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final List<String> methods  = Collections.synchronizedList(new ArrayList<>());
        final List<String> bodies   = Collections.synchronizedList(new ArrayList<>());

        volatile int status = 200;
        volatile boolean truncateReply = false;
        volatile String healthBody = "{"
                + "'ok': true, 'time_ms': 1752000000000, 'uptime_s': 3600,"
                + "'latest_cursor': 42, 'latest_weight_grams': 118.4,"
                + "'sensors': {'scale': true, 'feeder': true, 'camera': true,"
                + " 'location': false, 'motion': {'Kitchen': true}}}";
        volatile String eventsBody = "{'events': [], 'next_cursor': 0, 'has_more': false}";
        volatile String postBody = "{'requested_grams': 40,"
                + " 'note': 'actual amount comes from the load cell'}";

        FakePi() throws IOException {
            socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(this::serve, "fake-pi");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            client.getInputStream(), StandardCharsets.UTF_8));

                    String requestLine = in.readLine();
                    if (requestLine == null) continue;
                    String[] parts = requestLine.split(" ");
                    String method = parts[0];
                    String path = parts[1];
                    requests.add(path);
                    methods.add(method);

                    // Headers, up to the blank line. They have to be read or the
                    // client's write can block against a full buffer — and the
                    // length is needed to know where a POST body ends.
                    int length = 0;
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        String lower = line.toLowerCase(Locale.ROOT);
                        if (lower.startsWith("content-length:")) {
                            length = Integer.parseInt(
                                    line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    if (length > 0) {
                        char[] payload = new char[length];
                        int read = 0;
                        while (read < length) {
                            int n = in.read(payload, read, length - read);
                            if (n < 0) break;
                            read += n;
                        }
                        bodies.add(new String(payload, 0, Math.max(0, read)));
                    }

                    String reply = path.startsWith("/health") ? healthBody
                            : path.startsWith("/feed") || path.startsWith("/tare")
                                    ? postBody : eventsBody;
                    byte[] body = reply.replace('\'', '"')
                            .getBytes(StandardCharsets.UTF_8);

                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 " + status + " \r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));

                    // Promise the full length, send half, hang up: what a phone
                    // walking out of range actually looks like.
                    out.write(body, 0, truncateReply ? body.length / 2 : body.length);
                    out.flush();
                } catch (IOException stopping) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
