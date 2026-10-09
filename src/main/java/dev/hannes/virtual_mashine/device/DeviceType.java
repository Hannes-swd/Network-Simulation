package dev.hannes.virtual_mashine.device;

import java.util.Locale;

/**
 * Every placeable network device. VM devices run a real operating system in QEMU; the unmanaged
 * switch is a plain learning switch inside the mod; the modem is QEMU's user-mode NAT on its own.
 */
public enum DeviceType {
    //       id                kind              ports prefix  first  alwaysOn ram   cpus  portsOnFront depth  portY  spread
    PC("pc", Kind.VM, 1, "eth", 0, false, 4096, 2, false, 0.50, 0.16, 0.0),
    SERVER("server", Kind.VM, 2, "eth", 0, true, 2048, 2, false, 0.375, 0.55, 0.25),
    ROUTER("router", Kind.VM, 4, "eth", 0, true, 512, 1, true, 0.375, 0.10, 0.54),
    MANAGED_SWITCH("managed_switch", Kind.VM, 8, "Port ", 1, true, 512, 1, true, 0.50, 0.13, 0.80),
    SWITCH("switch", Kind.SWITCH, 8, "Port ", 1, true, 0, 0, true, 0.50, 0.13, 0.80),
    MODEM("modem", Kind.MODEM, 1, "LAN", -1, true, 0, 0, false, 0.25, 0.22, 0.0),
    /** Notebook: a computer with only a WLAN card (port 0 has no socket). */
    LAPTOP("laptop", Kind.VM, 1, "wlan", 0, false, 4096, 2, false, 0.30, 0.05, 0.0),
    /** WLAN access point: bridges its wireless clients to its LAN port. */
    ACCESS_POINT("access_point", Kind.AP, 1, "LAN", -1, true, 0, 0, false, 0.25, 0.09, 0.0),
    /** Smartphone with Android: an item the player carries, WLAN only. */
    PHONE("phone", Kind.VM, 1, "wlan", 0, false, 3072, 2, false, 0.0, 0.0, 0.0),
    /** Network I/O module: reads and drives redstone on each face, controlled by Modbus TCP or HTTP. */
    IO_MODULE("io_module", Kind.IO, 1, "LAN", -1, true, 0, 0, false, 0.5, 0.5, 0.0),
    /** Network display: a small kiosk computer showing one web page on its front, used by looking and clicking. */
    DISPLAY("display", Kind.VM, 1, "LAN", -1, true, 1024, 1, false, 0.5, 0.5, 0.0);

    public enum Kind { VM, SWITCH, MODEM, AP, IO }

    /** How many wireless clients one access point carries. */
    public static final int AP_CLIENT_SLOTS = 16;

    public final String id;
    public final Kind kind;
    public final int ports;
    private final String portPrefix;
    /** Number of the first port in its label; -1 = the label is the prefix alone. */
    private final int firstPortNumber;
    public final boolean alwaysOnByDefault;
    public final int defaultRamMb;
    public final int defaultCpus;
    /** Switches and routers have their sockets on the front, computers on the back. */
    public final boolean portsOnFront;
    /** Distance from the block centre to the face with the sockets, in blocks. */
    public final double socketDepth;
    /** Height of the sockets above the block's bottom, in blocks. */
    public final double socketY;
    /** Horizontal distance between the outermost sockets, in blocks. */
    public final double socketSpread;

    DeviceType(String id, Kind kind, int ports, String portPrefix, int firstPortNumber, boolean alwaysOnByDefault,
               int defaultRamMb, int defaultCpus, boolean portsOnFront, double socketDepth, double socketY, double socketSpread) {
        this.id = id;
        this.kind = kind;
        this.ports = ports;
        this.portPrefix = portPrefix;
        this.firstPortNumber = firstPortNumber;
        this.alwaysOnByDefault = alwaysOnByDefault;
        this.defaultRamMb = defaultRamMb;
        this.defaultCpus = defaultCpus;
        this.portsOnFront = portsOnFront;
        this.socketDepth = socketDepth;
        this.socketY = socketY;
        this.socketSpread = socketSpread;
    }

    public boolean isVm() {
        return kind == Kind.VM;
    }

    /** Every machine with an operating system also has a serial port (console / COM1); laptops and phones have none. */
    public boolean hasConsole() {
        return isVm() && !wirelessOnly() && this != DISPLAY;
    }

    /** Laptop and phone: their only network card is WLAN, connected through the WLAN menu. */
    public boolean wirelessOnly() {
        return this == LAPTOP || this == PHONE;
    }

    /** The WLAN card of a laptop or phone: it is connected through the WLAN menu, not with a cable. */
    public boolean isWirelessPort(int port) {
        return wirelessOnly() && port == 0;
    }

    /** Network ports plus the serial port. */
    public int totalPorts() {
        return ports + (hasConsole() ? 1 : 0);
    }

    public boolean isConsolePort(int port) {
        return hasConsole() && port == ports;
    }

    /** What the port is called on the device's housing, e.g. "eth0", "Port 3" or "Console". */
    public String portName(int port) {
        if (isConsolePort(port)) {
            return this == PC || this == SERVER ? "COM1" : "Console";
        }
        return firstPortNumber < 0 ? portPrefix : portPrefix + (port + firstPortNumber);
    }

    public String translationKey() {
        return "block.virtual_mashine." + id;
    }

    public static DeviceType byId(String id) {
        if (id != null) {
            String wanted = id.toLowerCase(Locale.ROOT);
            for (DeviceType type : values()) {
                if (type.id.equals(wanted)) {
                    return type;
                }
            }
        }
        return PC;
    }
}
