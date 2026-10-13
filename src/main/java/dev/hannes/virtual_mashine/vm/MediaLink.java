package dev.hannes.virtual_mashine.vm;

import dev.hannes.virtual_mashine.Virtual_mashine;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The camera channel of a machine: a virtio serial port ("org.netz.media") read by the netz-media
 * agent in the guest, which hands the pictures to a video device (v4l2loopback in Linux).
 * <p>
 * Each picture is {@code "NMF1"}, a type byte (1 = YUYV), width and height (16 bit) and the length
 * (32 bit, all big-endian), then the pixels. The newest picture always wins: a guest that reads
 * slowly, or not at all, never holds the game up. The agent says hello every few seconds, so the
 * mod knows whether anybody is listening.
 */
public final class MediaLink implements Closeable {
    public static final int WIDTH = 640;
    public static final int HEIGHT = 360;
    private static final byte[] MAGIC = {'N', 'M', 'F', '1'};
    private static final int TYPE_YUYV = 1;
    private static final long AGENT_TIMEOUT_NANOS = 12_000_000_000L;

    private final int port;
    private final Thread thread;
    private final AtomicReference<byte[]> next = new AtomicReference<>();
    private final Object signal = new Object();
    private volatile boolean closed;
    private volatile Socket socket;
    private volatile long agentSeen;

    public MediaLink(int port, String label) {
        this.port = port;
        this.thread = new Thread(this::run, "virtual_mashine-camera-" + label);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** The guest's agent answered recently: pictures sent now reach a video device. */
    public boolean agentConnected() {
        return agentSeen != 0 && System.nanoTime() - agentSeen < AGENT_TIMEOUT_NANOS;
    }

    /** A picture of {@link #WIDTH} x {@link #HEIGHT} pixels in YUYV (2 bytes per pixel). */
    public void sendFrame(byte[] yuyv) {
        if (yuyv.length != WIDTH * HEIGHT * 2) {
            throw new IllegalArgumentException("Frame has " + yuyv.length + " bytes");
        }
        next.set(yuyv);
        synchronized (signal) {
            signal.notifyAll();
        }
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
                Thread reader = new Thread(() -> listen(s), "virtual_mashine-camera-agent");
                reader.setDaemon(true);
                reader.start();
                OutputStream out = s.getOutputStream();
                while (!closed) {
                    byte[] frame = next.getAndSet(null);
                    if (frame == null) {
                        synchronized (signal) {
                            if (next.get() == null) {
                                signal.wait(1000);
                            }
                        }
                        continue;
                    }
                    ByteBuffer header = ByteBuffer.allocate(13);
                    header.put(MAGIC).put((byte) TYPE_YUYV).putShort((short) WIDTH).putShort((short) HEIGHT).putInt(frame.length);
                    out.write(header.array());
                    out.write(frame);
                    out.flush();
                }
            } catch (IOException e) {
                if (!closed) {
                    Virtual_mashine.LOGGER.debug("Camera channel on port {} ended: {}", port, e.toString());
                }
            } catch (InterruptedException e) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Anything the agent sends is its hello. */
    private void listen(Socket s) {
        byte[] buffer = new byte[256];
        try {
            InputStream in = s.getInputStream();
            while (in.read(buffer) >= 0) {
                agentSeen = System.nanoTime();
            }
        } catch (IOException ignored) {
            // The connection is gone; run() reconnects.
        }
    }
}
