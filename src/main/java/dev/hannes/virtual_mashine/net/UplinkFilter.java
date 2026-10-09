package dev.hannes.virtual_mashine.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Guards every internet uplink. QEMU's user-mode NAT maps each address of its virtual network
 * (except the DNS server) to the host computer's own loopback, so without this filter a program
 * inside a VM could reach every service that only listens locally on the host, including the
 * control ports of all other VMs. It also keeps VMs out of the real home network.
 * <p>
 * Frames towards the uplink may only carry ARP, DHCP/DNS for the uplink itself, and traffic to
 * public internet addresses. Everything else is dropped, as by a firewall. The host computer's own
 * public addresses count as private too.
 */
final class UplinkFilter {
    private static final int GATEWAY = ipv4(192, 168, 178, 1);
    private static final int DNS = ipv4(192, 168, 178, 2);
    private static final int BROADCAST = 0xFFFFFFFF;
    private static final int TCP = 6;
    private static final int UDP = 17;
    private static final int ICMP = 1;
    private static final int ICMPV6 = 58;
    private static final int DHCP_SERVER = 67;
    private static final int DHCPV6_SERVER = 547;
    private static final int DNS_PORT = 53;
    /** QEMU's default IPv6 DNS address inside its fec0::/64 network. */
    private static final byte[] DNS6 = {(byte) 0xFE, (byte) 0xC0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3};

    private static volatile Set<Integer> hostIpv4 = Set.of();
    private static volatile Set<ByteBuffer> hostIpv6 = Set.of();

    private UplinkFilter() {
    }

    /** Re-reads the host's own addresses (they change when it reconnects); called now and then. */
    static void refreshHostAddresses() {
        Set<Integer> v4 = new HashSet<>();
        Set<ByteBuffer> v6 = new HashSet<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    byte[] raw = address.getAddress();
                    if (address instanceof Inet4Address) {
                        v4.add(s32(raw, 0));
                    } else if (address instanceof Inet6Address) {
                        v6.add(ByteBuffer.wrap(raw));
                    }
                }
            }
        } catch (SocketException e) {
            return; // Keep the previous list.
        }
        hostIpv4 = v4;
        hostIpv6 = v6;
    }

    static boolean allows(byte[] frame) {
        if (frame.length < 14) {
            return false;
        }
        return switch (u16(frame, 12)) {
            case 0x0806 -> true; // ARP
            case 0x0800 -> allowsIpv4(frame, 14);
            case 0x86DD -> allowsIpv6(frame, 14);
            default -> false; // VLAN tags and other protocols: the NAT would ignore them anyway.
        };
    }

    private static boolean allowsIpv4(byte[] f, int o) {
        if (f.length < o + 20 || (f[o] & 0xF0) != 0x40) {
            return false;
        }
        int headerLength = (f[o] & 0x0F) * 4;
        if (headerLength < 20 || f.length < o + headerLength) {
            return false;
        }
        int protocol = f[o + 9] & 0xFF;
        int destination = s32(f, o + 16);
        boolean fragment = (u16(f, o + 6) & 0x3FFF) != 0; // "more fragments" flag or an offset
        int port = !fragment && (protocol == TCP || protocol == UDP) && f.length >= o + headerLength + 4
                ? u16(f, o + headerLength + 2) : -1;
        if (destination == DNS) {
            return !fragment && (protocol == ICMP || port == DNS_PORT);
        }
        if (destination == GATEWAY || destination == BROADCAST) {
            return !fragment && (protocol == ICMP && destination == GATEWAY || protocol == UDP && port == DHCP_SERVER);
        }
        return !isSpecialIpv4(destination) && !hostIpv4.contains(destination);
    }

    /** Private, loopback, link-local, carrier-grade NAT, multicast and reserved ranges. */
    private static boolean isSpecialIpv4(int a) {
        int first = a >>> 24;
        int second = (a >>> 16) & 0xFF;
        return first == 0 || first == 10 || first == 127 || first >= 224
                || first == 100 && (second & 0xC0) == 64
                || first == 169 && second == 254
                || first == 172 && (second & 0xF0) == 16
                || first == 192 && second == 168
                || first == 192 && second == 0 && ((a >>> 8) & 0xFF) == 0
                || first == 198 && (second & 0xFE) == 18;
    }

    private static boolean allowsIpv6(byte[] f, int o) {
        if (f.length < o + 40 || (f[o] & 0xF0) != 0x60) {
            return false;
        }
        int next = f[o + 6] & 0xFF;
        int d = o + 24;
        if (next == ICMPV6) {
            return true; // Neighbour discovery, router advertisements, ping.
        }
        if (next != TCP && next != UDP) {
            return false; // Extension headers (fragments, routing, ...) are not needed for normal traffic.
        }
        int port = f.length >= o + 44 ? u16(f, o + 42) : -1;
        if (equals(f, d, DNS6)) {
            return port == DNS_PORT;
        }
        int b0 = f[d] & 0xFF;
        int b1 = f[d + 1] & 0xFF;
        if (b0 == 0xFF) {
            // Multicast: only the DHCPv6 request, which the NAT answers itself.
            return next == UDP && port == DHCPV6_SERVER && b1 == 0x02;
        }
        // Global unicast 2000::/3, without 6to4 (2002::/16), which would tunnel to private IPv4 addresses.
        return (b0 & 0xE0) == 0x20 && !(b0 == 0x20 && b1 == 0x02)
                && !hostIpv6.contains(ByteBuffer.wrap(f, d, 16).slice());
    }

    private static boolean equals(byte[] f, int offset, byte[] address) {
        for (int i = 0; i < address.length; i++) {
            if (f[offset + i] != address[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] f, int offset) {
        return (f[offset] & 0xFF) << 8 | f[offset + 1] & 0xFF;
    }

    private static int s32(byte[] f, int offset) {
        return (f[offset] & 0xFF) << 24 | (f[offset + 1] & 0xFF) << 16 | (f[offset + 2] & 0xFF) << 8 | f[offset + 3] & 0xFF;
    }

    private static int ipv4(int a, int b, int c, int d) {
        return a << 24 | b << 16 | c << 8 | d;
    }
}
