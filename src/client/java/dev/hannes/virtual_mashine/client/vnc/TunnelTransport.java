package dev.hannes.virtual_mashine.client.vnc;

import dev.hannes.virtual_mashine.network.TunnelPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * VNC through the Minecraft connection, for players whose game does not run on the computer that
 * hosts the virtual machines. Bytes from the server arrive as payloads on the main thread and are
 * queued for the VNC reader thread; writes are sent as payloads on every flush.
 */
public final class TunnelTransport implements RfbTransport {
    private static final Map<Integer, TunnelTransport> OPEN = new ConcurrentHashMap<>();
    private static final byte[] END = new byte[0];

    private final int id = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
    private final UUID pcId;
    /** Which screen of the machine's graphics card. */
    private final int head;
    private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    private final InputStream input = new InputStream() {
        private byte[] current = END;
        private int position;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (position >= current.length) {
                if (closed && incoming.isEmpty()) {
                    return -1;
                }
                try {
                    byte[] next = incoming.poll(250, TimeUnit.MILLISECONDS);
                    if (next != null) {
                        current = next;
                        position = 0;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
            }
            int count = Math.min(length, current.length - position);
            System.arraycopy(current, position, target, offset, count);
            position += count;
            return count;
        }
    };

    private final OutputStream output = new OutputStream() {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            pending.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            pending.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            if (closed) {
                throw new IOException("Tunnel closed");
            }
            byte[] all = pending.toByteArray();
            pending.reset();
            for (int start = 0; start < all.length; start += TunnelPayloads.MAX_CLIENT_CHUNK) {
                int end = Math.min(all.length, start + TunnelPayloads.MAX_CLIENT_CHUNK);
                send(new TunnelPayloads.Data(id, Arrays.copyOfRange(all, start, end)));
            }
        }
    };

    public TunnelTransport(UUID pcId) {
        this(pcId, 0);
    }

    public TunnelTransport(UUID pcId, int head) {
        this.pcId = pcId;
        this.head = head;
    }

    @Override
    public void connect() throws IOException {
        OPEN.put(id, this);
        if (!send(new TunnelPayloads.Open(id, pcId, head))) {
            close();
            throw new IOException("Not connected to a server with this mod");
        }
    }

    @Override
    public InputStream input() {
        return input;
    }

    @Override
    public OutputStream output() {
        return output;
    }

    @Override
    public boolean remote() {
        return true;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        OPEN.remove(id);
        incoming.add(END);
        send(new TunnelPayloads.Close(id));
    }

    private static boolean send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        try {
            if (ClientPlayNetworking.canSend(payload.type())) {
                ClientPlayNetworking.send(payload);
                return true;
            }
        } catch (IllegalStateException e) {
            // Left the world in the meantime.
        }
        return false;
    }

    // ---------------------------------------------------------------- called by the payload receivers

    public static void onData(TunnelPayloads.Data payload) {
        TunnelTransport tunnel = OPEN.get(payload.tunnelId());
        if (tunnel != null) {
            tunnel.incoming.add(payload.bytes());
        }
    }

    public static void onClose(TunnelPayloads.Close payload) {
        TunnelTransport tunnel = OPEN.remove(payload.tunnelId());
        if (tunnel != null) {
            tunnel.closed = true;
            tunnel.incoming.add(END);
        }
    }

    public static void closeAll() {
        for (TunnelTransport tunnel : OPEN.values()) {
            tunnel.closed = true;
            tunnel.incoming.add(END);
        }
        OPEN.clear();
    }
}
