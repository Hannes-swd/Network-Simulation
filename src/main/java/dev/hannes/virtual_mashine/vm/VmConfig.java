package dev.hannes.virtual_mashine.vm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.List;

/**
 * Hardware settings of one PC, stored as {@code config.json} in its folder inside the world.
 * The same JSON is sent to the client, which sends an edited copy back.
 */
public final class VmConfig {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final int MIN_RAM_MB = 256;
    public static final int MIN_DISK_GB = 2;
    public static final int MAX_DISK_GB = 512;

    /** Network cards are plugged into the mod's cables (real networks with switches, routers, ...). */
    public static final String NET_CABLE = "cable";
    /** One network card with QEMU's built-in NAT: online without any cabling. */
    public static final String NET_DIRECT = "direct";
    public static final String NET_OFF = "off";
    public static final List<String> NIC_MODELS = List.of("e1000", "e1000e", "virtio-net-pci", "rtl8139", "vmxnet3");
    /** Guest keyboard layouts the paste button can type with. */
    public static final List<String> TYPING_LAYOUTS = List.of("de", "us");

    /** {@link dev.hannes.virtual_mashine.device.DeviceType} id: pc, server, router, ... */
    public String type = "pc";
    public String name = "PC";
    public int ramMb = 4096;
    public int cpus = 2;
    public int diskGb = 32;
    /** File name of the inserted ISO, empty when the CD drive is empty. */
    public String iso = "";
    public boolean bootFromCd = false;
    /** Hardware virtualization (WHPX/KVM/HVF). Fast, but on Windows it makes freezing impossible. */
    public boolean acceleration = true;
    public boolean sound = true;
    /** Legacy switch from before cabling existed; only read to migrate old PCs. */
    public boolean network = true;
    /** {@link #NET_CABLE}, {@link #NET_DIRECT} or {@link #NET_OFF}; null in configs from older versions. */
    public String netMode;
    public String nicModel = "e1000";
    /** Start together with the world (servers, routers and switches are always on). */
    public boolean autoStart = false;
    /** Disk image (file name in the ISO folder) the disk is created from, e.g. OpenWrt; empty = blank disk. */
    public String diskImage = "";
    /** OpenWrt role to set up on first boot ("router" / "switch"), empty for none. */
    public String provision = "";
    public boolean provisioned = false;
    /** A frozen RAM snapshot is stored inside the disk image and will be resumed on the next start. */
    public boolean frozen = false;
    /** Keyboard layout set in the guest, used when the paste button types text: "de" or "us". */
    public String typingLayout = "de";
    /**
     * The machine was started with the clipboard agent port. A snapshot only resumes on the same
     * hardware, so machines frozen by older versions keep running without it until they are restarted.
     */
    public boolean clipboardPort = false;
    /** Share the clipboard with the guest. Off keeps a VM (e.g. one used to test malware) fully apart. */
    public boolean clipboardSharing = true;
    /**
     * Hardware of the last fresh start (a frozen machine resumes on it): a sound card with line-in for
     * microphones, and the serial port cameras send their pictures through. Older versions had neither.
     */
    public boolean audioInput = false;
    public boolean mediaPort = false;
    /**
     * Graphics card with several screens (HDMI monitors), from the next fresh start on. Off keeps the
     * plain VGA card of older versions, for systems that only know that one.
     */
    public boolean multiScreen = true;
    /** Screens of the graphics card of the last fresh start; 0 = the plain VGA card of older versions. */
    public int videoOutputs = 0;
    /** Display: the page it shows (handed to its kiosk system at start) and its size in blocks. */
    public String displayUrl = "";
    public int displayWidth = 2;
    public int displayHeight = 1;
    public static final int MAX_DISPLAY_WIDTH = 8;
    public static final int MAX_DISPLAY_HEIGHT = 6;

    public VmConfig copy() {
        return GSON.fromJson(GSON.toJson(this), VmConfig.class);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Parses without clamping: on a remote client the local machine's limits do not apply. */
    public static VmConfig fromJson(String json) {
        VmConfig config = GSON.fromJson(json, VmConfig.class);
        if (config == null) {
            return new VmConfig();
        }
        if (config.iso == null) {
            config.iso = "";
        }
        if (config.name == null) {
            config.name = "PC";
        }
        if (config.type == null) {
            config.type = "pc";
        }
        if (config.netMode == null) {
            config.netMode = config.network ? NET_DIRECT : NET_OFF;
        }
        if (config.nicModel == null) {
            config.nicModel = "e1000";
        }
        if (config.diskImage == null) {
            config.diskImage = "";
        }
        if (config.provision == null) {
            config.provision = "";
        }
        if (config.typingLayout == null || !TYPING_LAYOUTS.contains(config.typingLayout)) {
            config.typingLayout = "de";
        }
        if (config.displayUrl == null) {
            config.displayUrl = "";
        }
        return config;
    }

    /** Clamps to what the hosting machine can give; only meaningful where the VMs run. */
    public VmConfig sanitized() {
        if (name == null || name.isBlank()) {
            name = "PC";
        }
        if (name.length() > 32) {
            name = name.substring(0, 32);
        }
        if (iso == null) {
            iso = "";
        }
        if (type == null) {
            type = "pc";
        }
        if (netMode == null) {
            // Configs from before cabling: the PC had QEMU's NAT, keep it online that way.
            netMode = network ? NET_DIRECT : NET_OFF;
        }
        if (!netMode.equals(NET_CABLE) && !netMode.equals(NET_DIRECT) && !netMode.equals(NET_OFF)) {
            netMode = NET_CABLE;
        }
        if (nicModel == null || !NIC_MODELS.contains(nicModel)) {
            nicModel = "e1000";
        }
        if (diskImage == null) {
            diskImage = "";
        }
        if (provision == null) {
            provision = "";
        }
        if (typingLayout == null || !TYPING_LAYOUTS.contains(typingLayout)) {
            typingLayout = "de";
        }
        if (displayUrl == null) {
            displayUrl = "";
        }
        displayUrl = displayUrl.strip();
        if (displayUrl.length() > 512) {
            displayUrl = displayUrl.substring(0, 512);
        }
        displayWidth = clamp(displayWidth, 1, MAX_DISPLAY_WIDTH);
        displayHeight = clamp(displayHeight, 1, MAX_DISPLAY_HEIGHT);
        ramMb = clamp(ramMb, MIN_RAM_MB, HostInfo.maxRamMb());
        cpus = clamp(cpus, 1, HostInfo.cpuCount());
        diskGb = clamp(diskGb, MIN_DISK_GB, MAX_DISK_GB);
        return this;
    }

    /** Pixels of the display's guest screen: about 480 per block, at most 1920 x 1440. */
    public int displayPixelsPerBlock() {
        return Math.min(480, Math.min(1920 / displayWidth, 1440 / displayHeight));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
