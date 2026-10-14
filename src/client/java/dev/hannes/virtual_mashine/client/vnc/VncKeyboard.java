package dev.hannes.virtual_mashine.client.vnc;

import dev.hannes.virtual_mashine.vm.HostInfo;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The player's keyboard on a guest: raw scancodes (the guest applies its own layout), and every key
 * that went down in the guest also comes up again when the keyboard is handed back.
 */
public final class VncKeyboard {
    public static final int ACTION_RELEASE = 0;
    public static final int ACTION_PRESS = 1;

    /** Keys currently held down in the guest: GLFW key (or negative raw scancode) to {scancode, keysym}. */
    private final Map<Integer, int[]> heldKeys = new LinkedHashMap<>();

    public void key(RfbClient client, KeyEvent event, int action) {
        int key = event.key();
        int scancode = VncKeys.scancode(key);
        int keysym = VncKeys.keysym(key);
        int heldId = key;
        if (scancode == 0 && HostInfo.OS == HostInfo.Os.WINDOWS) {
            scancode = VncKeys.fromWindowsScancode(event.scancode());
            heldId = -event.scancode();
        }
        if (scancode == 0 && keysym == 0) {
            return;
        }
        boolean down = action != ACTION_RELEASE;
        if (down) {
            heldKeys.put(heldId, new int[]{scancode, keysym});
        } else if (heldKeys.remove(heldId) == null) {
            return; // Released a key the guest never saw pressed (e.g. the capture click combo).
        }
        client.sendKey(down, scancode, keysym);
    }

    /** Lets go of every key the guest still sees held. */
    public void releaseAll(@Nullable RfbClient client) {
        if (client != null) {
            for (int[] held : heldKeys.values()) {
                client.sendKey(false, held[0], held[1]);
            }
        }
        heldKeys.clear();
    }

    /** Left Ctrl + Left Alt hands keyboard and mouse back to Minecraft. */
    public static boolean isReleaseCombo(long window, int key) {
        return (key == GLFW.GLFW_KEY_LEFT_ALT && GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS)
                || (key == GLFW.GLFW_KEY_LEFT_CONTROL && GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS);
    }
}
