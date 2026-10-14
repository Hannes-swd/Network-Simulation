package dev.hannes.virtual_mashine.device;

import java.util.Locale;

/** Cable categories with their real-world limits (1 block = 1 metre). */
public enum CableType {
    CAT5E("cat5e", Kind.COPPER, 100, 0x9AA0A6),
    CAT6("cat6", Kind.COPPER, 100, 0x2F6FD6),
    CAT6A("cat6a", Kind.COPPER, 100, 0x2EA44F),
    CAT7("cat7", Kind.COPPER, 100, 0xE07B22),
    /** Single-mode fibre (yellow jacket). */
    FIBER("fiber", Kind.FIBER, 10_000, 0xF2C230),
    /** Rollover console cable from a computer's serial port to a router or switch console. */
    CONSOLE("console", Kind.CONSOLE, 10, 0x7FC8E8),
    /** USB 2.0 with an active extension: connects a microphone or camera to a computer. */
    USB("usb", Kind.USB, 10, 0x3A3A3E),
    /** HDMI 2.0: carries a computer's picture to a monitor or an HDMI switch. */
    HDMI("hdmi", Kind.HDMI, 15, 0x2A2A2A);

    public enum Kind { COPPER, FIBER, CONSOLE, USB, HDMI }

    public final String id;
    public final Kind kind;
    public final int maxLength;
    public final int defaultColor;

    CableType(String id, Kind kind, int maxLength, int defaultColor) {
        this.id = id;
        this.kind = kind;
        this.maxLength = maxLength;
        this.defaultColor = defaultColor;
    }

    public boolean isConsole() {
        return kind == Kind.CONSOLE;
    }

    public boolean isUsb() {
        return kind == Kind.USB;
    }

    public boolean isHdmi() {
        return kind == Kind.HDMI;
    }

    /** Console, USB and HDMI cables do not carry network frames. */
    public boolean isNetwork() {
        return kind == Kind.COPPER || kind == Kind.FIBER;
    }

    /** Line rate in Mbit/s over a cable of this length; 0 for console cables. */
    public int speedMbit(double length) {
        return switch (this) {
            case CAT5E -> 1_000;
            case CAT6 -> length <= 55 ? 10_000 : 1_000;
            case CAT6A, CAT7, FIBER -> 10_000;
            case CONSOLE -> 0;
            case USB -> 480;
            case HDMI -> 18_000;
        };
    }

    /** Translation key of the speed description shown in tooltips. */
    public String speedKey() {
        return "virtual_mashine.cable." + id + ".speed";
    }

    public static CableType byId(String id) {
        if (id != null) {
            String wanted = id.toLowerCase(Locale.ROOT);
            for (CableType type : values()) {
                if (type.id.equals(wanted)) {
                    return type;
                }
            }
        }
        return CAT6;
    }

    /** The jacket colours real patch cables come in. */
    public static final int[] COLORS = {
            0x2F6FD6, // blau
            0xE5C33A, // gelb
            0xD23B3B, // rot
            0x2EA44F, // grün
            0x9AA0A6, // grau
            0xF2F2F2, // weiß
            0x2A2A2A, // schwarz
            0xE07B22, // orange
            0x8E44AD, // lila
    };

    public static int nextColor(int current) {
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i] == current) {
                return COLORS[(i + 1) % COLORS.length];
            }
        }
        return COLORS[0];
    }

    public static String colorKey(int color) {
        String[] names = {"blue", "yellow", "red", "green", "gray", "white", "black", "orange", "purple"};
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i] == color) {
                return "virtual_mashine.color." + names[i];
            }
        }
        if (color == FIBER.defaultColor) {
            return "virtual_mashine.color.yellow";
        }
        if (color == CONSOLE.defaultColor) {
            return "virtual_mashine.color.light_blue";
        }
        if (color == USB.defaultColor) {
            return "virtual_mashine.color.black";
        }
        return "virtual_mashine.color.custom";
    }
}
