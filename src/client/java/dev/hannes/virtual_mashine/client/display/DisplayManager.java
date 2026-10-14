package dev.hannes.virtual_mashine.client.display;

import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.client.vnc.RfbClient;
import dev.hannes.virtual_mashine.client.vnc.RfbTransport;
import dev.hannes.virtual_mashine.client.vnc.TunnelTransport;
import dev.hannes.virtual_mashine.client.vnc.VncKeyboard;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of monitor blocks: one screen connection per picture in sight, its picture as a
 * texture, and mouse and keyboard in the world. A picture is a display's own page or a screen of a
 * computer arriving over HDMI; monitors showing the same screen share one connection.
 * <p>
 * Looking at a running monitor moves its pointer; clicking, holding (drag) and the wheel go to the
 * picture instead of Minecraft. Sneaking leaves the mouse to Minecraft, so the block can still be
 * broken or its settings opened. Clicking a computer's monitor also hands it the keyboard (the player
 * stops walking) until Left Ctrl + Left Alt gives it back; meanwhile looking around moves the pointer,
 * across all monitors of that computer.
 */
public final class DisplayManager {
    /** How far away a monitor still takes clicks. */
    private static final double REACH = 12;
    /** Pictures and monitors not drawn for this long lose their connection. */
    private static final long IDLE_MS = 4000;

    /** One picture: screen {@code head} of a machine (a display's own system has only screen 0). */
    private record FeedKey(UUID machine, int head) {
    }

    private static final class Feed {
        final FeedKey key;
        final Identifier textureId;
        final DisplayTexture texture = new DisplayTexture();
        @Nullable RfbClient client;
        int port;
        long retryAt;
        long lastSeen;
        boolean registered;

        Feed(FeedKey key) {
            this.key = key;
            this.textureId = Virtual_mashine.id("display/" + key.machine().toString().toLowerCase(Locale.ROOT) + "_" + key.head());
        }

        boolean live() {
            return client != null && client.status() == RfbClient.Status.CONNECTED && texture.ready();
        }
    }

    /** A monitor block in sight. */
    private static final class Monitor {
        BlockPos pos = BlockPos.ZERO;
        Direction facing = Direction.NORTH;
        int width = 1;
        int height = 1;
        @Nullable Feed feed;
        /** Shows a computer over HDMI (real mouse buttons, takes the keyboard) rather than a page. */
        boolean hdmi;
        long lastSeen;
    }

    private record Aim(Monitor monitor, Feed feed, int x, int y) {
    }

    private static final Map<FeedKey, Feed> FEEDS = new HashMap<>();
    private static final Map<UUID, Monitor> MONITORS = new HashMap<>();
    private static final VncKeyboard KEYBOARD = new VncKeyboard();
    private static @Nullable Aim aim;
    private static @Nullable Feed pressedOn;
    private static int buttonMask;
    /** The VNC button bit each mouse button set when it went down. */
    private static final int[] BUTTON_BITS = new int[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];
    private static @Nullable Feed lastPointerFeed;
    private static int lastX = -1;
    private static int lastY = -1;
    /** The computer that has the keyboard, or null. */
    private static @Nullable UUID captured;

    private DisplayManager() {
    }

    /**
     * While a monitor is drawn (render thread): keeps its picture's connection, uploads new pixels and
     * returns the texture to draw, or null if there is nothing to show yet.
     *
     * @param port   VNC port of the picture on the hosting computer, 0 = nothing to show
     * @param source the computer on the HDMI cable, null for the display's own system
     * @param head   which screen of {@code source}
     */
    public static @Nullable Identifier frame(UUID id, BlockPos pos, Direction facing, int width, int height, int port,
                                             @Nullable UUID source, int head) {
        long now = System.currentTimeMillis();
        Monitor monitor = MONITORS.computeIfAbsent(id, key -> new Monitor());
        monitor.pos = pos.immutable();
        monitor.facing = facing;
        monitor.width = width;
        monitor.height = height;
        monitor.lastSeen = now;
        monitor.hdmi = source != null;
        if (port <= 0) {
            monitor.feed = null;
            return null;
        }
        Feed feed = FEEDS.computeIfAbsent(source != null ? new FeedKey(source, head) : new FeedKey(id, 0), Feed::new);
        monitor.feed = feed;
        feed.lastSeen = now;
        if (feed.client == null || feed.port != port || feed.client.status() == RfbClient.Status.CLOSED) {
            if (now >= feed.retryAt) {
                disconnect(feed);
                Minecraft minecraft = Minecraft.getInstance();
                RfbTransport transport = minecraft.hasSingleplayerServer() ? RfbTransport.local(port)
                        : new TunnelTransport(feed.key.machine(), feed.key.head());
                feed.client = new RfbClient(transport, port);
                feed.port = port;
                feed.retryAt = now + 3000;
            }
        }
        if (feed.client != null) {
            feed.texture.update(feed.client);
        }
        if (!feed.texture.ready()) {
            return null;
        }
        if (!feed.registered) {
            Minecraft.getInstance().getTextureManager().register(feed.textureId, feed.texture);
            feed.registered = true;
        }
        return feed.textureId;
    }

