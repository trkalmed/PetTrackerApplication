package com.example.pettrackerapplication;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

public class MjpegInputStream extends BufferedInputStream {
    private static final int SOI = 0xFFD8;
    private static final int EOI = 0xFFD9;

    public MjpegInputStream(InputStream in) {
        super(in, 8192);
    }

    public byte[] readJpegFrame() throws IOException {
        int prev = 0, cur;

        while (true) {
            cur = read();
            if (cur == -1) return null;
            int marker = (prev << 8) | cur;
            if (marker == SOI) break;
            prev = cur;
        }

        ByteArrayBuilder out = new ByteArrayBuilder(64 * 1024);
        out.append((byte) 0xFF);
        out.append((byte) 0xD8);

        prev = 0;
        while (true) {
            cur = read();
            if (cur == -1) return null;
            out.append((byte) cur);
            int marker = (prev << 8) | cur;
            if (marker == EOI) break;
            prev = cur;
        }
        return out.toByteArray();
    }


    static class ByteArrayBuilder {
        private byte[] buf;
        private int len;

        ByteArrayBuilder(int cap) {
            buf = new byte[cap];
        }

        void append(byte b) {
            if (len >= buf.length) {
                byte[] nb = new byte[buf.length * 2];
                System.arraycopy(buf, 0, nb, 0, buf.length);
                buf = nb;
            }
            buf[len++] = b;
        }

        byte[] toByteArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }
}
