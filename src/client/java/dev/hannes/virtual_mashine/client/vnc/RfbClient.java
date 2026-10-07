package dev.hannes.virtual_mashine.client.vnc;

import dev.hannes.virtual_mashine.Virtual_mashine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A small VNC (RFB 3.8) viewer for QEMU's built-in VNC server, reached directly on localhost or
 * through the Minecraft connection ({@link TunnelTransport}), where the screen is zlib-compressed.
 * <p>
 * A background thread keeps an RGBA copy of the guest screen; the render thread uploads the
 * changed rows to a texture via {@link #consumeFrame}. Input is sent with QEMU's extended key
 * events, which carry raw scancodes, so the guest's own keyboard layout decides the characters.
 */
public final class RfbClient implements Closeable {
    public enum Status { CONNECTING, CONNECTED, CLOSED }

    private static final int ENCODING_RAW = 0;
    private static final int ENCODING_COPY_RECT = 1;
    private static final int ENCODING_ZLIB = 6;
    /** Pseudo-encodings -256..-247 select zlib level 0..9; 6 is a good speed/size balance. */
    private static final int ENCODING_COMPRESS_LEVEL_6 = -250;
    private static final int ENCODING_DESKTOP_SIZE = -223;
    private static final int ENCODING_QEMU_EXTENDED_KEY = -258;

    private final int port;
    private final RfbTransport transport;
    private final Object frameLock = new Object();
    private final Object writeLock = new Object();
    private final Thread thread;

    private volatile Status status = Status.CONNECTING;
    private volatile String error = "";
    private volatile boolean extendedKeys;
    private DataOutputStream out;

    // Guarded by frameLock.
    private ByteBuffer pixels;
    private int width;
    private int height;
    private int dirtyTop = Integer.MAX_VALUE;
    private int dirtyBottom = -1;
    private boolean resized;

    // Reader thread only.
    private byte[] scratch = new byte[0];
    private byte[] compressed = new byte[0];
    private final Inflater inflater = new Inflater();

    /**
     * @param port QEMU's VNC port on the hosting computer; identifies the session, also for tunnels
     */
    public RfbClient(RfbTransport transport, int port) {
        this.transport = transport;
        this.port = port;
        this.thread = new Thread(this::run, "virtual_mashine-vnc-" + port);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public int port() {
        return port;
    }

    public Status status() {
        return status;
    }

    public String error() {
        return error;
    }

    /** Guest screen size, or 0 before the first frame. */
    public int width() {
        synchronized (frameLock) {
            return width;
        }
    }

    public int height() {
        synchronized (frameLock) {
            return height;
        }
    }

    @FunctionalInterface
    public interface FrameConsumer {
        /**
         * @param pixels  tightly packed RGBA rows of the whole screen; only valid during the call
         * @param resized the screen size changed since the last call
         */
        void accept(ByteBuffer pixels, int width, int height, int dirtyTop, int dirtyBottom, boolean resized);
    }

    /** Hands the rows changed since the last call to {@code consumer}; does nothing if nothing changed. */
    public void consumeFrame(FrameConsumer consumer) {
        synchronized (frameLock) {
            if (pixels == null || dirtyBottom < dirtyTop) {
                return;
            }
            consumer.accept(pixels, width, height, dirtyTop, dirtyBottom, resized);
            dirtyTop = Integer.MAX_VALUE;
            dirtyBottom = -1;
            resized = false;
        }
    }

    // ---------------------------------------------------------------- input

    /**
     * @param xtScancode XT scancode (0xE0-prefixed keys have the high bit set), 0 if unknown
     * @param keysym     X11 keysym, used when the server lacks extended key events
     */
    public void sendKey(boolean down, int xtScancode, int keysym) {
        if (status != Status.CONNECTED) {
            return;
        }
        synchronized (writeLock) {
            try {
                if (extendedKeys && xtScancode != 0) {
                    out.writeByte(255);
                    out.writeByte(0);
                    out.writeShort(down ? 1 : 0);
                    out.writeInt(keysym);
                    out.writeInt(xtScancode);
                } else if (keysym != 0) {
                    out.writeByte(4);
                    out.writeByte(down ? 1 : 0);
                    out.writeShort(0);
                    out.writeInt(keysym);
                }
                out.flush();
            } catch (IOException e) {
                fail(e);
            }
        }
    }

    /** @param buttonMask bit 0 left, 1 middle, 2 right, 3/4 wheel up/down, 5/6 wheel left/right */
    public void sendPointer(int buttonMask, int x, int y) {
        if (status != Status.CONNECTED) {
            return;
        }
        synchronized (writeLock) {
            try {
                out.writeByte(5);
                out.writeByte(buttonMask);
                out.writeShort(Math.max(0, x));
                out.writeShort(Math.max(0, y));
                out.flush();
            } catch (IOException e) {
                fail(e);
            }
        }
    }

    @Override
    public void close() {
        status = Status.CLOSED;
        transport.close();
        thread.interrupt();
    }

    // ---------------------------------------------------------------- protocol

    private void run() {
        try {
            transport.connect();
            DataInputStream in = new DataInputStream(new BufferedInputStream(transport.input(), 1 << 16));
            out = new DataOutputStream(new BufferedOutputStream(transport.output(), 1 << 12));
            handshake(in);
            status = Status.CONNECTED;
            requestUpdate(false);
            while (status == Status.CONNECTED) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0 -> readFramebufferUpdate(in);
                    case 1 -> { // SetColourMapEntries (not used in true-colour mode)
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        in.skipNBytes(in.readUnsignedShort() * 6L);
                    }
                    case 2 -> {
                        // Bell.
                    }
                    case 3 -> { // ServerCutText
                        in.skipNBytes(3);
                        in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
                    }
                    default -> throw new IOException("Unknown VNC message " + type);
                }
            }
        } catch (IOException e) {
            if (status != Status.CLOSED) {
                fail(e);
            }
        } finally {
            status = Status.CLOSED;
            transport.close();
            inflater.end();
        }
    }

    private void handshake(DataInputStream in) throws IOException {
        byte[] version = new byte[12];
        in.readFully(version);
        String serverVersion = new String(version, StandardCharsets.US_ASCII);
        if (!serverVersion.startsWith("RFB ")) {
            throw new IOException("Not a VNC server");
        }
        out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();

        int count = in.readUnsignedByte();
        if (count == 0) {
            throw new IOException("VNC server refused: " + readString(in));
        }
        boolean hasNone = false;
        for (int i = 0; i < count; i++) {
            hasNone |= in.readUnsignedByte() == 1;
        }
        if (!hasNone) {
            throw new IOException("VNC server wants a password");
        }
        out.writeByte(1);
        out.flush();
        if (in.readInt() != 0) {
            throw new IOException("VNC authentication failed: " + readString(in));
        }

        out.writeByte(1); // ClientInit: shared session
        out.flush();

        int w = in.readUnsignedShort();
        int h = in.readUnsignedShort();
        in.skipNBytes(16); // server pixel format, replaced below
        in.skipNBytes(in.readInt() & 0xFFFFFFFFL); // desktop name
        resize(w, h);

        synchronized (writeLock) {
            // SetPixelFormat: 32 bpp little endian with red in the lowest byte = RGBA memory layout.
            out.writeByte(0);
            out.write(new byte[3]);
            out.writeByte(32);
            out.writeByte(24);
            out.writeByte(0);
            out.writeByte(1);
            out.writeShort(255);
            out.writeShort(255);
            out.writeShort(255);
            out.writeByte(0);
            out.writeByte(8);
            out.writeByte(16);
            out.write(new byte[3]);

            // QEMU uses the first encoding in this list that it supports.
            int[] encodings = transport.remote()
                    ? new int[]{ENCODING_ZLIB, ENCODING_COPY_RECT, ENCODING_RAW, ENCODING_DESKTOP_SIZE, ENCODING_QEMU_EXTENDED_KEY, ENCODING_COMPRESS_LEVEL_6}
                    : new int[]{ENCODING_RAW, ENCODING_COPY_RECT, ENCODING_DESKTOP_SIZE, ENCODING_QEMU_EXTENDED_KEY};
            out.writeByte(2);
            out.writeByte(0);
            out.writeShort(encodings.length);
            for (int encoding : encodings) {
                out.writeInt(encoding);
            }
            out.flush();
        }
    }

    private void readFramebufferUpdate(DataInputStream in) throws IOException {
        in.readUnsignedByte();
        int rects = in.readUnsignedShort();
        for (int i = 0; i < rects; i++) {
            int x = in.readUnsignedShort();
            int y = in.readUnsignedShort();
            int w = in.readUnsignedShort();
            int h = in.readUnsignedShort();
            int encoding = in.readInt();
            switch (encoding) {
                case ENCODING_RAW -> readRaw(in, x, y, w, h);
                case ENCODING_ZLIB -> readZlib(in, x, y, w, h);
                case ENCODING_COPY_RECT -> copyRect(in.readUnsignedShort(), in.readUnsignedShort(), x, y, w, h);
                case ENCODING_DESKTOP_SIZE -> resize(w, h);
                case ENCODING_QEMU_EXTENDED_KEY -> extendedKeys = true;
                default -> throw new IOException("Unsupported VNC encoding " + encoding);
            }
        }
        requestUpdate(true);
    }

    private void readRaw(DataInputStream in, int x, int y, int w, int h) throws IOException {
        int length = w * h * 4;
        ensureScratch(length);
        // Read outside the lock so a slow connection never stalls rendering.
        in.readFully(scratch, 0, length);
        putPixels(x, y, w, h);
    }

    /** Zlib encoding: raw pixels through one zlib stream that lasts the whole connection. */
    private void readZlib(DataInputStream in, int x, int y, int w, int h) throws IOException {
        int compressedLength = in.readInt();
        if (compressedLength < 0 || compressedLength > 64 * 1024 * 1024) {
            throw new IOException("Bad zlib rectangle length " + compressedLength);
        }
        if (compressed.length < compressedLength) {
            compressed = new byte[compressedLength];
        }
        in.readFully(compressed, 0, compressedLength);
        int length = w * h * 4;
        ensureScratch(length);
        inflater.setInput(compressed, 0, compressedLength);
        int produced = 0;
        try {
            while (produced < length) {
                int n = inflater.inflate(scratch, produced, length - produced);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary() || inflater.finished())) {
                    throw new IOException("Truncated zlib rectangle");
                }
                produced += n;
            }
        } catch (DataFormatException e) {
            throw new IOException("Corrupt zlib data", e);
        }
        putPixels(x, y, w, h);
    }

    private void ensureScratch(int length) {
        if (scratch.length < length) {
            scratch = new byte[length];
        }
    }

    /** Copies the rectangle held in {@code scratch} into the screen. */
    private void putPixels(int x, int y, int w, int h) {
        int length = w * h * 4;
        for (int i = 3; i < length; i += 4) {
            scratch[i] = (byte) 0xFF; // The padding byte becomes an opaque alpha channel.
        }
        synchronized (frameLock) {
            if (pixels == null) {
                return;
            }
            int clippedW = Math.min(w, width - x);
            int clippedH = Math.min(h, height - y);
            if (clippedW <= 0 || clippedH <= 0) {
                return;
            }
            for (int row = 0; row < clippedH; row++) {
                pixels.put(((y + row) * width + x) * 4, scratch, row * w * 4, clippedW * 4);
            }
            markDirty(y, y + clippedH - 1);
        }
    }

    private void copyRect(int srcX, int srcY, int x, int y, int w, int h) {
        synchronized (frameLock) {
            if (pixels == null || srcX + w > width || srcY + h > height || x + w > width || y + h > height) {
                return;
            }
            byte[] copy = new byte[w * h * 4];
            for (int row = 0; row < h; row++) {
                pixels.get(((srcY + row) * width + srcX) * 4, copy, row * w * 4, w * 4);
            }
            for (int row = 0; row < h; row++) {
                pixels.put(((y + row) * width + x) * 4, copy, row * w * 4, w * 4);
            }
            markDirty(y, y + h - 1);
        }
    }

    private void resize(int w, int h) {
        synchronized (frameLock) {
            if (w == width && h == height && pixels != null) {
                return;
            }
            width = w;
            height = h;
            pixels = w > 0 && h > 0 ? ByteBuffer.allocateDirect(w * h * 4) : null;
            resized = true;
            markDirty(0, h - 1);
        }
    }

    private void markDirty(int top, int bottom) {
        dirtyTop = Math.min(dirtyTop, top);
        dirtyBottom = Math.max(dirtyBottom, bottom);
    }

    private void requestUpdate(boolean incremental) throws IOException {
        int w;
        int h;
        synchronized (frameLock) {
            w = width;
            h = height;
        }
        synchronized (writeLock) {
            out.writeByte(3);
            out.writeByte(incremental ? 1 : 0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(w);
            out.writeShort(h);
            out.flush();
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void fail(IOException e) {
        if (status == Status.CLOSED) {
            return;
        }
        error = e.getMessage() == null ? e.toString() : e.getMessage();
        Virtual_mashine.LOGGER.debug("VNC connection to port {} ended", port, e);
        status = Status.CLOSED;
        transport.close();
    }
}
