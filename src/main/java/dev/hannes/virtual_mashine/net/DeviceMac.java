package dev.hannes.virtual_mashine.net;

import java.util.Locale;
import java.util.UUID;

/** Stable, locally unique MAC address per device and port (QEMU's 52:54:00 prefix). */
public final class DeviceMac {
    private DeviceMac() {
    }

    public static String of(UUID device, int port) {
        long bits = device.getLeastSignificantBits() ^ device.getMostSignificantBits();
        return String.format(Locale.ROOT, "52:54:00:%02x:%02x:%02x", (bits >>> 40) & 0xFF, (bits >>> 32) & 0xFF,
                ((bits >>> 24) & 0xF0) | (port & 0x0F));
    }
}
