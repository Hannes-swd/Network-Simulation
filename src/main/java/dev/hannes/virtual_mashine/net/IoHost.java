package dev.hannes.virtual_mashine.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The firmware of an I/O module: a small IPv4 host inside the fabric, like the one in a real
 * Ethernet I/O module. It answers ARP and ping, takes its address by DHCP (or uses a fixed one) and
 * serves Modbus TCP on port 502 and a web page with a JSON API on port 80. Any operating system,
 * PLC runtime or script can therefore read and drive the redstone channels over the network.
 * Runs on the fabric thread only.
 */
final class IoHost implements NetworkFabric.Host {
    private static final int MODBUS_PORT = 502;
    private static final int HTTP_PORT = 80;
    private static final int MAX_CONNECTIONS = 16;
    private static final int MSS = 1400;
    private static final int WINDOW = 16384;
    private static final int BROADCAST_IP = 0xFFFFFFFF;
    private static final byte[] BROADCAST_MAC = {-1, -1, -1, -1, -1, -1};

    private static final int FIN = 0x01;
    private static final int SYN = 0x02;
    private static final int RST = 0x04;
    private static final int PSH = 0x08;
    private static final int ACK = 0x10;

    private static final int DHCP_OFF = 0;
    private static final int DHCP_SELECTING = 1;
    private static final int DHCP_REQUESTING = 2;
    private static final int DHCP_BOUND = 3;

    private final IoModule io;
    private final byte[] mac;
    private final String hostname;
    private final Random random = new Random();
    private Consumer<byte[]> wire = frame -> {
    };
    private BooleanSupplier link = () -> false;

    private int ip;
    private int mask;
    private int gateway;
    private int ipId;
    private int appliedNetwork = -1;
    private boolean lastLink;

    private int dhcpState = DHCP_OFF;
    private int xid;
    private int offered;
    private int dhcpServer;
    private long dhcpTimer;
    private int dhcpTries;
    private long renewAt;
    private long leaseEnd;

    private final Map<Long, Connection> connections = new HashMap<>();
    /** Snapshot of {@code connections.size()} for other threads. */
    private volatile int openConnections;

    IoHost(IoModule io, String hostname) {
        this.io = io;
        this.mac = parseMac(io.mac());
        this.hostname = hostname;
    }

    IoModule io() {
        return io;
    }

    @Override
    public void attach(Consumer<byte[]> send, BooleanSupplier linkUp) {
        this.wire = send;
        this.link = linkUp;
    }

    // ---------------------------------------------------------------- timers

    @Override
    public void tick(long now) {
        boolean up = link.getAsBoolean();
        if (up != lastLink || io.networkVersion() != appliedNetwork) {
            lastLink = up;
            applyNetwork(now, up);
        }
        if (!up) {
            return;
        }
        if (io.dhcp()) {
            dhcpTick(now);
        }
        tcpTick(now);
        openConnections = connections.size();
    }

    /** Cable plugged or settings changed: start over, like a real device after a link change. */
    private void applyNetwork(long now, boolean up) {
        appliedNetwork = io.networkVersion();
        connections.clear();
        dhcpState = DHCP_OFF;
        if (!up) {
            setAddress(0, 0, 0);
            return;
        }
        if (io.dhcp()) {
            setAddress(0, 0, 0);
            startDhcp(now);
        } else {
            setAddress(io.staticIp(), io.staticMask(), io.staticGateway());
            if (ip != 0) {
                sendArp(1, BROADCAST_MAC, ip); // Gratuitous ARP: "this address is here now".
            }
        }
    }

    private void setAddress(int ip, int mask, int gateway) {
        if (ip != this.ip) {
            connections.clear();
        }
        this.ip = ip;
        this.mask = mask;
        this.gateway = gateway;
        io.setAddress(ip, mask, gateway);
    }

    // ---------------------------------------------------------------- frames in

    @Override
    public void receive(byte[] f) {
        if (f.length < 14 || !lastLink) {
            return;
        }
        boolean forUs = true;
        boolean broadcast = true;
        for (int i = 0; i < 6; i++) {
            forUs &= f[i] == mac[i];
            broadcast &= f[i] == (byte) 0xFF;
        }
        if (!forUs && !broadcast) {
            return;
        }
        byte[] srcMac = new byte[6];
        System.arraycopy(f, 6, srcMac, 0, 6);
        int type = u16(f, 12);
        if (type == 0x0806) {
            onArp(f);
        } else if (type == 0x0800) {
            onIpv4(f, srcMac);
        }
    }

    private void onArp(byte[] f) {
        if (f.length < 42 || u16(f, 14) != 1 || u16(f, 16) != 0x0800 || u16(f, 20) != 1 || ip == 0) {
            return;
        }
        if (s32(f, 38) == ip) {
            byte[] senderMac = new byte[6];
            System.arraycopy(f, 22, senderMac, 0, 6);
            sendArpReply(senderMac, s32(f, 28));
        }
    }

