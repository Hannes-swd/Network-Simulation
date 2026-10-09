package dev.hannes.virtual_mashine.client.vnc;

import java.util.HashMap;
import java.util.Map;

/**
 * Types text into the guest as key presses, for guests without a clipboard agent (BIOS, consoles,
 * Tiny Core, router shells, ...). Keys are XT scancodes, so the characters depend on the guest's
 * keyboard layout, which therefore has to be known.
 */
public final class TextTyper {
    private static final int SHIFT = 0x2A;
    private static final int ALT_GR = 0xB8;
    private static final int SPACE = 0x39;
    private static final int WITH_SHIFT = 1 << 8;
    private static final int WITH_ALT_GR = 1 << 9;
    /** Dead key (accents): a space after it produces the character itself. */
    private static final int DEAD = 1 << 10;
    /**
     * Pause between characters: QEMU's PS/2 keyboard drops key events when the guest reads them more
     * slowly than they arrive, and a lost Shift release would turn the rest of the text into capitals.
     */
    private static final long DELAY_MS = 15;
    /** Characters with Shift or AltGr are four key events instead of two. */
    private static final long MODIFIER_DELAY_MS = 10;

    private static final Map<Character, Integer> US = new HashMap<>();
    private static final Map<Character, Integer> DE = new HashMap<>();

    static {
        common(US);
        row(US, "1234567890-=", "!@#$%^&*()_+", 0x02);
        row(US, "qwertyuiop[]", "QWERTYUIOP{}", 0x10);
        row(US, "asdfghjkl;'`", "ASDFGHJKL:\"~", 0x1E);
        row(US, "\\zxcvbnm,./", "|ZXCVBNM<>?", 0x2B);

        common(DE);
        row(DE, "1234567890ß", "!\"§$%&/()=?", 0x02);
        DE.put('´', 0x0D | DEAD);
        DE.put('`', 0x0D | WITH_SHIFT | DEAD);
        row(DE, "qwertzuiopü+", "QWERTZUIOPÜ*", 0x10);
        row(DE, "asdfghjklöä", "ASDFGHJKLÖÄ", 0x1E);
        DE.put('^', 0x29 | DEAD);
        DE.put('°', 0x29 | WITH_SHIFT);
        row(DE, "#yxcvbnm,.-", "'YXCVBNM;:_", 0x2B);
        DE.put('<', 0x56);
        DE.put('>', 0x56 | WITH_SHIFT);
        altGr(DE, '²', 0x03);
        altGr(DE, '³', 0x04);
        altGr(DE, '{', 0x08);
        altGr(DE, '[', 0x09);
        altGr(DE, ']', 0x0A);
        altGr(DE, '}', 0x0B);
        altGr(DE, '\\', 0x0C);
        altGr(DE, '@', 0x10);
        altGr(DE, '€', 0x12);
        DE.put('~', 0x1B | WITH_ALT_GR | DEAD); // Dead on Linux; on Windows the extra space stays.
        altGr(DE, '|', 0x56);
        altGr(DE, 'µ', 0x32);
    }

    private static volatile Thread running;

    private TextTyper() {
    }

    private static void common(Map<Character, Integer> map) {
        map.put(' ', SPACE);
        map.put('\n', 0x1C);
        map.put('\t', 0x0F);
    }

    /** Keys with consecutive scancodes: character i sits on scancode {@code first + i}. */
    private static void row(Map<Character, Integer> map, String plain, String shifted, int first) {
        for (int i = 0; i < plain.length(); i++) {
            map.put(plain.charAt(i), first + i);
            map.put(shifted.charAt(i), (first + i) | WITH_SHIFT);
        }
    }

    private static void altGr(Map<Character, Integer> map, char c, int scancode) {
        map.put(c, scancode | WITH_ALT_GR);
    }

    /** Characters of {@code text} that cannot be typed on {@code layout}. */
    public static int untypable(String text, String layout) {
        Map<Character, Integer> map = "us".equals(layout) ? US : DE;
        int missing = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\r' && !map.containsKey(c)) {
                missing++;
            }
        }
        return missing;
    }

    /** Types {@code text} in the background; a new call stops text that is still being typed. */
    public static void type(RfbClient client, String text, String layout) {
        Map<Character, Integer> map = "us".equals(layout) ? US : DE;
        Thread previous = running;
        if (previous != null) {
            previous.interrupt();
        }
        Thread thread = new Thread(() -> {
            try {
                for (int i = 0; i < text.length() && client.status() == RfbClient.Status.CONNECTED; i++) {
                    Integer key = map.get(text.charAt(i));
                    if (key == null) {
                        continue;
                    }
                    press(client, key);
                    Thread.sleep(DELAY_MS + ((key & (WITH_SHIFT | WITH_ALT_GR)) != 0 ? MODIFIER_DELAY_MS : 0));
                }
            } catch (InterruptedException ignored) {
                // A newer paste took over.
            }
        }, "virtual_mashine-paste");
        thread.setDaemon(true);
        running = thread;
        thread.start();
    }

    private static void press(RfbClient client, int key) {
        int scancode = key & 0xFF;
        boolean shift = (key & WITH_SHIFT) != 0;
        boolean altGr = (key & WITH_ALT_GR) != 0;
        if (shift) {
            client.sendKey(true, SHIFT, 0xFFE1);
        }
        if (altGr) {
            client.sendKey(true, ALT_GR, 0xFE03);
        }
        client.sendKey(true, scancode, 0);
        client.sendKey(false, scancode, 0);
        if (altGr) {
            client.sendKey(false, ALT_GR, 0xFE03);
        }
        if (shift) {
            client.sendKey(false, SHIFT, 0xFFE1);
        }
        if ((key & DEAD) != 0) {
            client.sendKey(true, SPACE, 0);
            client.sendKey(false, SPACE, 0);
        }
    }
}
