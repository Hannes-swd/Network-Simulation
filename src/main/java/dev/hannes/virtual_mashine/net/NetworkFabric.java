package dev.hannes.virtual_mashine.net;

import dev.hannes.virtual_mashine.Virtual_mashine;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The physical layer: every network card of every QEMU process sends its Ethernet frames as UDP
 * datagrams to a socket of this class, which hands them to whatever is on the other end of the
 * cable plugged into that port. No cable, or the other device switched off, means the frame is
 * lost, exactly as on a real wire.
 */
public final class NetworkFabric {
    /** Frames are not allowed to cross more switches than this (protects against switching loops). */
    private static final int MAX_HOPS = 16;

    /** Socket pair of one QEMU network card: QEMU listens on {@code qemuPort}, the mod on {@code modPort}. */
    public record UdpBinding(int qemuPort, int modPort) {
    }

    public static final class PortStats {
        public final AtomicLong rxFrames = new AtomicLong();
        public final AtomicLong txFrames = new AtomicLong();
        public final AtomicLong rxBytes = new AtomicLong();
        public final AtomicLong txBytes = new AtomicLong();
        public volatile long lastActivity;
    }

    private final class UdpEndpoint {
        final PortRef ref;
        final DatagramChannel channel;
        final InetSocketAddress qemu;

        UdpEndpoint(PortRef ref, DatagramChannel channel, InetSocketAddress qemu) {
            this.ref = ref;
            this.channel = channel;
            this.qemu = qemu;
        }

        void send(byte[] frame) {
            try {
                channel.send(ByteBuffer.wrap(frame), qemu);
            } catch (IOException e) {
                // A full socket buffer drops the frame, like a congested link.
            }
        }
    }

    private final Selector selector;
    private final Thread thread;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final Map<PortRef, PortRef> links = new ConcurrentHashMap<>();
    private final Map<PortRef, UdpEndpoint> udp = new ConcurrentHashMap<>();
    private final Map<UUID, LearningSwitch> switches = new ConcurrentHashMap<>();
    private final Map<PortRef, PortStats> stats = new ConcurrentHashMap<>();
    /** Line rate of each direction of a cable, keyed by the sending port. Fabric thread only after creation. */
    private final Map<PortRef, TokenBucket> limits = new ConcurrentHashMap<>();

    /** Polices a cable direction to its line rate; excess frames are dropped like on a saturated link. */
    private static final class TokenBucket {
        private final double bytesPerNano;
        private final double capacity;
        private double tokens;
        private long last = System.nanoTime();

        TokenBucket(int mbit) {
            bytesPerNano = mbit * 1_000_000.0 / 8 / 1_000_000_000.0;
            capacity = Math.max(256 * 1024, bytesPerNano * 20_000_000); // 20 ms burst
            tokens = capacity;
        }

