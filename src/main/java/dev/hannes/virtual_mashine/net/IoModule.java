package dev.hannes.virtual_mashine.net;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * One network I/O module: six redstone channels, one per face, each either an input (reads the
 * signal arriving at exactly that face) or an output (drives 0..15 on exactly that face), plus its
 * network settings. Shared between the server thread (redstone) and the fabric thread (network).
 */
public final class IoModule {
    public static final int CHANNELS = 6;
    /** Channel order, relative to the module's front. Used in the API, Modbus addresses follow it. */
    public static final String[] SIDES = {"front", "back", "left", "right", "top", "bottom"};

    /** What is stored in the world (io.json). */
    public static final class Settings {
        public String name = "";
        public boolean dhcp = true;
        public String ip = "192.168.1.50";
        public String mask = "255.255.255.0";
        public String gateway = "192.168.1.1";
        public boolean[] outputs = new boolean[CHANNELS];
        public int[] levels = new int[CHANNELS];
    }

    private final String mac;
    private final AtomicIntegerArray inputs = new AtomicIntegerArray(CHANNELS);
    private final AtomicIntegerArray outputs = new AtomicIntegerArray(CHANNELS);
    private final AtomicInteger outputMask = new AtomicInteger();
    private final AtomicInteger networkVersion = new AtomicInteger();
    private volatile String name;
    private volatile boolean dhcp;
    private volatile int staticIp;
    private volatile int staticMask;
    private volatile int staticGateway;
    private volatile int ip;
    private volatile int mask;
    private volatile int gateway;
    private volatile boolean dirty;

    public IoModule(String mac, Settings settings) {
        this.mac = mac;
        this.name = settings.name;
        this.dhcp = settings.dhcp;
        this.staticIp = parseIp(settings.ip);
        this.staticMask = parseIp(settings.mask);
        this.staticGateway = parseIp(settings.gateway);
        for (int ch = 0; ch < CHANNELS; ch++) {
            if (settings.outputs != null && ch < settings.outputs.length && settings.outputs[ch]) {
                int bit = 1 << ch;
                outputMask.updateAndGet(m -> m | bit);
                if (settings.levels != null && ch < settings.levels.length) {
                    outputs.set(ch, clamp(settings.levels[ch]));
                }
            }
        }
    }

    public Settings settings() {
        Settings s = new Settings();
        s.name = name;
        s.dhcp = dhcp;
        s.ip = formatIp(staticIp);
        s.mask = formatIp(staticMask);
        s.gateway = formatIp(staticGateway);
        for (int ch = 0; ch < CHANNELS; ch++) {
            s.outputs[ch] = isOutput(ch);
            s.levels[ch] = outputs.get(ch);
        }
        return s;
    }

    // ---------------------------------------------------------------- channels

    public boolean isOutput(int ch) {
        return (outputMask.get() & 1 << ch) != 0;
    }

    public int outputMask() {
        return outputMask.get();
    }

    /** Switching to input releases the output, so nothing stays powered by surprise. */
    public void setMode(int ch, boolean output) {
        outputMask.updateAndGet(m -> output ? m | 1 << ch : m & ~(1 << ch));
        if (!output) {
            outputs.set(ch, 0);
        }
        dirty = true;
    }

    /** @return false if the channel is an input */
    public boolean setOutput(int ch, int level) {
        if (!isOutput(ch)) {
            return false;
        }
        outputs.set(ch, clamp(level));
        dirty = true;
        return true;
    }

    public int output(int ch) {
        return isOutput(ch) ? outputs.get(ch) : 0;
    }

    public int input(int ch) {
        return inputs.get(ch);
    }

    public void setInput(int ch, int level) {
        inputs.set(ch, clamp(level));
    }

    /** The level on the face: what an input reads or what an output drives. */
    public int level(int ch) {
        return isOutput(ch) ? outputs.get(ch) : inputs.get(ch);
    }

    /** Output levels (4 bits per channel) and the output mask in bits 24..29, for the block. */
    public int packedOutputs() {
        int packed = outputMask.get() << 24;
        for (int ch = 0; ch < CHANNELS; ch++) {
            packed |= output(ch) << (ch * 4);
        }
        return packed;
    }

    public static int packedLevel(int packed, int ch) {
        return (packed >>> (ch * 4)) & 0xF;
    }

    public static boolean packedIsOutput(int packed, int ch) {
        return (packed >>> 24 & 1 << ch) != 0;
    }

    // ---------------------------------------------------------------- network settings

    public String mac() {
        return mac;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        dirty = true;
    }

    public boolean dhcp() {
        return dhcp;
    }

    public int staticIp() {
        return staticIp;
    }

    public int staticMask() {
        return staticMask;
    }

    public int staticGateway() {
        return staticGateway;
    }

    public void configureNetwork(boolean dhcp, int ip, int mask, int gateway) {
        this.dhcp = dhcp;
        this.staticIp = ip;
        this.staticMask = mask;
        this.staticGateway = gateway;
        dirty = true;
        networkVersion.incrementAndGet();
    }

    int networkVersion() {
        return networkVersion.get();
    }

    /** The address the module currently answers on (0 = none yet). */
    public int ip() {
        return ip;
    }

    public int mask() {
        return mask;
    }

    public int gateway() {
        return gateway;
    }

    void setAddress(int ip, int mask, int gateway) {
        this.ip = ip;
        this.mask = mask;
        this.gateway = gateway;
    }

    public boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    // ---------------------------------------------------------------- helpers

    public static int clamp(int level) {
        return Math.max(0, Math.min(15, level));
    }

    /** @return the address, or 0 if {@code text} is not a dotted IPv4 address */
    public static int parseIp(String text) {
        if (text == null) {
            return 0;
        }
        String[] parts = text.trim().split("\\.");
        if (parts.length != 4) {
            return 0;
        }
        int value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return 0;
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return 0;
            }
            value = value << 8 | octet;
        }
        return value;
    }

    public static String formatIp(int ip) {
        return (ip >>> 24) + "." + (ip >>> 16 & 0xFF) + "." + (ip >>> 8 & 0xFF) + "." + (ip & 0xFF);
    }
}
