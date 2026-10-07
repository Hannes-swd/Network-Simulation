package dev.hannes.virtual_mashine.client.vnc;

import org.lwjgl.glfw.GLFW;

/**
 * Maps GLFW key tokens to PC/XT scancodes and X11 keysyms.
 * <p>
 * GLFW key tokens name physical key positions (on a German keyboard the key labelled Z reports
 * {@code GLFW_KEY_Y}), so sending the matching scancode lets the guest apply its own layout,
 * exactly like a real keyboard would. 0xE0-prefixed scancodes are encoded with the high bit set,
 * as QEMU's extended key event expects.
 */
public final class VncKeys {
    private static final int[] SCANCODE = new int[GLFW.GLFW_KEY_LAST + 1];
    private static final int[] KEYSYM = new int[GLFW.GLFW_KEY_LAST + 1];

    static {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int[] letterCodes = {0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
                0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C};
        for (int i = 0; i < letters.length(); i++) {
            map(GLFW.GLFW_KEY_A + i, letterCodes[i], 'a' + i);
        }
        map(GLFW.GLFW_KEY_1, 0x02, '1');
        map(GLFW.GLFW_KEY_2, 0x03, '2');
        map(GLFW.GLFW_KEY_3, 0x04, '3');
        map(GLFW.GLFW_KEY_4, 0x05, '4');
        map(GLFW.GLFW_KEY_5, 0x06, '5');
        map(GLFW.GLFW_KEY_6, 0x07, '6');
        map(GLFW.GLFW_KEY_7, 0x08, '7');
        map(GLFW.GLFW_KEY_8, 0x09, '8');
        map(GLFW.GLFW_KEY_9, 0x0A, '9');
        map(GLFW.GLFW_KEY_0, 0x0B, '0');

        map(GLFW.GLFW_KEY_SPACE, 0x39, 0x20);
        map(GLFW.GLFW_KEY_APOSTROPHE, 0x28, 0x27);
        map(GLFW.GLFW_KEY_COMMA, 0x33, 0x2C);
        map(GLFW.GLFW_KEY_MINUS, 0x0C, 0x2D);
        map(GLFW.GLFW_KEY_PERIOD, 0x34, 0x2E);
        map(GLFW.GLFW_KEY_SLASH, 0x35, 0x2F);
        map(GLFW.GLFW_KEY_SEMICOLON, 0x27, 0x3B);
        map(GLFW.GLFW_KEY_EQUAL, 0x0D, 0x3D);
        map(GLFW.GLFW_KEY_LEFT_BRACKET, 0x1A, 0x5B);
        map(GLFW.GLFW_KEY_BACKSLASH, 0x2B, 0x5C);
        map(GLFW.GLFW_KEY_RIGHT_BRACKET, 0x1B, 0x5D);
        map(GLFW.GLFW_KEY_GRAVE_ACCENT, 0x29, 0x60);
        map(GLFW.GLFW_KEY_WORLD_1, 0x56, 0x3C); // The extra <> key of ISO (e.g. German) keyboards.

        map(GLFW.GLFW_KEY_ESCAPE, 0x01, 0xFF1B);
        map(GLFW.GLFW_KEY_ENTER, 0x1C, 0xFF0D);
        map(GLFW.GLFW_KEY_TAB, 0x0F, 0xFF09);
        map(GLFW.GLFW_KEY_BACKSPACE, 0x0E, 0xFF08);
        map(GLFW.GLFW_KEY_INSERT, 0xD2, 0xFF63);
        map(GLFW.GLFW_KEY_DELETE, 0xD3, 0xFFFF);
        map(GLFW.GLFW_KEY_RIGHT, 0xCD, 0xFF53);
        map(GLFW.GLFW_KEY_LEFT, 0xCB, 0xFF51);
        map(GLFW.GLFW_KEY_DOWN, 0xD0, 0xFF54);
        map(GLFW.GLFW_KEY_UP, 0xC8, 0xFF52);
        map(GLFW.GLFW_KEY_PAGE_UP, 0xC9, 0xFF55);
        map(GLFW.GLFW_KEY_PAGE_DOWN, 0xD1, 0xFF56);
        map(GLFW.GLFW_KEY_HOME, 0xC7, 0xFF50);
        map(GLFW.GLFW_KEY_END, 0xCF, 0xFF57);
        map(GLFW.GLFW_KEY_CAPS_LOCK, 0x3A, 0xFFE5);
        map(GLFW.GLFW_KEY_SCROLL_LOCK, 0x46, 0xFF14);
        map(GLFW.GLFW_KEY_NUM_LOCK, 0x45, 0xFF7F);
        map(GLFW.GLFW_KEY_PRINT_SCREEN, 0xB7, 0xFF61);
        map(GLFW.GLFW_KEY_PAUSE, 0xC6, 0xFF13);

        int[] fKeys = {0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44, 0x57, 0x58};
        for (int i = 0; i < fKeys.length; i++) {
            map(GLFW.GLFW_KEY_F1 + i, fKeys[i], 0xFFBE + i);
        }

        int[] keypad = {0x52, 0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49};
        for (int i = 0; i < keypad.length; i++) {
            map(GLFW.GLFW_KEY_KP_0 + i, keypad[i], 0xFFB0 + i);
        }
        map(GLFW.GLFW_KEY_KP_DECIMAL, 0x53, 0xFFAE);
        map(GLFW.GLFW_KEY_KP_DIVIDE, 0xB5, 0xFFAF);
        map(GLFW.GLFW_KEY_KP_MULTIPLY, 0x37, 0xFFAA);
        map(GLFW.GLFW_KEY_KP_SUBTRACT, 0x4A, 0xFFAD);
        map(GLFW.GLFW_KEY_KP_ADD, 0x4E, 0xFFAB);
        map(GLFW.GLFW_KEY_KP_ENTER, 0x9C, 0xFF8D);
        map(GLFW.GLFW_KEY_KP_EQUAL, 0x59, 0xFFBD);

        map(GLFW.GLFW_KEY_LEFT_SHIFT, 0x2A, 0xFFE1);
        map(GLFW.GLFW_KEY_LEFT_CONTROL, 0x1D, 0xFFE3);
        map(GLFW.GLFW_KEY_LEFT_ALT, 0x38, 0xFFE9);
        map(GLFW.GLFW_KEY_LEFT_SUPER, 0xDB, 0xFFEB);
        map(GLFW.GLFW_KEY_RIGHT_SHIFT, 0x36, 0xFFE2);
        map(GLFW.GLFW_KEY_RIGHT_CONTROL, 0x9D, 0xFFE4);
        map(GLFW.GLFW_KEY_RIGHT_ALT, 0xB8, 0xFE03); // AltGr
        map(GLFW.GLFW_KEY_RIGHT_SUPER, 0xDC, 0xFFEC);
        map(GLFW.GLFW_KEY_MENU, 0xDD, 0xFF67);
    }

    private VncKeys() {
    }

    private static void map(int glfwKey, int scancode, int keysym) {
        SCANCODE[glfwKey] = scancode;
        KEYSYM[glfwKey] = keysym;
    }

    /** XT scancode for a GLFW key, or 0 if the key is unknown. */
    public static int scancode(int glfwKey) {
        return glfwKey >= 0 && glfwKey < SCANCODE.length ? SCANCODE[glfwKey] : 0;
    }

    /** X11 keysym for a GLFW key, or 0 if the key is unknown. */
    public static int keysym(int glfwKey) {
        return glfwKey >= 0 && glfwKey < KEYSYM.length ? KEYSYM[glfwKey] : 0;
    }

    /**
     * Fallback for keys GLFW has no token for: on Windows the platform scancode is already an XT
     * scancode, with bit 8 marking the 0xE0 prefix.
     */
    public static int fromWindowsScancode(int scancode) {
        if (scancode <= 0 || scancode > 0x1FF) {
            return 0;
        }
        return (scancode & 0x7F) | ((scancode & 0x100) != 0 ? 0x80 : 0);
    }
}
