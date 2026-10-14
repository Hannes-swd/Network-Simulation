package dev.hannes.virtual_mashine.vm;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Plugs a monitor into one screen (head) of a running machine, or pulls it out: a short VNC
 * connection to that head's server that asks for a desktop size (RFB SetDesktopSize). QEMU hands the
 * size to the graphics card, which reports the monitor as connected with that as its preferred mode;
 * a size of 0 x 0 reports it as disconnected. The guest sees it like a real monitor being plugged in.
 */
final class VncScreenControl {
    private static final int ENCODING_EXTENDED_DESKTOP_SIZE = -308;
    private static final int MSG_SET_ENCODINGS = 2;
    private static final int MSG_SET_DESKTOP_SIZE = 251;

    private VncScreenControl() {
    }

    /** Blocks for at most a few seconds. */
    static void requestSize(int vncPort, int width, int height) throws IOException {
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress("127.0.0.1", vncPort), 2000);
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            in.readFully(new byte[12]);
            out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            int types = in.readUnsignedByte();
            if (types == 0) {
                throw new IOException("VNC refused the connection");
            }
            boolean none = false;
            for (int i = 0; i < types; i++) {
                none |= in.readUnsignedByte() == 1;
            }
            if (!none) {
                throw new IOException("VNC wants authentication");
            }
            out.writeByte(1);
            out.flush();
            if (in.readInt() != 0) {
                throw new IOException("VNC security handshake failed");
            }
            out.writeByte(1); // Shared: viewers that are connected stay connected.
            out.flush();
            in.readFully(new byte[4 + 16]);
            in.readFully(new byte[in.readInt()]);

            out.writeByte(MSG_SET_ENCODINGS);
            out.writeByte(0);
            out.writeShort(1);
            out.writeInt(ENCODING_EXTENDED_DESKTOP_SIZE);
            out.writeByte(MSG_SET_DESKTOP_SIZE);
            out.writeByte(0);
            out.writeShort(width);
            out.writeShort(height);
            out.writeByte(1);
            out.writeByte(0);
            out.writeInt(0); // Screen id.
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(width);
            out.writeShort(height);
            out.writeInt(0);
            out.flush();
            awaitAnswer(in);
        }
    }

    /** Waits for QEMU's reply to the size request, so the connection does not end before it was read. */
    private static void awaitAnswer(DataInputStream in) throws IOException {
        try {
            for (int messages = 0; messages < 8; messages++) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0 -> {
                        in.readUnsignedByte();
                        int rects = in.readUnsignedShort();
                        for (int r = 0; r < rects; r++) {
                            int reason = in.readUnsignedShort();
                            in.readUnsignedShort();
                            in.readUnsignedShort();
                            in.readUnsignedShort();
                            int encoding = in.readInt();
                            if (encoding != ENCODING_EXTENDED_DESKTOP_SIZE) {
                                return; // Nothing else was asked for; stop reading.
                            }
                            int screens = in.readUnsignedByte();
                            in.readFully(new byte[3 + 16 * screens]);
                            if (reason == 1) {
                                return; // The answer to this client's request.
                            }
                        }
                    }
                    case 2 -> {
                        // Bell.
                    }
                    case 3 -> {
                        in.readFully(new byte[3]);
                        in.readFully(new byte[in.readInt()]);
                    }
                    default -> {
                        return;
                    }
                }
            }
        } catch (SocketTimeoutException e) {
            // QEMU took the request anyway; older versions do not answer.
        }
    }
}
