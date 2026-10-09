package dev.hannes.virtual_mashine.client.display;

import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.client.vnc.RfbClient;
import dev.hannes.virtual_mashine.client.vnc.RfbTransport;
import dev.hannes.virtual_mashine.client.vnc.TunnelTransport;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * Client side of display blocks: one screen connection per display in sight, its picture as a
 * texture, and the mouse in the world. Looking at a running display moves its pointer; clicking
 * (either button), holding (drag) and the wheel go to the page instead of Minecraft. Sneaking
 * leaves the mouse to Minecraft, so the block can still be broken or its settings opened.
 */
public final class DisplayManager {
    /** How far away a display still takes clicks. */
    private static final double REACH = 12;
    /** Displays not drawn for this long lose their connection. */
    private static final long IDLE_MS = 4000;

    private static final class Entry {
        final UUID id;
        final Identifier textureId;
        final DisplayTexture texture = new DisplayTexture();
        @Nullable RfbClient client;
        int port;
        long retryAt;
        BlockPos pos = BlockPos.ZERO;
        Direction facing = Direction.NORTH;
        int width = 1;
        int height = 1;
        long lastSeen;
        boolean registered;

        Entry(UUID id) {
            this.id = id;
            this.textureId = Virtual_mashine.id("display/" + id.toString().toLowerCase(Locale.ROOT));
        }

        boolean live() {
            return client != null && client.status() == RfbClient.Status.CONNECTED && texture.ready();
        }
    }

    private record Aim(Entry entry, int x, int y) {
    }

    private static final Map<UUID, Entry> DISPLAYS = new HashMap<>();
    private static @Nullable Aim aim;
    private static @Nullable Entry pressedOn;
    private static int buttonMask;
    private static int lastX = -1;
    private static int lastY = -1;

    private DisplayManager() {
    }

    /**
     * While a display is drawn (render thread): keeps its connection, uploads new pixels and
     * returns the texture to draw, or null if there is nothing to show yet.
     */
    public static @Nullable Identifier frame(UUID id, BlockPos pos, Direction facing, int width, int height, int port) {
        Entry e = DISPLAYS.computeIfAbsent(id, Entry::new);
        e.pos = pos.immutable();
        e.facing = facing;
        e.width = width;
        e.height = height;
        e.lastSeen = System.currentTimeMillis();
        if (port <= 0) {
            disconnect(e);
            return null;
        }
        if (e.client == null || e.port != port || e.client.status() == RfbClient.Status.CLOSED) {
            if (System.currentTimeMillis() >= e.retryAt) {
                disconnect(e);
                Minecraft minecraft = Minecraft.getInstance();
                RfbTransport transport = minecraft.hasSingleplayerServer() ? RfbTransport.local(port) : new TunnelTransport(id);
                e.client = new RfbClient(transport, port);
                e.port = port;
                e.retryAt = System.currentTimeMillis() + 3000;
            }
        }
        if (e.client != null) {
            e.texture.update(e.client);
        }
        if (!e.texture.ready()) {
            return null;
        }
        if (!e.registered) {
            Minecraft.getInstance().getTextureManager().register(e.textureId, e.texture);
            e.registered = true;
        }
        return e.textureId;
    }

    private static void disconnect(Entry e) {
        if (e.client != null) {
            e.client.close();
            e.client = null;
        }
    }

    // ---------------------------------------------------------------- every client tick

