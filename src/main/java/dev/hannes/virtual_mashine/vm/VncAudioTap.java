package dev.hannes.virtual_mashine.vm;

import dev.hannes.virtual_mashine.Virtual_mashine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Listens to a machine's sound: a VNC connection to its QEMU that never asks for the picture,
 * only for QEMU's audio stream. The sound card has to be wired to the VNC server
 * ({@code -vnc ...,audiodev=}); without that QEMU never confirms the extension and nothing arrives.
 */
public final class VncAudioTap implements Closeable {
    public static final int RATE = 44_100;
    public static final int CHANNELS = 2;
    /** Sound is handed on in pieces of about 20 ms. */
    private static final int CHUNK_BYTES = RATE * CHANNELS * 2 / 50;
    private static final int ENCODING_QEMU_AUDIO = -259;
    private static final int MSG_QEMU = 255;
    private static final int QEMU_AUDIO = 1;

    public interface Listener {
        /** 16-bit signed little-endian PCM, stereo, {@link #RATE}; called on the tap's thread. */
        void audio(byte[] pcm);
    }

    private final int port;
    private final Listener listener;
    private final Thread thread;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private volatile boolean closed;
    private volatile Socket socket;

    public VncAudioTap(int port, String label, Listener listener) {
        this.port = port;
        this.listener = listener;
        this.thread = new Thread(this::run, "virtual_mashine-sound-" + label);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void close() {
        closed = true;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
        thread.interrupt();
    }

    private void run() {
        while (!closed) {
            try (Socket s = new Socket()) {
                socket = s;
                s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                s.setTcpNoDelay(true);
                session(new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16)),
                        new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 256)));
            } catch (IOException e) {
                if (!closed) {
                    Virtual_mashine.LOGGER.debug("Sound connection to VNC port {} ended: {}", port, e.toString());
                }
            }
            pending.reset();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void session(DataInputStream in, DataOutputStream out) throws IOException {
        byte[] version = new byte[12];
        in.readFully(version);
        if (!new String(version, StandardCharsets.US_ASCII).startsWith("RFB ")) {
            throw new IOException("Not a VNC server");
        }
        out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        int count = in.readUnsignedByte();
        boolean none = false;
        for (int i = 0; i < count; i++) {
            none |= in.readUnsignedByte() == 1;
        }
        if (!none) {
            throw new IOException("VNC server wants authentication");
        }
        out.writeByte(1);
        out.flush();
        if (in.readInt() != 0) {
            throw new IOException("VNC authentication failed");
        }
        out.writeByte(1); // shared session
        out.flush();
        in.skipNBytes(4 + 16); // size and pixel format
        in.skipNBytes(in.readInt() & 0xFFFFFFFFL); // desktop name
        out.writeByte(2); // SetEncodings: the audio extension only, so the picture is never sent
        out.writeByte(0);
        out.writeShort(1);
        out.writeInt(ENCODING_QEMU_AUDIO);
        out.flush();
        while (!closed) {
            int type = in.readUnsignedByte();
            switch (type) {
                case 0 -> { // FramebufferUpdate: only QEMU's confirmation of the audio extension comes this way
                    in.readUnsignedByte();
                    int rects = in.readUnsignedShort();
                    for (int i = 0; i < rects; i++) {
                        in.skipNBytes(8);
                        int encoding = in.readInt();
                        if (encoding != ENCODING_QEMU_AUDIO) {
                            throw new IOException("Unexpected VNC rectangle " + encoding);
                        }
                        out.writeByte(MSG_QEMU);
                        out.writeByte(QEMU_AUDIO);
                        out.writeShort(2); // set format: signed 16 bit, stereo
                        out.writeByte(3);
                        out.writeByte(CHANNELS);
                        out.writeInt(RATE);
                        out.writeByte(MSG_QEMU);
                        out.writeByte(QEMU_AUDIO);
                        out.writeShort(0); // enable
                        out.flush();
                    }
                }
                case 1 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    in.skipNBytes(in.readUnsignedShort() * 6L);
                }
                case 2 -> {
                    // Bell.
                }
                case 3 -> {
                    in.skipNBytes(3);
                    int length = in.readInt();
                    in.skipNBytes(Math.abs((long) length));
                }
                case MSG_QEMU -> readAudio(in);
                default -> throw new IOException("Unknown VNC message " + type);
            }
        }
    }

    private void readAudio(DataInputStream in) throws IOException {
        if (in.readUnsignedByte() != QEMU_AUDIO) {
            throw new IOException("Unknown QEMU VNC message");
        }
        int operation = in.readUnsignedShort();
        if (operation == 2) {
            int length = in.readInt();
            if (length < 0 || length > 1 << 22) {
                throw new IOException("Bad audio block " + length);
            }
            byte[] data = in.readNBytes(length);
            if (data.length < length) {
                throw new IOException("Connection closed");
            }
            pending.write(data, 0, data.length);
            if (pending.size() >= CHUNK_BYTES) {
                flush();
            }
        } else if (operation == 0) {
            flush(); // The guest stopped playing: hand on the rest at once.
        } else if (operation != 1) {
            throw new IOException("Unknown QEMU audio operation " + operation);
        }
    }

    private void flush() {
        if (pending.size() == 0) {
            return;
        }
        byte[] chunk = pending.toByteArray();
        pending.reset();
        try {
            listener.audio(chunk);
        } catch (RuntimeException e) {
            Virtual_mashine.LOGGER.warn("Handing on sound of VNC port {} failed", port, e);
        }
    }
}
