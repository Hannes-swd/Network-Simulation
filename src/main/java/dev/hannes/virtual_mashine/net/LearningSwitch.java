package dev.hannes.virtual_mashine.net;

import java.util.HashMap;
import java.util.Map;

/**
 * An unmanaged Ethernet switch: learns which MAC address sits behind which port and floods
 * broadcasts and unknown destinations, exactly like a cheap desktop switch. Only used on the
 * fabric thread.
 */
final class LearningSwitch {
    private static final long AGING_NANOS = 300L * 1_000_000_000L;
    private static final int[] NONE = new int[0];

    private record Entry(int port, long seen) {
    }

    private final int ports;
    private final Map<Long, Entry> table = new HashMap<>();

    LearningSwitch(int ports) {
        this.ports = ports;
    }

    /** Ports the frame that arrived on {@code inPort} has to leave through. */
    int[] forward(int inPort, byte[] frame) {
        if (frame.length < 14) {
            return NONE;
        }
        long now = System.nanoTime();
        long destination = mac(frame, 0);
        if ((destination & 0xFFFFFFFFFFF0L) == 0x0180C2000000L) {
            // 01:80:C2:00:00:0x (STP, LACP, LLDP, ...) is never forwarded by an IEEE 802.1D bridge.
            return NONE;
        }
        long source = mac(frame, 6);
        if ((frame[6] & 1) == 0) {
            table.put(source, new Entry(inPort, now));
        }
        if ((frame[0] & 1) == 0) {
            Entry known = table.get(destination);
            if (known != null && now - known.seen < AGING_NANOS) {
                return known.port == inPort ? NONE : new int[]{known.port};
            }
        }
        int[] flood = new int[ports - 1];
        for (int port = 0, i = 0; port < ports; port++) {
            if (port != inPort) {
                flood[i++] = port;
            }
        }
        return flood;
    }

    /** A cable was pulled: forget the addresses learnt on that port. */
    void forgetPort(int port) {
        table.values().removeIf(entry -> entry.port == port);
    }

    private static long mac(byte[] frame, int offset) {
        long value = 0;
        for (int i = 0; i < 6; i++) {
            value = (value << 8) | (frame[offset + i] & 0xFF);
        }
        return value;
    }
}