        boolean take(int bytes) {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - last) * bytesPerNano);
            last = now;
            if (tokens < bytes) {
                return false;
            }
            tokens -= bytes;
            return true;
        }
    }
    private volatile boolean running = true;

    public NetworkFabric() throws IOException {
        selector = Selector.open();
        thread = new Thread(this::loop, "virtual_mashine-network");
        thread.setDaemon(true);
        thread.start();
    }

    // ---------------------------------------------------------------- devices

    /** Opens the mod side for {@code count} network cards of a QEMU process. */
    public UdpBinding[] openUdpPorts(UUID device, int count) throws IOException {
        UdpBinding[] bindings = new UdpBinding[count];
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        for (int port = 0; port < count; port++) {
            PortRef ref = new PortRef(device, port);
            closeUdp(ref);
            DatagramChannel channel = DatagramChannel.open();
            channel.bind(new InetSocketAddress(loopback, 0));
            channel.configureBlocking(false);
            int qemuPort = freeUdpPort(loopback);
            UdpEndpoint endpoint = new UdpEndpoint(ref, channel, new InetSocketAddress(loopback, qemuPort));
            udp.put(ref, endpoint);
            runOnFabric(() -> {
                try {
                    channel.register(selector, SelectionKey.OP_READ, endpoint);
                } catch (IOException e) {
                    Virtual_mashine.LOGGER.warn("Could not register network port {}", ref, e);
                }
            });
            bindings[port] = new UdpBinding(qemuPort, ((InetSocketAddress) channel.getLocalAddress()).getPort());
        }
        return bindings;
    }

    public void closeUdpPorts(UUID device) {
        for (PortRef ref : udp.keySet()) {
            if (ref.device().equals(device)) {
                closeUdp(ref);
            }
        }
    }

    public void addSwitch(UUID device, int ports) {
        switches.computeIfAbsent(device, id -> new LearningSwitch(ports));
    }

    public boolean hasSwitch(UUID device) {
        return switches.containsKey(device);
    }

    public void removeSwitch(UUID device) {
        switches.remove(device);
    }

    // ---------------------------------------------------------------- cables

    /** @param mbit line rate of the cable, 0 = unlimited */
    public void connect(PortRef a, PortRef b, int mbit) {
        links.put(a, b);
        links.put(b, a);
        if (mbit > 0) {
            limits.put(a, new TokenBucket(mbit));
            limits.put(b, new TokenBucket(mbit));
        }
    }

    public void disconnect(PortRef a) {
        PortRef b = links.remove(a);
        limits.remove(a);
        if (b != null) {
            links.remove(b);
            limits.remove(b);
            forgetOnSwitch(b);
        }
        forgetOnSwitch(a);
    }

    public @Nullable PortRef peer(PortRef port) {
        return links.get(port);
    }

    /** The device behind this port has power and a network card listening. */
    public boolean isPowered(PortRef port) {
        return udp.containsKey(port) || switches.containsKey(port.device());
    }

    /** Link LED: cable plugged in and both ends powered. */
    public boolean hasLink(PortRef port) {
        PortRef peer = links.get(port);
        return peer != null && isPowered(port) && isPowered(peer);
    }

    public PortStats stats(PortRef port) {
        return stats.computeIfAbsent(port, key -> new PortStats());
    }

    public void close() {
        running = false;
        selector.wakeup();
        for (PortRef ref : udp.keySet()) {
            closeUdp(ref);
        }
        try {
            selector.close();
        } catch (IOException ignored) {
            // Shutting down.
        }
    }

    // ---------------------------------------------------------------- fabric thread

    private void loop() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(65536);
        while (running) {
            try {
                selector.select(200);
                Runnable task;
                while ((task = tasks.poll()) != null) {
                    task.run();
                }
                for (SelectionKey key : selector.selectedKeys()) {
                    if (!key.isValid() || !(key.attachment() instanceof UdpEndpoint endpoint)) {
                        continue;
                    }
                    while (true) {
                        buffer.clear();
                        if (endpoint.channel.receive(buffer) == null) {
                            break;
                        }
                        buffer.flip();
                        byte[] frame = new byte[buffer.remaining()];
                        buffer.get(frame);
                        transmit(endpoint.ref, frame, 0);
                    }
                }
                selector.selectedKeys().clear();
            } catch (ClosedSelectorException e) {
                return;
            } catch (IOException | RuntimeException e) {
                if (running) {
                    Virtual_mashine.LOGGER.debug("Network fabric hiccup", e);
                }
            }
        }
    }

    /** {@code from} puts a frame on its cable. */
    private void transmit(PortRef from, byte[] frame, int hops) {
        PortStats out = stats(from);
        out.txFrames.incrementAndGet();
        out.txBytes.addAndGet(frame.length);
        out.lastActivity = System.nanoTime();
        PortRef to = links.get(from);
        if (to == null) {
            return;
        }
        TokenBucket limit = limits.get(from);
        if (limit != null && !limit.take(frame.length)) {
            return;
        }
        PortStats in = stats(to);
        in.rxFrames.incrementAndGet();
        in.rxBytes.addAndGet(frame.length);
        in.lastActivity = out.lastActivity;

        UdpEndpoint endpoint = udp.get(to);
        if (endpoint != null) {
            endpoint.send(frame);
            return;
        }
        LearningSwitch sw = switches.get(to.device());
        if (sw != null && hops < MAX_HOPS) {
            for (int port : sw.forward(to.port(), frame)) {
                transmit(new PortRef(to.device(), port), frame, hops + 1);
            }
        }
    }

    private void forgetOnSwitch(PortRef port) {
        LearningSwitch sw = switches.get(port.device());
        if (sw != null) {
            runOnFabric(() -> sw.forgetPort(port.port()));
        }
    }

    private void runOnFabric(Runnable task) {
        tasks.add(task);
        selector.wakeup();
    }

    private void closeUdp(PortRef ref) {
        UdpEndpoint endpoint = udp.remove(ref);
        if (endpoint != null) {
            try {
                endpoint.channel.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private static int freeUdpPort(InetAddress loopback) throws IOException {
        try (DatagramSocket probe = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            return probe.getLocalPort();
        }
    }
}