    private void onIpv4(byte[] f, byte[] srcMac) {
        int o = 14;
        if (f.length < o + 20 || (f[o] & 0xF0) != 0x40) {
            return;
        }
        int headerLength = (f[o] & 0x0F) * 4;
        int total = u16(f, o + 2);
        if (headerLength < 20 || total < headerLength || f.length < o + total || (u16(f, o + 6) & 0x3FFF) != 0) {
            return; // Malformed or fragmented: a small device ignores both.
        }
        int protocol = f[o + 9] & 0xFF;
        int src = s32(f, o + 12);
        int dst = s32(f, o + 16);
        int payload = o + headerLength;
        int length = total - headerLength;
        if (protocol == 17 && length >= 8 && u16(f, payload + 2) == 68) {
            onDhcp(f, payload + 8, length - 8);
            return;
        }
        if (ip == 0 || dst != ip) {
            return;
        }
        if (protocol == 1) {
            onIcmp(f, payload, length, src, srcMac);
        } else if (protocol == 6) {
            onTcp(f, payload, length, src, srcMac);
        }
    }

    private void onIcmp(byte[] f, int o, int length, int src, byte[] srcMac) {
        if (length < 8 || f[o] != 8) {
            return; // Only echo requests.
        }
        byte[] reply = new byte[length];
        System.arraycopy(f, o, reply, 0, length);
        reply[0] = 0;
        reply[2] = 0;
        reply[3] = 0;
        put16(reply, 2, checksum(reply, 0, length, 0));
        sendIp(srcMac, ip, src, 1, reply);
    }

    // ---------------------------------------------------------------- DHCP client

    private void startDhcp(long now) {
        dhcpState = DHCP_SELECTING;
        xid = random.nextInt();
        dhcpTries = 0;
        sendDhcp(1);
        dhcpTimer = now + 3000;
    }

    private void dhcpTick(long now) {
        if (dhcpState == DHCP_OFF) {
            startDhcp(now);
        } else if (dhcpState == DHCP_SELECTING && now >= dhcpTimer) {
            dhcpTries++;
            sendDhcp(1);
            dhcpTimer = now + Math.min(16000, 3000L << Math.min(dhcpTries, 3));
        } else if (dhcpState == DHCP_REQUESTING && now >= dhcpTimer) {
            if (++dhcpTries > 3) {
                if (ip != 0 && now < leaseEnd) {
                    dhcpState = DHCP_BOUND; // Renewal failed for now: keep the lease and try again later.
                    renewAt = now + 30_000;
                } else {
                    setAddress(0, 0, 0);
                    startDhcp(now);
                }
            } else {
                sendDhcp(3);
                dhcpTimer = now + 3000;
            }
        } else if (dhcpState == DHCP_BOUND) {
            if (now >= leaseEnd) {
                setAddress(0, 0, 0);
                startDhcp(now);
            } else if (now >= renewAt) {
                dhcpState = DHCP_REQUESTING;
                dhcpTries = 0;
                offered = ip;
                sendDhcp(3);
                dhcpTimer = now + 3000;
            }
        }
    }

    /** @param type 1 = discover, 3 = request */
    private void sendDhcp(int type) {
        byte[] b = new byte[300];
        b[0] = 1;
        b[1] = 1;
        b[2] = 6;
        put32(b, 4, xid);
        put16(b, 10, 0x8000); // Answer by broadcast: we have no address yet.
        System.arraycopy(mac, 0, b, 28, 6);
        put32(b, 236, 0x63825363);
        int o = 240;
        b[o++] = 53;
        b[o++] = 1;
        b[o++] = (byte) type;
        if (type == 3) {
            b[o++] = 50;
            b[o++] = 4;
            put32(b, o, offered);
            o += 4;
            if (dhcpServer != 0) {
                b[o++] = 54;
                b[o++] = 4;
                put32(b, o, dhcpServer);
                o += 4;
            }
        }
        byte[] name = hostname.getBytes(StandardCharsets.US_ASCII);
        b[o++] = 12;
        b[o++] = (byte) name.length;
        System.arraycopy(name, 0, b, o, name.length);
        o += name.length;
        b[o++] = 55;
        b[o++] = 3;
        b[o++] = 1;
        b[o++] = 3;
        b[o++] = 51;
        b[o] = (byte) 255;
        sendUdp(BROADCAST_MAC, 0, BROADCAST_IP, 68, 67, b);
    }

