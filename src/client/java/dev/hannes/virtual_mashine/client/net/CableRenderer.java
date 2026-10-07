package dev.hannes.virtual_mashine.client.net;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.network.NetPayloads;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/** Draws every patch cable as a sagging, lit tube between its two sockets. */
public final class CableRenderer {
    private static final Identifier TEXTURE = Virtual_mashine.id("textures/entity/cable.png");
    private static final float RADIUS = 0.028F;
    private static final double MAX_DISTANCE = 128;

    private static volatile List<NetPayloads.CableVisual> cables = List.of();
    private static volatile NetPayloads.HeldCable held = new NetPayloads.HeldCable(false, 0, 0, 0, 0);

    private CableRenderer() {
    }

    public static void setCables(List<NetPayloads.CableVisual> newCables) {
        cables = List.copyOf(newCables);
    }

    public static void setHeld(NetPayloads.HeldCable newHeld) {
        held = newHeld;
    }

    public static void clear() {
        cables = List.of();
        held = new NetPayloads.HeldCable(false, 0, 0, 0, 0);
    }

    public static void render(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        List<NetPayloads.CableVisual> all = cables;
        NetPayloads.HeldCable inHand = held;
        if (level == null || (all.isEmpty() && !inHand.active())) {
            return;
        }
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
        PoseStack poseStack = context.poseStack();
        context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.entitySolid(TEXTURE), (pose, buffer) -> {
            for (NetPayloads.CableVisual cable : all) {
                Vec3 a = new Vec3(cable.ax(), cable.ay(), cable.az());
                Vec3 b = new Vec3(cable.bx(), cable.by(), cable.bz());
                if (a.distanceTo(camera) > MAX_DISTANCE && b.distanceTo(camera) > MAX_DISTANCE) {
                    continue;
                }
                tube(buffer, pose, level, camera, a, b, cable.color());
            }
            LocalPlayer player = minecraft.player;
            if (inHand.active() && player != null) {
                float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
                Vec3 eye = player.getEyePosition(partial);
                Vec3 look = player.getViewVector(partial);
                Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
                Vec3 hand = eye.add(look.scale(0.45)).add(right.scale(0.28)).add(0, -0.38, 0);
                tube(buffer, pose, level, camera, new Vec3(inHand.x(), inHand.y(), inHand.z()), hand, inHand.color());
            }
        });
    }

    private static void tube(VertexConsumer buffer, PoseStack.Pose pose, ClientLevel level, Vec3 camera, Vec3 a, Vec3 b, int color) {
        double length = a.distanceTo(b);
        if (length < 1.0E-3) {
            return;
        }
        double horizontal = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
        double sag = Math.min(1.6, 0.05 + horizontal * 0.06);
        int segments = (int) Math.max(8, Math.min(48, length * 2));
        int argb = 0xFF000000 | color;
        Vec3 previous = point(a, b, sag, 0);
        for (int i = 1; i <= segments; i++) {
            Vec3 next = point(a, b, sag, (double) i / segments);
            segment(buffer, pose, level, camera, previous, next, argb);
            previous = next;
        }
    }

    /** Position along a cable hanging between a and b. */
    private static Vec3 point(Vec3 a, Vec3 b, double sag, double t) {
        return new Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t - sag * 4 * t * (1 - t),
                a.z + (b.z - a.z) * t);
    }

    private static void segment(VertexConsumer buffer, PoseStack.Pose pose, ClientLevel level, Vec3 camera, Vec3 from, Vec3 to, int argb) {
        Vector3f direction = new Vector3f((float) (to.x - from.x), (float) (to.y - from.y), (float) (to.z - from.z)).normalize();
        Vector3f side = new Vector3f(direction).cross(0, 1, 0);
        if (side.lengthSquared() < 1.0E-6F) {
            side.set(1, 0, 0);
        }
        side.normalize();
        Vector3f up = new Vector3f(side).cross(direction).normalize();
        int lightFrom = LevelRenderer.getLightCoords(level, BlockPos.containing(from));
        int lightTo = LevelRenderer.getLightCoords(level, BlockPos.containing(to));
        float fx = (float) (from.x - camera.x);
        float fy = (float) (from.y - camera.y);
        float fz = (float) (from.z - camera.z);
        float tx = (float) (to.x - camera.x);
        float ty = (float) (to.y - camera.y);
        float tz = (float) (to.z - camera.z);
        // Four faces around the tube: +up, +side, -up, -side.
        Vector3f[] normals = {up, side, new Vector3f(up).negate(), new Vector3f(side).negate()};
        for (int face = 0; face < 4; face++) {
            Vector3f n = normals[face];
            Vector3f t = normals[(face + 1) % 4];
            float ox1 = (n.x + t.x) * RADIUS;
            float oy1 = (n.y + t.y) * RADIUS;
            float oz1 = (n.z + t.z) * RADIUS;
            float ox2 = (n.x - t.x) * RADIUS;
            float oy2 = (n.y - t.y) * RADIUS;
            float oz2 = (n.z - t.z) * RADIUS;
            // Counter-clockwise seen from outside, so back-face culling keeps the outer side.
            vertex(buffer, pose, fx + ox1, fy + oy1, fz + oz1, argb, 0, 1, lightFrom, n);
            vertex(buffer, pose, tx + ox1, ty + oy1, tz + oz1, argb, 1, 1, lightTo, n);
            vertex(buffer, pose, tx + ox2, ty + oy2, tz + oz2, argb, 1, 0, lightTo, n);
            vertex(buffer, pose, fx + ox2, fy + oy2, fz + oz2, argb, 0, 0, lightFrom, n);
        }
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int argb, float u, float v, int light, Vector3f normal) {
        buffer.addVertex(pose, x, y, z)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normal.x, normal.y, normal.z);
    }
}
