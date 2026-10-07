package dev.hannes.virtual_mashine.vm;

import dev.hannes.virtual_mashine.Virtual_mashine;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The machine's serial port (COM1 / ttyS0 / console). QEMU exposes it on a local TCP socket; this
 * class keeps the one allowed connection and hands the bytes to a console cable partner and to
 * listeners such as the OpenWrt set-up.
 */
public final class SerialLine {
    private final int tcpPort;
    private final List<Consumer<byte[]>> taps = new CopyOnWriteArrayList<>();
    private volatile @Nullable SerialLine peer;
    private volatile @Nullable OutputStream out;
    private volatile @Nullable Socket socket;
    private volatile boolean closed;

    SerialLine(int tcpPort, String name) {
        this.tcpPort = tcpPort;
        Thread thread = new Thread(this::run, "virtual_mashine-serial-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    /** The machine on the other end of a console cable, or null when nothing is plugged in. */
    public @Nullable SerialLine peer() {
        return peer;
    }

    public void connectTo(@Nullable SerialLine other) {
        peer = other;
    }

    public void addTap(Consumer<byte[]> tap) {
        taps.add(tap);
    }

    public void removeTap(Consumer<byte[]> tap) {
        taps.remove(tap);
    }

    public void write(byte[] data) {
        OutputStream stream = out;
        if (stream == null) {
            return;
        }
        synchronized (this) {
            try {
                stream.write(data);
                stream.flush();
            } catch (IOException e) {
                // QEMU went away; the reader notices and reconnects.
            }
        }
    }

    public void close() {
        closed = true;
        peer = null;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
    }

    private void run() {
        byte[] buffer = new byte[4096];
        while (!closed) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", tcpPort), 1000);
                s.setTcpNoDelay(true);
                socket = s;
                out = s.getOutputStream();
                InputStream in = s.getInputStream();
                int read;
                while (!closed && (read = in.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    byte[] chunk = Arrays.copyOf(buffer, read);
                    SerialLine partner = peer;
                    if (partner != null) {
                        partner.write(chunk);
                    }
                    for (Consumer<byte[]> tap : taps) {
                        tap.accept(chunk);
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    Virtual_mashine.LOGGER.debug("Serial port {} not reachable yet", tcpPort);
                }
            }
            out = null;
            if (!closed) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