    private void onDhcp(byte[] f, int o, int length) {
        if (length < 240 || f[o] != 2 || s32(f, o + 4) != xid || s32(f, o + 236) != 0x63825363 || !io.dhcp()) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            if (f[o + 28 + i] != mac[i]) {
                return;
            }
        }
        int yiaddr = s32(f, o + 16);
        int type = 0;
        int optMask = 0;
        int optRouter = 0;
        int optServer = 0;
        long lease = 3600;
        int p = o + 240;
        int end = o + length;
        while (p < end) {
            int code = f[p] & 0xFF;
            if (code == 255) {
                break;
            }
            if (code == 0) {
                p++;
                continue;
            }
            if (p + 1 >= end) {
                break;
            }
            int len = f[p + 1] & 0xFF;
            int v = p + 2;
            if (v + len > end) {
                break;
            }
            switch (code) {
                case 53 -> type = len >= 1 ? f[v] & 0xFF : 0;
                case 1 -> optMask = len >= 4 ? s32(f, v) : 0;
                case 3 -> optRouter = len >= 4 ? s32(f, v) : 0;
                case 54 -> optServer = len >= 4 ? s32(f, v) : 0;
                case 51 -> lease = len >= 4 ? Integer.toUnsignedLong(s32(f, v)) : 3600;
                default -> {
                }
            }
            p = v + len;
        }
        long now = System.currentTimeMillis();
        if (type == 2 && dhcpState == DHCP_SELECTING) {
            offered = yiaddr;
            dhcpServer = optServer;
            dhcpState = DHCP_REQUESTING;
            dhcpTries = 0;
            sendDhcp(3);
            dhcpTimer = now + 3000;
        } else if (type == 5 && dhcpState == DHCP_REQUESTING) {
            lease = Math.max(60, Math.min(lease, 7 * 24 * 3600L));
            setAddress(yiaddr, optMask != 0 ? optMask : 0xFFFFFF00, optRouter);
            dhcpState = DHCP_BOUND;
            renewAt = now + lease * 500;
            leaseEnd = now + lease * 1000;
        } else if (type == 6) {
            setAddress(0, 0, 0);
            startDhcp(now);
        }
    }

    // ---------------------------------------------------------------- TCP (server side only)

    private static final int SYN_RECEIVED = 0;
    private static final int ESTABLISHED = 1;
    private static final int FIN_WAIT_1 = 2;
    private static final int FIN_WAIT_2 = 3;
    private static final int LAST_ACK = 4;

    private record Segment(int seq, int flags, byte[] data) {
        int length() {
            return data.length + ((flags & (SYN | FIN)) != 0 ? 1 : 0);
        }
    }

    private interface App {
        /** @return bytes to send back (may be empty) */
        byte[] onData(byte[] data, int offset, int length);

        boolean wantsClose();
    }

    private final class Connection {
        final int peer;
        final int peerPort;
        final int localPort;
        byte[] peerMac;
        final App app;
        int state = SYN_RECEIVED;
        int sndUna;
        int sndNxt;
        int rcvNxt;
        int peerMss = 536;
        final ArrayDeque<Segment> unacked = new ArrayDeque<>();
        long rto = 300;
        long rtoAt;
        int retries;
        long lastActivity;

        Connection(int peer, int peerPort, int localPort, byte[] peerMac, App app) {
            this.peer = peer;
            this.peerPort = peerPort;
            this.localPort = localPort;
            this.peerMac = peerMac;
            this.app = app;
        }
    }

    private static long key(int peer, int peerPort, int localPort) {
        return Integer.toUnsignedLong(peer) << 32 | (long) peerPort << 16 | localPort;
    }

    private void onTcp(byte[] f, int o, int length, int src, byte[] srcMac) {
        if (length < 20) {
            return;
        }
        int srcPort = u16(f, o);
        int dstPort = u16(f, o + 2);
        int seq = s32(f, o + 4);
        int ack = s32(f, o + 8);
        int dataOffset = (f[o + 12] >> 4 & 0xF) * 4;
        int flags = f[o + 13] & 0x3F;
        if (dataOffset < 20 || dataOffset > length) {
            return;
        }
        int data = o + dataOffset;
        int dataLength = length - dataOffset;
        long now = System.currentTimeMillis();
        long key = key(src, srcPort, dstPort);
        Connection c = connections.get(key);
        if ((flags & RST) != 0) {
            connections.remove(key);
            return;
        }
        if (c == null) {
            boolean listening = dstPort == MODBUS_PORT || dstPort == HTTP_PORT;
            if ((flags & SYN) != 0 && (flags & ACK) == 0 && listening && connections.size() < MAX_CONNECTIONS) {
                c = new Connection(src, srcPort, dstPort, srcMac, dstPort == MODBUS_PORT ? new Modbus() : new Http());
                c.peerMss = Math.min(MSS, peerMss(f, o + 20, o + dataOffset));
                int iss = random.nextInt();
                c.sndUna = iss;
                c.sndNxt = iss;
                c.rcvNxt = seq + 1;
                c.lastActivity = now;
                connections.put(key, c);
                queue(c, SYN | ACK, new byte[0], now);
            } else {
                // Closed port or stray segment: refuse it, as every TCP stack does.
                int rstSeq = (flags & ACK) != 0 ? ack : 0;
                int rstAck = seq + dataLength + ((flags & (SYN | FIN)) != 0 ? 1 : 0);
                sendSegment(srcMac, src, dstPort, srcPort, rstSeq, rstAck, (flags & ACK) != 0 ? RST : RST | ACK, null, 0, 0);
            }
            return;
        }
        c.peerMac = srcMac;
        c.lastActivity = now;
        if ((flags & SYN) != 0) {
            if (c.state == SYN_RECEIVED) {
                retransmit(c, now); // The peer did not get our SYN-ACK.
            }
            return;
        }
        if ((flags & ACK) != 0 && seqLt(c.sndUna, ack) && seqLe(ack, c.sndNxt)) {
            c.sndUna = ack;
            while (!c.unacked.isEmpty() && seqLe(c.unacked.peek().seq() + c.unacked.peek().length(), ack)) {
                c.unacked.poll();
            }
            c.retries = 0;
            c.rto = 300;
            c.rtoAt = now + c.rto;
            if (c.state == SYN_RECEIVED) {
                c.state = ESTABLISHED;
            }
            if (c.state == FIN_WAIT_1 && c.unacked.isEmpty()) {
                c.state = FIN_WAIT_2;
            }
            if (c.state == LAST_ACK && c.unacked.isEmpty()) {
                connections.remove(key);
                return;
            }
        }
        if (c.state == SYN_RECEIVED) {
            return;
        }
        if (dataLength > 0) {
            if (seq != c.rcvNxt) {
                sendAck(c); // Duplicate or out of order: tell the peer where we are.
                return;
            }
            c.rcvNxt += dataLength;
            if (c.state == ESTABLISHED) {
                byte[] reply = c.app.onData(f, data, dataLength);
                if (reply.length > 0) {
                    send(c, reply, now);
                } else {
                    sendAck(c);
                }
                if (c.app.wantsClose()) {
                    close(c, now, false);
                }
            } else {
                sendAck(c);
            }
        }
        if ((flags & FIN) != 0 && seq + dataLength == c.rcvNxt) {
            c.rcvNxt += 1;
            switch (c.state) {
                case ESTABLISHED -> close(c, now, true); // The FIN segment acknowledges theirs.

                case FIN_WAIT_1, FIN_WAIT_2 -> {
                    sendAck(c);
                    connections.remove(key);
                }
                default -> sendAck(c);
            }
        }
    }

    private static int peerMss(byte[] f, int from, int to) {
        int p = from;
        while (p < to) {
            int kind = f[p] & 0xFF;
            if (kind == 0) {
                break;
            }
            if (kind == 1) {
                p++;
                continue;
            }
            if (p + 1 >= to) {
                break;
            }
            int len = f[p + 1] & 0xFF;
            if (len < 2) {
                break;
            }
            if (kind == 2 && len == 4 && p + 4 <= to) {
                return Math.max(64, u16(f, p + 2));
            }
            p += len;
        }
        return 536;
    }

    private void send(Connection c, byte[] bytes, long now) {
        for (int off = 0; off < bytes.length; off += c.peerMss) {
            int len = Math.min(c.peerMss, bytes.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(bytes, off, chunk, 0, len);
            queue(c, PSH | ACK, chunk, now);
        }
    }

    /** @param peerClosed the peer sent its FIN already, so ours is the last segment */
    private void close(Connection c, long now, boolean peerClosed) {
        if (c.state != ESTABLISHED) {
            return;
        }
        c.state = peerClosed ? LAST_ACK : FIN_WAIT_1;
        queue(c, FIN | ACK, new byte[0], now);
    }

    private void queue(Connection c, int flags, byte[] data, long now) {
        Segment segment = new Segment(c.sndNxt, flags, data);
        c.sndNxt += segment.length();
        if (c.unacked.isEmpty()) {
            c.rtoAt = now + c.rto;
        }
        c.unacked.add(segment);
        transmit(c, segment);
    }

    private void transmit(Connection c, Segment segment) {
        sendSegment(c.peerMac, c.peer, c.localPort, c.peerPort, segment.seq(), c.rcvNxt, segment.flags(),
                segment.data(), 0, segment.data().length);
    }

    private void sendAck(Connection c) {
        sendSegment(c.peerMac, c.peer, c.localPort, c.peerPort, c.sndNxt, c.rcvNxt, ACK, null, 0, 0);
    }

    private void retransmit(Connection c, long now) {
        for (Segment segment : c.unacked) {
            transmit(c, segment);
        }
        c.rtoAt = now + c.rto;
    }

    private void tcpTick(long now) {
        Iterator<Connection> it = connections.values().iterator();
        while (it.hasNext()) {
            Connection c = it.next();
            if (!c.unacked.isEmpty() && now >= c.rtoAt) {
                if (++c.retries > 8) {
                    it.remove();
                    continue;
                }
                c.rto = Math.min(c.rto * 2, 4000);
                retransmit(c, now);
            }
            long idle = now - c.lastActivity;
            if (c.state == FIN_WAIT_2 && idle > 15_000 || idle > 600_000) {
                it.remove();
            }
        }
    }

    // ---------------------------------------------------------------- Modbus TCP

    /**
     * Modbus TCP server. Addresses are channel numbers 0..5 (front, back, left, right, top, bottom):
     * coils = output on/off, discrete inputs = signal present, input registers = level on the face
     * (0..15), holding registers 0..5 = output level, holding registers 100..105 = mode (0 in, 1 out).
     */
    private final class Modbus implements App {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public byte[] onData(byte[] data, int offset, int length) {
            buffer.write(data, offset, length);
            byte[] all = buffer.toByteArray();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int p = 0;
            while (all.length - p >= 8) {
                int len = u16(all, p + 4);
                if (len < 2 || len > 260) {
                    buffer.reset();
                    return out.toByteArray(); // Garbage: drop it, the client will notice.
                }
                if (all.length - p < 6 + len) {
                    break;
                }
                byte[] pdu = new byte[len - 1];
                System.arraycopy(all, p + 7, pdu, 0, len - 1);
                byte[] response = handle(pdu);
                out.write(all[p]);
                out.write(all[p + 1]);
                out.write(0);
                out.write(0);
                out.write(response.length + 1 >> 8);
                out.write(response.length + 1);
                out.write(all[p + 6]);
                out.write(response, 0, response.length);
                p += 6 + len;
            }
            buffer.reset();
            buffer.write(all, p, all.length - p);
            return out.toByteArray();
        }

        @Override
        public boolean wantsClose() {
            return false;
        }

        private byte[] handle(byte[] pdu) {
            int function = pdu[0] & 0xFF;
            try {
                return switch (function) {
                    case 1, 2 -> readBits(function, u16(pdu, 1), u16(pdu, 3));
                    case 3, 4 -> readRegisters(function, u16(pdu, 1), u16(pdu, 3));
                    case 5 -> writeCoil(u16(pdu, 1), u16(pdu, 3), pdu);
                    case 6 -> writeRegister(u16(pdu, 1), u16(pdu, 3), pdu);
                    case 15 -> writeCoils(u16(pdu, 1), u16(pdu, 3), pdu);
                    case 16 -> writeRegisters(u16(pdu, 1), u16(pdu, 3), pdu);
                    default -> error(function, 1);
                };
            } catch (ArrayIndexOutOfBoundsException e) {
                return error(function, 3);
            }
        }

        private byte[] readBits(int function, int start, int count) {
            if (count < 1 || count > 2000) {
                return error(function, 3);
            }
            if (start + count > IoModule.CHANNELS) {
                return error(function, 2);
            }
            byte[] r = new byte[2 + (count + 7) / 8];
            r[0] = (byte) function;
            r[1] = (byte) ((count + 7) / 8);
            for (int i = 0; i < count; i++) {
                int ch = start + i;
                boolean on = function == 1 ? io.output(ch) > 0 : io.level(ch) > 0;
                if (on) {
                    r[2 + i / 8] |= (byte) (1 << (i % 8));
                }
            }
            return r;
        }

        private byte[] readRegisters(int function, int start, int count) {
            if (count < 1 || count > 125) {
                return error(function, 3);
            }
            boolean modes = function == 3 && start >= 100;
            int first = modes ? start - 100 : start;
            if (first < 0 || first + count > IoModule.CHANNELS) {
                return error(function, 2);
            }
            byte[] r = new byte[2 + count * 2];
            r[0] = (byte) function;
            r[1] = (byte) (count * 2);
            for (int i = 0; i < count; i++) {
                int ch = first + i;
                int value = modes ? (io.isOutput(ch) ? 1 : 0) : function == 3 ? io.output(ch) : io.level(ch);
                put16(r, 2 + i * 2, value);
            }
            return r;
        }

        private byte[] writeCoil(int address, int value, byte[] pdu) {
            if (address >= IoModule.CHANNELS) {
                return error(5, 2);
            }
            if (value != 0xFF00 && value != 0) {
                return error(5, 3);
            }
            if (!io.setOutput(address, value == 0xFF00 ? 15 : 0)) {
                return error(5, 4); // The channel is an input.
            }
            return echo(pdu, 5);
        }

        private byte[] writeRegister(int address, int value, byte[] pdu) {
            int problem = writeOne(address, value);
            return problem != 0 ? error(6, problem) : echo(pdu, 5);
        }

        private byte[] writeCoils(int start, int count, byte[] pdu) {
            if (count < 1 || start + count > IoModule.CHANNELS) {
                return error(15, 2);
            }
            for (int i = 0; i < count; i++) {
                if (!io.isOutput(start + i)) {
                    return error(15, 4);
                }
            }
            for (int i = 0; i < count; i++) {
                boolean on = (pdu[6 + i / 8] >> (i % 8) & 1) != 0;
                io.setOutput(start + i, on ? 15 : 0);
            }
            return echo(pdu, 5);
        }

        private byte[] writeRegisters(int start, int count, byte[] pdu) {
            if (count < 1 || count > 123) {
                return error(16, 3);
            }
            for (int i = 0; i < count; i++) {
                int problem = writeOne(start + i, u16(pdu, 6 + i * 2));
                if (problem != 0) {
                    return error(16, problem);
                }
            }
            return echo(pdu, 5);
        }

        /** @return 0, or the Modbus exception code: 2 no such register, 3 bad value, 4 channel is an input */
        private int writeOne(int address, int value) {
            if (address >= 100 && address < 100 + IoModule.CHANNELS) {
                if (value > 1) {
                    return 3;
                }
                io.setMode(address - 100, value == 1);
                return 0;
            }
            if (address >= IoModule.CHANNELS) {
                return 2;
            }
            if (value > 15) {
                return 3;
            }
            return io.setOutput(address, value) ? 0 : 4;
        }

        private static byte[] echo(byte[] pdu, int length) {
            byte[] r = new byte[length];
            System.arraycopy(pdu, 0, r, 0, length);
            return r;
        }

        private static byte[] error(int function, int code) {
            return new byte[]{(byte) (function | 0x80), (byte) code};
        }
    }

    // ---------------------------------------------------------------- web page and JSON API

    private final class Http implements App {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean done;

        @Override
        public byte[] onData(byte[] data, int offset, int length) {
            if (done) {
                return new byte[0];
            }
            buffer.write(data, offset, length);
            String text = buffer.toString(StandardCharsets.ISO_8859_1);
            if (!text.contains("\r\n\r\n") && !text.contains("\n\n")) {
                if (buffer.size() > 8192) {
                    done = true;
                    return response(400, "text/plain", "Request too large\n", null);
                }
                return new byte[0];
            }
            done = true;
            String[] request = text.split("\r?\n", 2)[0].split(" ");
            if (request.length < 2) {
                return response(400, "text/plain", "Bad request\n", null);
            }
            return route(request[0], request[1]);
        }

        @Override
        public boolean wantsClose() {
            return done;
        }

        private byte[] route(String method, String target) {
            if (!method.equals("GET") && !method.equals("POST")) {
                return response(405, "text/plain", "Use GET\n", null);
            }
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            Map<String, String> query = parseQuery(q < 0 ? "" : target.substring(q + 1));
            boolean ui = query.containsKey("ui");
            switch (path) {
                case "/", "/index.html" -> {
                    return response(200, "text/html; charset=utf-8", page(), null);
                }
                case "/api", "/api/status" -> {
                    return response(200, "application/json", json(), null);
                }
                case "/set", "/api/set" -> {
                    int ch = channel(query);
                    Integer level = level(query.get("level"));
                    if (ch < 0 || level == null) {
                        return error("Need ch (0-5 or front/back/left/right/top/bottom) and level (0-15 or on/off)", ui);
                    }
                    if (!io.setOutput(ch, level)) {
                        return error("Channel " + IoModule.SIDES[ch] + " is an input; switch it to output first (/mode?ch=" + ch + "&mode=output)", ui);
                    }
                    return ok(ui);
                }
                case "/mode", "/api/mode" -> {
                    int ch = channel(query);
                    String mode = query.getOrDefault("mode", "");
                    if (ch < 0 || !(mode.equals("input") || mode.equals("output"))) {
                        return error("Need ch and mode=input or mode=output", ui);
                    }
                    io.setMode(ch, mode.equals("output"));
                    return ok(ui);
                }
                default -> {
                    return response(404, "text/plain", "Not found. Try / or /api\n", null);
                }
            }
        }

        private byte[] ok(boolean ui) {
            return ui ? response(303, "text/plain", "", "/") : response(200, "application/json", json(), null);
        }

        private byte[] error(String message, boolean ui) {
            if (ui) {
                return response(400, "text/html; charset=utf-8", "<p>" + escape(message) + "</p><p><a href=\"/\">Zur&uuml;ck</a></p>", null);
            }
            return response(400, "application/json", "{\"error\":\"" + message.replace("\"", "'") + "\"}\n", null);
        }

        private int channel(Map<String, String> query) {
            String value = query.getOrDefault("ch", query.getOrDefault("side", ""));
            for (int i = 0; i < IoModule.CHANNELS; i++) {
                if (IoModule.SIDES[i].equals(value) || Integer.toString(i).equals(value)) {
                    return i;
                }
            }
            return -1;
        }

        private Integer level(String value) {
            if (value == null) {
                return null;
            }
            switch (value.toLowerCase(Locale.ROOT)) {
                case "on", "true" -> {
                    return 15;
                }
                case "off", "false" -> {
                    return 0;
                }
                default -> {
                    try {
                        int level = Integer.parseInt(value);
                        return level >= 0 && level <= 15 ? level : null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
        }

        private String json() {
            StringBuilder s = new StringBuilder();
            s.append("{\"name\":\"").append(escapeJson(name())).append("\",\"mac\":\"").append(io.mac())
                    .append("\",\"ip\":\"").append(IoModule.formatIp(ip)).append("\",\"dhcp\":").append(io.dhcp())
                    .append(",\"channels\":[");
            for (int ch = 0; ch < IoModule.CHANNELS; ch++) {
                if (ch > 0) {
                    s.append(',');
                }
                s.append("{\"ch\":").append(ch).append(",\"side\":\"").append(IoModule.SIDES[ch])
                        .append("\",\"mode\":\"").append(io.isOutput(ch) ? "output" : "input")
                        .append("\",\"level\":").append(io.level(ch)).append('}');
            }
            return s.append("]}\n").toString();
        }

        private String page() {
            String[] sides = {"vorne", "hinten", "links", "rechts", "oben", "unten"};
            StringBuilder s = new StringBuilder();
            s.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"2\">")
                    .append("<meta name=\"viewport\" content=\"width=device-width\"><title>").append(escape(name()))
                    .append("</title><style>body{font-family:sans-serif;margin:16px;background:#1e2227;color:#e8e8e8}")
                    .append("td,th{padding:6px 10px;text-align:left}a{color:#7cc4ff;margin-right:6px}")
                    .append(".bar{display:inline-block;height:10px;background:#d33;vertical-align:middle}</style></head><body>")
                    .append("<h2>").append(escape(name())).append("</h2><p>IP ").append(IoModule.formatIp(ip))
                    .append(io.dhcp() ? " (DHCP)" : " (fest)").append(" &middot; MAC ").append(io.mac())
                    .append("<br>Modbus TCP: Port 502 &middot; JSON: <a href=\"/api\">/api</a></p><table><tr><th>Kanal</th>")
                    .append("<th>Seite</th><th>Modus</th><th>Pegel</th><th></th></tr>");
            for (int ch = 0; ch < IoModule.CHANNELS; ch++) {
                boolean out = io.isOutput(ch);
                int level = io.level(ch);
                s.append("<tr><td>").append(ch).append("</td><td>").append(sides[ch]).append("</td><td>")
                        .append(out ? "Ausgang" : "Eingang").append(" <a href=\"/mode?ui=1&ch=").append(ch)
                        .append(out ? "&mode=input\">&rarr;Eingang" : "&mode=output\">&rarr;Ausgang").append("</a></td><td>")
                        .append(level).append(" <span class=\"bar\" style=\"width:").append(level * 6).append("px\"></span></td><td>");
                if (out) {
                    for (int value : new int[]{0, 1, 7, 15}) {
                        s.append("<a href=\"/set?ui=1&ch=").append(ch).append("&level=").append(value).append("\">")
                                .append(value == 0 ? "Aus" : value == 15 ? "An" : Integer.toString(value)).append("</a>");
                    }
                }
                s.append("</td></tr>");
            }
            return s.append("</table></body></html>\n").toString();
        }

        private String name() {
            return io.name().isEmpty() ? hostname : io.name();
        }

        private byte[] response(int status, String type, String body, String location) {
            String reason = switch (status) {
                case 200 -> "OK";
                case 303 -> "See Other";
                case 400 -> "Bad Request";
                case 404 -> "Not Found";
                default -> "Method Not Allowed";
            };
            byte[] content = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.0 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + content.length
                    + "\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n"
                    + (location != null ? "Location: " + location + "\r\n" : "") + "Server: virtual_mashine-io\r\n\r\n";
            byte[] header = head.getBytes(StandardCharsets.US_ASCII);
            byte[] all = new byte[header.length + content.length];
            System.arraycopy(header, 0, all, 0, header.length);
            System.arraycopy(content, 0, all, header.length, content.length);
            return all;
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (!pair.isEmpty()) {
                values.put(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
            }
        }
        return values;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------------------------------------------------------- frames out

    private void sendArpReply(byte[] targetMac, int targetIp) {
        byte[] f = ethernet(targetMac, 0x0806, 28);
        put16(f, 14, 1);
        put16(f, 16, 0x0800);
        f[18] = 6;
        f[19] = 4;
        put16(f, 20, 2);
        System.arraycopy(mac, 0, f, 22, 6);
        put32(f, 28, ip);
        System.arraycopy(targetMac, 0, f, 32, 6);
        put32(f, 38, targetIp);
        wire.accept(f);
    }

    /** ARP request; with {@code target == ip} it is a gratuitous announcement. */
    private void sendArp(int op, byte[] dstMac, int target) {
        byte[] f = ethernet(dstMac, 0x0806, 28);
        put16(f, 14, 1);
        put16(f, 16, 0x0800);
        f[18] = 6;
        f[19] = 4;
        put16(f, 20, op);
        System.arraycopy(mac, 0, f, 22, 6);
        put32(f, 28, ip);
        put32(f, 38, target);
        wire.accept(f);
    }

    private void sendUdp(byte[] dstMac, int src, int dst, int srcPort, int dstPort, byte[] data) {
        byte[] udp = new byte[8 + data.length];
        put16(udp, 0, srcPort);
        put16(udp, 2, dstPort);
        put16(udp, 4, udp.length);
        System.arraycopy(data, 0, udp, 8, data.length);
        int sum = pseudoHeaderSum(src, dst, 17, udp.length);
        int checksum = checksum(udp, 0, udp.length, sum);
        put16(udp, 6, checksum == 0 ? 0xFFFF : checksum);
        sendIp(dstMac, src, dst, 17, udp);
    }

    private void sendSegment(byte[] dstMac, int dst, int srcPort, int dstPort, int seq, int ack, int flags,
                             byte[] data, int off, int len) {
        boolean syn = (flags & SYN) != 0;
        int header = syn ? 24 : 20;
        byte[] t = new byte[header + len];
        put16(t, 0, srcPort);
        put16(t, 2, dstPort);
        put32(t, 4, seq);
        put32(t, 8, (flags & ACK) != 0 ? ack : 0);
        t[12] = (byte) (header / 4 << 4);
        t[13] = (byte) flags;
        put16(t, 14, WINDOW);
        if (syn) {
            t[20] = 2;
            t[21] = 4;
            put16(t, 22, MSS);
        }
        if (len > 0) {
            System.arraycopy(data, off, t, header, len);
        }
        put16(t, 16, checksum(t, 0, t.length, pseudoHeaderSum(ip, dst, 6, t.length)));
        sendIp(dstMac, ip, dst, 6, t);
    }

    private void sendIp(byte[] dstMac, int src, int dst, int protocol, byte[] payload) {
        byte[] f = ethernet(dstMac, 0x0800, 20 + payload.length);
        int o = 14;
        f[o] = 0x45;
        put16(f, o + 2, 20 + payload.length);
        put16(f, o + 4, ipId++ & 0xFFFF);
        put16(f, o + 6, 0x4000);
        f[o + 8] = 64;
        f[o + 9] = (byte) protocol;
        put32(f, o + 12, src);
        put32(f, o + 16, dst);
        put16(f, o + 10, checksum(f, o, 20, 0));
        System.arraycopy(payload, 0, f, o + 20, payload.length);
        wire.accept(f);
    }

    /** Ethernet frame with room for {@code length} payload bytes, padded to the 60-byte minimum. */
    private byte[] ethernet(byte[] dstMac, int type, int length) {
        byte[] f = new byte[Math.max(60, 14 + length)];
        System.arraycopy(dstMac, 0, f, 0, 6);
        System.arraycopy(mac, 0, f, 6, 6);
        put16(f, 12, type);
        return f;
    }

    // ---------------------------------------------------------------- helpers

    private static int pseudoHeaderSum(int src, int dst, int protocol, int length) {
        return (src >>> 16) + (src & 0xFFFF) + (dst >>> 16) + (dst & 0xFFFF) + protocol + length;
    }

    private static int checksum(byte[] b, int off, int len, int initial) {
        long sum = initial & 0xFFFFFFFFL;
        int i = off;
        for (; i + 1 < off + len; i += 2) {
            sum += (b[i] & 0xFF) << 8 | b[i + 1] & 0xFF;
        }
        if (i < off + len) {
            sum += (b[i] & 0xFF) << 8;
        }
        while (sum >> 16 != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);
    }

    private static boolean seqLt(int a, int b) {
        return a - b < 0;
    }

    private static boolean seqLe(int a, int b) {
        return a - b <= 0;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | b[off + 1] & 0xFF;
    }

    private static int s32(byte[] b, int off) {
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16 | (b[off + 2] & 0xFF) << 8 | b[off + 3] & 0xFF;
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static byte[] parseMac(String text) {
        String[] parts = text.split(":");
        byte[] mac = new byte[6];
        for (int i = 0; i < 6; i++) {
            mac[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return mac;
    }

    /** Open TCP connections (Modbus clients, browsers), for the module's panel. */
    int connectionCount() {
        return openConnections;
    }
}
