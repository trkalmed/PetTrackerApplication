package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class PiSyncIntegrationTest {

    private static final long MINUTE = 60 * 1000L;
    private static final long HOUR = 60 * MINUTE;

    private Context context;
    private AppDatabase db;
    private FakePi pi;
    private long petId;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();
        db = AppDatabase.getInstance(context);
        db.clearAllTables();

        petId = db.petDao().insert(new Pet(
                "Biscuit", "Cat", null, null, null, null, null, null, null, "owner-1"));

        pi = new FakePi();
        Prefs.get(context).edit().clear().apply();
        PiConnectionManager.saveIp(context, "127.0.0.1");
        Prefs.setActivePetId(context, petId);
    }

    @After
    public void tearDown() throws IOException {
        pi.close();
        db.clearAllTables();
        Prefs.get(context).edit().clear().apply();
    }

    private PiEventSync.Result syncAndWait() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        PiEventSync.Result[] captured = new PiEventSync.Result[1];

        PiSync.requestSync(context, result -> {
            captured[0] = result;
            done.countDown();
        });

        assertTrue("the sync should have finished", done.await(20, TimeUnit.SECONDS));
        return captured[0];
    }

    private long todayAt(long millisAgo) {
        long candidate = System.currentTimeMillis() - millisAgo;
        DayRange today = DayRange.today();
        return today.contains(candidate) ? candidate : today.startMillis + 1;
    }

    @Test
    public void aPiOnTheNetworkFillsTheDashboard() throws Exception {
        pi.events = "{'events': ["
                + "{'id': 43, 'kind': 'feeding', 'timestamp_ms': " + todayAt(3 * HOUR) + ","
                + " 'source': 'load-cell', 'grams': 40, 'type': 'EATEN'},"
                + "{'id': 44, 'kind': 'weight', 'timestamp_ms': " + todayAt(2 * HOUR) + ","
                + " 'source': 'load-cell', 'grams': 118.4},"
                + "{'id': 45, 'kind': 'motion', 'timestamp_ms': " + todayAt(2 * MINUTE) + ","
                + " 'source': 'pir', 'room': 'Kitchen'}"
                + "], 'next_cursor': 45, 'has_more': false}";

        PiEventSync.Result result = syncAndWait();

        assertTrue("the Pi is right there", result.reachable);
        assertEquals("the meal and the sighting; not the raw weight", 2, result.stored);

        DashboardStats stats = new PetDataRepository(context)
                .statsForToday(db.petDao().getPetById(petId, "owner-1"));
        assertEquals(Integer.valueOf(40), stats.gramsEaten);
        assertEquals("Kitchen", stats.currentRoom);

        assertNotNull(result.health);
    }

    @Test
    public void theCursorIsSentBackOnTheNextSync() throws Exception {
        pi.events = "{'events': [{'id': 43, 'kind': 'motion', 'timestamp_ms': "
                + todayAt(HOUR) + ", 'source': 'pir', 'room': 'Hall'}],"
                + " 'next_cursor': 43, 'has_more': false}";
        syncAndWait();

        pi.events = "{'events': [], 'next_cursor': 43, 'has_more': false}";
        syncAndWait();

        assertEquals("/events?since=0", pi.requests.get(1));
        assertEquals("/events?since=43", pi.requests.get(3));
    }

    @Test
    public void repointingAtADifferentPiStartsOver() throws Exception {
        pi.events = "{'events': [{'id': 43, 'kind': 'motion', 'timestamp_ms': "
                + todayAt(HOUR) + ", 'source': 'pir', 'room': 'Hall'}],"
                + " 'next_cursor': 43, 'has_more': false}";
        syncAndWait();

        Prefs.get(context).edit()
                .putString(Prefs.KEY_PI_CURSOR_HOST, "192.168.1.99").apply();

        syncAndWait();

        assertEquals("/events?since=0", pi.requests.get(3));
        DashboardStats stats = new PetDataRepository(context)
                .statsForToday(db.petDao().getPetById(petId, "owner-1"));
        assertEquals(1, stats.rooms.size());
    }

    @Test
    public void aPiThatIsNotThereIsReportedCalmly() throws Exception {
        pi.close();

        PiEventSync.Result result = syncAndWait();

        assertFalse(result.reachable);
        assertNotNull(result.error);
    }

    private static final class FakePi implements java.io.Closeable {

        private final ServerSocket socket;
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());

        volatile String health = "{'ok': true, 'time_ms': 1752000000000,"
                + " 'uptime_s': 60, 'latest_cursor': 9999,"
                + " 'sensors': {'scale': true, 'feeder': true, 'camera': true,"
                + " 'location': false, 'motion': {'Kitchen': true}}}";
        volatile String events = "{'events': [], 'next_cursor': 0, 'has_more': false}";

        FakePi() throws IOException {
            socket = new ServerSocket(8080, 0, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(this::serve, "fake-pi");
            thread.setDaemon(true);
            thread.start();
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

                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) { }

                    byte[] body = (path.startsWith("/health") ? health : events)
                            .replace('\'', '"').getBytes(StandardCharsets.UTF_8);

                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 200 \r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                } catch (IOException stopping) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (!socket.isClosed()) socket.close();
        }
    }
}