    private static void disconnect(Feed feed) {
        if (feed.client != null) {
            feed.client.close();
            feed.client = null;
        }
    }

    // ---------------------------------------------------------------- every client tick

    public static void tick(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        MONITORS.values().removeIf(monitor -> now - monitor.lastSeen > IDLE_MS);
        Iterator<Feed> it = FEEDS.values().iterator();
        while (it.hasNext()) {
            Feed feed = it.next();
            if (now - feed.lastSeen > IDLE_MS) {
                if (pressedOn == feed) {
                    pressedOn = null;
                    buttonMask = 0;
                }
                disconnect(feed);
                if (feed.registered) {
                    minecraft.getTextureManager().release(feed.textureId);
                } else {
                    feed.texture.close();
                }
                it.remove();
            }
        }
        if (minecraft.level == null || minecraft.player == null) {
            disconnectAll();
            aim = null;
            return;
        }
        if (captured != null && (minecraft.screen != null || !minecraft.isWindowActive() || keyboardFeed() == null)) {
            releaseCapture(); // A menu opened, Alt-Tab, or the computer went away: never leave keys stuck in the guest.
        }
        aim = minecraft.screen == null ? findAim(minecraft) : null;
        Aim current = aim;
        if (pressedOn != null && (current == null || current.feed() != pressedOn)) {
            return; // Dragging off the screen: keep the button down until it is released.
        }
        if (current != null && current.feed().client != null
                && (current.x() != lastX || current.y() != lastY || current.feed() != lastPointerFeed)) {
            current.feed().client.sendPointer(buttonMask, current.x(), current.y());
            lastPointerFeed = current.feed();
            lastX = current.x();
            lastY = current.y();
        }
    }