    public static void tick(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        Iterator<Entry> it = DISPLAYS.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (now - e.lastSeen > IDLE_MS) {
                disconnect(e);
                if (e.registered) {
                    minecraft.getTextureManager().release(e.textureId);
                } else {
                    e.texture.close();
                }
                it.remove();
            }
        }
        if (minecraft.level == null || minecraft.player == null) {
            for (Entry e : DISPLAYS.values()) {
                disconnect(e);
            }
            aim = null;
            return;
        }
        aim = minecraft.screen == null ? findAim(minecraft) : null;
        Aim current = aim;
        if (pressedOn != null && (current == null || current.entry() != pressedOn)) {
            return; // Dragging off the screen: keep the button down until it is released.
        }
        if (current != null && (current.x() != lastX || current.y() != lastY) && current.entry().client != null) {
            current.entry().client.sendPointer(buttonMask, current.x(), current.y());
            lastX = current.x();
            lastY = current.y();
        }
    }

    /** The point of a running display under the crosshair, if no block is in between. */
    private static @Nullable Aim findAim(Minecraft minecraft) {
        Vec3 eye = minecraft.player.getEyePosition(1.0F);
        Vec3 look = minecraft.player.getViewVector(1.0F);
        Aim best = null;
        double bestT = Double.MAX_VALUE;
        for (Entry e : DISPLAYS.values()) {
            if (!e.live()) {
                continue;
            }
            Vec3 normal = new Vec3(e.facing.getStepX(), 0, e.facing.getStepZ());
            double facingDot = look.dot(normal);
            if (facingDot >= -1.0E-4) {
                continue; // Looking at its back or along it.
            }
            Vec3 origin = corner(e);
            double t = origin.subtract(eye).dot(normal) / facingDot;
            if (t <= 0 || t > REACH || t >= bestT) {
                continue;
            }
            Vec3 hit = eye.add(look.scale(t));
            Vec3 rel = hit.subtract(origin);
            Direction right = e.facing.getCounterClockWise();
            double u = rel.dot(new Vec3(right.getStepX(), 0, right.getStepZ())) / e.width;
            double v = rel.y / e.height;
            if (u < 0 || u > 1 || v < 0 || v > 1) {
                continue;
            }
            BlockHitResult between = minecraft.level.clip(new ClipContext(eye, hit.subtract(look.scale(0.06)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
            if (between.getType() == HitResult.Type.BLOCK && !between.getBlockPos().equals(e.pos)) {
                continue; // Something stands in front of the screen.
            }
            int x = (int) Math.min(e.texture.width() - 1, Math.floor(u * e.texture.width()));
            int y = (int) Math.min(e.texture.height() - 1, Math.floor((1 - v) * e.texture.height()));
            best = new Aim(e, x, y);
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

    private static Vec3 corner(Entry e) {
        return corner(e.pos, e.facing);
    }

    // ---------------------------------------------------------------- mouse (from the mixin)

    /** @return true if the click belongs to a display and Minecraft must not see it */
    public static boolean mouseButton(int button, int action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            return false;
        }
        int bit = button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE ? 2 : 1; // Left and right click both click the page.
        if (action == GLFW.GLFW_PRESS) {
            Aim current = aim;
            if (current == null || minecraft.player.isShiftKeyDown() || current.entry().client == null) {
                return false;
            }
            buttonMask |= bit;
            pressedOn = current.entry();
            current.entry().client.sendPointer(buttonMask, current.x(), current.y());
            return true;
        }
        if (action == GLFW.GLFW_RELEASE && pressedOn != null && (buttonMask & bit) != 0) {
            buttonMask &= ~bit;
            Entry target = pressedOn;
            Aim current = aim;
            int x = current != null && current.entry() == target ? current.x() : Math.max(0, lastX);
            int y = current != null && current.entry() == target ? current.y() : Math.max(0, lastY);
            if (target.client != null) {
                target.client.sendPointer(buttonMask, x, y);
            }
            if (buttonMask == 0) {
                pressedOn = null;
            }
            return true;
        }
        return false;
    }

    /** @return true if the wheel scrolled a display's page */
    public static boolean scroll(double amount) {
        Minecraft minecraft = Minecraft.getInstance();
        Aim current = aim;
        if (minecraft.screen != null || current == null || current.entry().client == null || amount == 0) {
            return false;
        }
        int bit = amount > 0 ? 1 << 3 : 1 << 4;
        int notches = Math.max(1, Math.min(5, (int) Math.round(Math.abs(amount))));
        for (int i = 0; i < notches; i++) {
            current.entry().client.sendPointer(buttonMask | bit, current.x(), current.y());
            current.entry().client.sendPointer(buttonMask, current.x(), current.y());
        }
        return true;
    }

    public static void disconnectAll() {
        for (Entry e : DISPLAYS.values()) {
            disconnect(e);
        }
    }
}
