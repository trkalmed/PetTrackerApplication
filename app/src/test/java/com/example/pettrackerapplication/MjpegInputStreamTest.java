package com.example.pettrackerapplication;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class MjpegInputStreamTest {

    private static final byte[] SOI = {(byte) 0xFF, (byte) 0xD8};
    private static final byte[] EOI = {(byte) 0xFF, (byte) 0xD9};

    private static byte[] frame(byte... payload) {
        byte[] out = new byte[SOI.length + payload.length + EOI.length];
        System.arraycopy(SOI, 0, out, 0, 2);
        System.arraycopy(payload, 0, out, 2, payload.length);
        System.arraycopy(EOI, 0, out, 2 + payload.length, 2);
        return out;
    }

    private static MjpegInputStream streamOf(byte[]... chunks) {
        int total = 0;
        for (byte[] c : chunks) total += c.length;
        byte[] all = new byte[total];
        int at = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, all, at, c.length);
            at += c.length;
        }
        return new MjpegInputStream(new ByteArrayInputStream(all));
    }

    @Test
    public void readsASingleFrameWithItsMarkers() throws IOException {
        byte[] expected = frame((byte) 0x01, (byte) 0x02, (byte) 0x03);

        byte[] actual = streamOf(expected).readJpegFrame();

        assertArrayEquals(expected, actual);
    }

    @Test
    public void skipsBoundaryPreambleBeforeTheFirstFrame() throws IOException {
        byte[] preamble = "--boundary\r\nContent-Type: image/jpeg\r\n\r\n".getBytes();
        byte[] expected = frame((byte) 0x0A, (byte) 0x0B);

        byte[] actual = streamOf(preamble, expected).readJpegFrame();

        assertArrayEquals(expected, actual);
    }

    @Test
    public void readsConsecutiveFramesSeparatedByBoundaries() throws IOException {
        byte[] first  = frame((byte) 0x11);
        byte[] second = frame((byte) 0x22, (byte) 0x33);
        byte[] gap    = "\r\n--boundary\r\n".getBytes();

        MjpegInputStream stream = streamOf(first, gap, second);

        assertArrayEquals(first, stream.readJpegFrame());
        assertArrayEquals(second, stream.readJpegFrame());
    }

    @Test
    public void returnsNullAtEndOfStream() throws IOException {
        MjpegInputStream stream = streamOf(frame((byte) 0x01));

        stream.readJpegFrame();

        assertNull(stream.readJpegFrame());
    }

    @Test
    public void returnsNullWhenTheStreamIsCutMidFrame() throws IOException {
        byte[] truncated = {(byte) 0xFF, (byte) 0xD8, (byte) 0x01, (byte) 0x02};

        assertNull(streamOf(truncated).readJpegFrame());
    }

    @Test
    public void returnsNullWhenNoFrameIsEverPresent() throws IOException {
        assertNull(streamOf("no jpeg here at all".getBytes()).readJpegFrame());
    }

    @Test
    public void growsPastTheInitialBufferForLargeFrames() throws IOException {
        byte[] payload = new byte[200_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        byte[] expected = frame(payload);

        byte[] actual = streamOf(expected).readJpegFrame();

        assertEquals(expected.length, actual.length);
        assertArrayEquals(expected, actual);
    }
}