    /** The point of a running monitor under the crosshair, if no block is in between. */
    private static @Nullable Aim findAim(Minecraft minecraft) {
        Vec3 eye = minecraft.player.getEyePosition(1.0F);
        Vec3 look = minecraft.player.getViewVector(1.0F);
        Aim best = null;
        double bestT = Double.MAX_VALUE;
        for (Monitor m : MONITORS.values()) {
            Feed feed = m.feed;
            if (feed == null || !feed.live()) {
                continue;
            }
            Vec3 normal = new Vec3(m.facing.getStepX(), 0, m.facing.getStepZ());
            double facingDot = look.dot(normal);
            if (facingDot >= -1.0E-4) {
                continue; // Looking at its back or along it.
            }
            Vec3 origin = corner(m.pos, m.facing);
            double t = origin.subtract(eye).dot(normal) / facingDot;
            if (t <= 0 || t > REACH || t >= bestT) {
                continue;
            }
            Vec3 hit = eye.add(look.scale(t));
            Vec3 rel = hit.subtract(origin);
            Direction right = m.facing.getCounterClockWise();
            double u = rel.dot(new Vec3(right.getStepX(), 0, right.getStepZ())) / m.width;
            double v = rel.y / m.height;
            if (u < 0 || u > 1 || v < 0 || v > 1) {
                continue;
            }
            BlockHitResult between = minecraft.level.clip(new ClipContext(eye, hit.subtract(look.scale(0.06)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
            if (between.getType() == HitResult.Type.BLOCK && !between.getBlockPos().equals(m.pos)) {
                continue; // Something stands in front of the screen.
            }
            int x = (int) Math.min(feed.texture.width() - 1, Math.floor(u * feed.texture.width()));
            int y = (int) Math.min(feed.texture.height() - 1, Math.floor((1 - v) * feed.texture.height()));
            best = new Aim(m, feed, x, y);
            bestT = t;
        }
        return best;
    }

    /** Lower left corner of the screen as seen from the front, slightly in front of the block. */
    static Vec3 corner(BlockPos pos, Direction facing) {
        Direction right = facing.getCounterClockWise();
        return Vec3.atCenterOf(pos)
                .add(facing.getStepX() * 0.503, -0.5, facing.getStepZ() * 0.503)
                .subtract(right.getStepX() * 0.5, 0, right.getStepZ() * 0.5);
    }

    // ---------------------------------------------------------------- mouse (from the mixin)

    /** @return true if the click belongs to a monitor and Minecraft must not see it */
    public static boolean mouseButton(int button, int action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            return false;
        }
        Aim current = aim;
        if (action == GLFW.GLFW_PRESS) {
            if (current == null || current.feed().client == null || captured == null && minecraft.player.isShiftKeyDown()) {
                // While a computer has the keyboard, stray clicks must not break blocks next to its monitors.
                return captured != null;
            }
            int bit = mouseBit(current.monitor(), button);
            if (bit == 0) {
                return captured != null;
            }
            if (current.monitor().hdmi) {
                capture(current.feed());
            }
            buttonMask |= bit;
            if (button >= 0 && button < BUTTON_BITS.length) {
                BUTTON_BITS[button] = bit;
            }
            pressedOn = current.feed();
            current.feed().client.sendPointer(buttonMask, current.x(), current.y());
            return true;
        }
        if (action == GLFW.GLFW_RELEASE && pressedOn != null) {
            int bit = button >= 0 && button < BUTTON_BITS.length ? BUTTON_BITS[button] : 0;
            if ((buttonMask & bit) == 0) {
                return captured != null;
            }
            buttonMask &= ~bit;
            Feed target = pressedOn;
            int x = current != null && current.feed() == target ? current.x() : Math.max(0, lastX);
            int y = current != null && current.feed() == target ? current.y() : Math.max(0, lastY);
            if (target.client != null) {
                target.client.sendPointer(buttonMask, x, y);
            }
            if (buttonMask == 0) {
                pressedOn = null;
            }
            return true;
        }
        return captured != null && action == GLFW.GLFW_RELEASE;
    }

    /** A computer gets real mouse buttons; on a page both buttons click, the middle one too. */
    private static int mouseBit(Monitor monitor, int button) {
        if (!monitor.hdmi) {
            return button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE ? 2 : 1;
        }
        return switch (button) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> 1;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 1 << 1;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 1 << 2;
            default -> 0;
        };
    }

    /** @return true if the wheel scrolled a monitor's picture (or a computer has the keyboard) */
    public static boolean scroll(double amount) {
        Minecraft minecraft = Minecraft.getInstance();
        Aim current = aim;
        if (minecraft.screen != null || current == null || current.feed().client == null || amount == 0) {
            return captured != null && minecraft.screen == null;
        }
        int bit = amount > 0 ? 1 << 3 : 1 << 4;
        int notches = Math.max(1, Math.min(5, (int) Math.round(Math.abs(amount))));
        for (int i = 0; i < notches; i++) {
            current.feed().client.sendPointer(buttonMask | bit, current.x(), current.y());
            current.feed().client.sendPointer(buttonMask, current.x(), current.y());
        }
        return true;
    }

    // ---------------------------------------------------------------- keyboard (from the mixin)

    private static void capture(Feed feed) {
        if (captured != null && !captured.equals(feed.key.machine())) {
            releaseCapture();
        }
        if (captured == null) {
            captured = feed.key.machine();
            // Keys held for walking would stay pressed in Minecraft while the guest gets the keyboard.
            KeyMapping.releaseAll();
        }
    }

    /** A connection to the computer that has the keyboard (keys reach the guest through any of its screens). */
    private static @Nullable Feed keyboardFeed() {
        UUID machine = captured;
        if (machine == null) {
            return null;
        }
        if (pressedOn != null && pressedOn.key.machine().equals(machine) && pressedOn.live()) {
            return pressedOn;
        }
        Feed fallback = null;
        for (Feed feed : FEEDS.values()) {
            if (feed.key.machine().equals(machine) && feed.live()) {
                if (aim != null && aim.feed() == feed) {
                    return feed;
                }
                fallback = feed;
            }
        }
        return fallback;
    }

    /** While true, {@code KeyboardHandlerMixin} sends every key here instead of to Minecraft. */
    public static boolean isCapturingKeyboard() {
        return captured != null && Minecraft.getInstance().screen == null;
    }

    public static void handleRawKey(KeyEvent event, int action) {
        if (action == VncKeyboard.ACTION_PRESS && VncKeyboard.isReleaseCombo(Minecraft.getInstance().getWindow().handle(), event.key())) {
            releaseCapture();
            return;
        }
        Feed feed = keyboardFeed();
        if (feed != null && feed.client != null) {
            KEYBOARD.key(feed.client, event, action);
        }
    }

    private static void releaseCapture() {
        Feed feed = keyboardFeed();
        KEYBOARD.releaseAll(feed != null ? feed.client : null);
        if (pressedOn != null && pressedOn.client != null && buttonMask != 0) {
            pressedOn.client.sendPointer(0, Math.max(0, lastX), Math.max(0, lastY));
        }
        pressedOn = null;
        buttonMask = 0;
        captured = null;
    }

    /** Top left while a computer has the keyboard, and a hint while looking at one that could get it. */
    public static void hud(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.options.hideGui) {
            return;
        }
        Component text;
        int color;
        if (captured != null) {
            text = Component.translatable("virtual_mashine.monitor.captured");
            color = 0xFF8AB8FF;
        } else if (aim != null && aim.monitor().hdmi) {
            text = Component.translatable("virtual_mashine.monitor.click_to_control");
            color = 0xFFD0D0D0;
        } else {
            return;
        }
        int width = minecraft.font.width(text);
        int x = (minecraft.getWindow().getGuiScaledWidth() - width) / 2;
        int y = minecraft.getWindow().getGuiScaledHeight() - 72;
        graphics.fill(x - 4, y - 3, x + width + 4, y + 11, 0x90000000);
        graphics.text(minecraft.font, text, x, y, color, false);
    }

    public static void disconnectAll() {
        if (captured != null) {
            releaseCapture();
        }
        for (Feed feed : FEEDS.values()) {
            disconnect(feed);
        }
    }
}
