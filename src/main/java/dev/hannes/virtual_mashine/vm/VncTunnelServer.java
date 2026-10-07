package dev.hannes.virtual_mashine.vm;

import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.network.TunnelPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server end of the screen tunnels: for every remote viewer it opens a local connection to QEMU's
 * VNC server and relays the bytes through Minecraft payloads. VNC only sends a frame after the
 * viewer asked for it, so a slow connection simply gets fewer frames instead of a growing backlog.
 */
final class VncTunnelServer {
    private record Key(UUID player, int tunnelId) {
    }

    private final Map<Key, Tunnel> tunnels = new ConcurrentHashMap<>();

    void open(ServerPlayer player, int tunnelId, int vncPort) {
        Key key = new Key(player.getUUID(), tunnelId);
        Tunnel previous = tunnels.remove(key);
        if (previous != null) {
            previous.close(false);
        }
        if (vncPort <= 0) {
            ServerPlayNetworking.send(player, new TunnelPayloads.Close(tunnelId));
            return;
        }
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress("127.0.0.1", vncPort), 2000);
        } catch (IOException e) {
            Virtual_mashine.LOGGER.debug("Tunnel {} could not reach VNC port {}", tunnelId, vncPort, e);
            try {
                socket.close();
            } catch (IOException ignored) {
                // Never opened.
            }
            ServerPlayNetworking.send(player, new TunnelPayloads.Close(tunnelId));
            return;
        }
        Tunnel tunnel = new Tunnel(key, player, socket);
        tunnels.put(key, tunnel);
        tunnel.start();
    }

    void data(ServerPlayer player, int tunnelId, byte[] bytes) {
        Tunnel tunnel = tunnels.get(new Key(player.getUUID(), tunnelId));
        if (tunnel != null) {
            tunnel.write(bytes);
        }
    }

    void close(ServerPlayer player, int tunnelId) {
        Tunnel tunnel = tunnels.remove(new Key(player.getUUID(), tunnelId));
        if (tunnel != null) {
            tunnel.close(false);
        }
    }

    void closeAllOf(UUID player) {
        tunnels.values().removeIf(tunnel -> {
            if (tunnel.key.player().equals(player)) {
                tunnel.close(false);
                return true;
            }
            return false;
        });
    }

    void closeAll() {
        for (Tunnel tunnel : tunnels.values()) {
            tunnel.close(false);
        }
        tunnels.clear();
    }

    private final class Tunnel {
        private final Key key;
        private final ServerPlayer player;
        private final Socket socket;
        private volatile boolean closed;

        Tunnel(Key key, ServerPlayer player, Socket socket) {
            this.key = key;
            this.player = player;
            this.socket = socket;
        }

        void start() {
            Thread thread = new Thread(this::pump, "virtual_mashine-tunnel-" + key.tunnelId());
            thread.setDaemon(true);
            thread.start();
        }

        /** QEMU to player. */
        private void pump() {
            byte[] buffer = new byte[TunnelPayloads.MAX_SERVER_CHUNK];
            try (InputStream in = socket.getInputStream()) {
                int read;
                while (!closed && (read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        ServerPlayNetworking.send(player, new TunnelPayloads.Data(key.tunnelId(), Arrays.copyOf(buffer, read)));
                    }
                }
            } catch (IOException e) {
                // Connection ended (PC switched off, tunnel closed).
            } finally {
                if (tunnels.remove(key, this)) {
                    close(true);
                }
            }
        }

        /** Player to QEMU. Called on the server thread with small input/request messages. */
        void write(byte[] bytes) {
            try {
                OutputStream out = socket.getOutputStream();
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                close(true);
                tunnels.remove(key, this);
            }
        }

        void close(boolean notifyPlayer) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
            if (notifyPlayer && !player.hasDisconnected()) {
                ServerPlayNetworking.send(player, new TunnelPayloads.Close(key.tunnelId()));
            }
        }
    }
}
