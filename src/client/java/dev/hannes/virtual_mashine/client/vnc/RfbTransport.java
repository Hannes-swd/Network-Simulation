package dev.hannes.virtual_mashine.client.vnc;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** The byte stream a {@link RfbClient} talks VNC over. */
public interface RfbTransport extends Closeable {
    /** Blocks until connected. Called on the VNC reader thread. */
    void connect() throws IOException;

    InputStream input() throws IOException;

    OutputStream output() throws IOException;

    /** Whether bandwidth matters, so the server should compress the screen. */
    boolean remote();

    @Override
    void close();

    /** Direct connection to QEMU on this computer (the player hosting the world). */
    static RfbTransport local(int port) {
        return new RfbTransport() {
            private final Socket socket = new Socket();

            @Override
            public void connect() throws IOException {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            }

            @Override
            public InputStream input() throws IOException {
                return socket.getInputStream();
            }

            @Override
            public OutputStream output() throws IOException {
                return socket.getOutputStream();
            }

            @Override
            public boolean remote() {
                return false;
            }

            @Override
            public void close() {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Closing anyway.
                }
            }
        };
    }
}
