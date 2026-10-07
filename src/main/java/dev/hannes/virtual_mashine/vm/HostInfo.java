package dev.hannes.virtual_mashine.vm;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/** Facts about the real computer that limit what a virtual machine may get. */
public final class HostInfo {
    public enum Os { WINDOWS, MAC, LINUX }

    public static final Os OS = detectOs();

    private HostInfo() {
    }

    private static Os detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return Os.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Os.MAC;
        }
        return Os.LINUX;
    }

    public static int cpuCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    public static int totalRamMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return (int) Math.min(Integer.MAX_VALUE, os.getTotalMemorySize() / (1024 * 1024));
            }
        } catch (LinkageError e) {
            // Runtime without the jdk.management module.
        }
        return 8192;
    }

    /** Memory the host has free right now, or -1 if unknown. */
    public static int freeRamMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return (int) Math.min(Integer.MAX_VALUE, os.getFreeMemorySize() / (1024 * 1024));
            }
        } catch (LinkageError e) {
            // Runtime without the jdk.management module.
        }
        return -1;
    }

    /**
     * At most half of the memory: Windows and Minecraft itself need the rest, otherwise the host
     * starts swapping and the guest (and the game) crawl.
     */
    public static int maxRamMb() {
        int max = totalRamMb() / 2;
        return Math.max(VmConfig.MIN_RAM_MB, max - max % 256);
    }

    /** Whether hardware acceleration still allows saving the RAM state on this host. */
    public static boolean accelerationAllowsFreezing() {
        // WHPX (Windows) has no dirty-memory tracking, so QEMU blocks snapshots. KVM supports them.
        return OS == Os.LINUX;
    }
}
