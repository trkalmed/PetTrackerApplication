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

        volatile int status = 200;
        volatile boolean truncateReply = false;
        volatile String healthBody = "{"
                + "'ok': true, 'time_ms': 1752000000000, 'uptime_s': 3600,"
                + "'latest_cursor': 42, 'latest_weight_grams': 118.4,"
                + "'sensors': {'scale': true, 'feeder': true, 'camera': true,"
                + " 'location': false, 'motion': {'Kitchen': true}}}";
        volatile String eventsBody = "{'events': [], 'next_cursor': 0, 'has_more': false}";

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
                    String path = requestLine.split(" ")[1];
                    requests.add(path);

                    // Headers, up to the blank line. Ignored, but they have to be
                    // read or the client's write can block against a full buffer.
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) { /* drain */ }

                    byte[] body = (path.startsWith("/health") ? healthBody : eventsBody)
                            .replace('\'', '"').getBytes(StandardCharsets.UTF_8);

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
