package dev.hannes.virtual_mashine.client.net;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.block.DeviceBlock;
import dev.hannes.virtual_mashine.block.DeviceBlockEntity;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.net.DeviceGeometry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The little link (green) and traffic (amber) LEDs above every socket on the device's housing. */
public final class DeviceLedRenderer implements BlockEntityRenderer<DeviceBlockEntity, DeviceLedRenderer.State> {
    private static final Identifier TEXTURE = Virtual_mashine.id("textures/entity/cable.png");
    private static final float SIZE = 0.022F;

    public static final class State extends BlockEntityRenderState {
        @Nullable DeviceType type;
        Direction facing = Direction.NORTH;
        int link;
        int activity;
    }

    public DeviceLedRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(DeviceBlockEntity device, State state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(device, state, partialTicks, cameraPosition, breakProgress);
        BlockState blockState = device.getBlockState();
        if (blockState.getBlock() instanceof DeviceBlock block) {
            state.type = block.deviceType();
            state.facing = blockState.getValue(DeviceBlock.FACING);
        } else {
            state.type = null;
        }
        state.link = device.linkLeds();
        state.activity = device.activityLeds();
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        DeviceType type = state.type;
        if (type == null || state.link == 0) {
            return;
        }
        boolean blinkOn = (System.currentTimeMillis() / 90) % 2 == 0;
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(TEXTURE), (pose, buffer) -> {
            for (int port = 0; port < Math.min(32, type.totalPorts()); port++) {
                if ((state.link & (1 << port)) == 0) {
                    continue;
                }
                Vec3 socket = DeviceGeometry.socket(type, net.minecraft.core.BlockPos.ZERO, state.facing, port);
                // Same faces as DeviceGeometry: switches/routers have network ports in front, everything else at the back.
                Direction face = !type.isConsolePort(port) && type.portsOnFront ? state.facing : state.facing.getOpposite();
                Direction left = face.getClockWise();
                double y = socket.y + 0.055;
                // Link LED on the left, traffic LED on the right of the socket.
                Vec3 linkLed = socket.add(left.getStepX() * 0.03, y - socket.y, left.getStepZ() * 0.03);
                Vec3 trafficLed = socket.add(-left.getStepX() * 0.03, y - socket.y, -left.getStepZ() * 0.03);
                square(buffer, pose, linkLed, face, left, 0xFF3DFF5A);
                if ((state.activity & (1 << port)) != 0 && blinkOn) {
                    square(buffer, pose, trafficLed, face, left, 0xFFFFB12E);
                }
            }
        });
    }

    /** A tiny glowing square on the face, facing outwards. */
    private static void square(VertexConsumer buffer, PoseStack.Pose pose, Vec3 centre, Direction face, Direction left, int argb) {
        float cx = (float) (centre.x + face.getStepX() * 0.004);
        float cy = (float) centre.y;
        float cz = (float) (centre.z + face.getStepZ() * 0.004);
        float lx = left.getStepX() * SIZE / 2;
        float lz = left.getStepZ() * SIZE / 2;
        float h = SIZE / 3;
        float nx = face.getStepX();
        float nz = face.getStepZ();
        // Counter-clockwise seen from outside the face.
        vertex(buffer, pose, cx + lx, cy - h, cz + lz, argb, nx, nz);
        vertex(buffer, pose, cx - lx, cy - h, cz - lz, argb, nx, nz);
        vertex(buffer, pose, cx - lx, cy + h, cz - lz, argb, nx, nz);
        vertex(buffer, pose, cx + lx, cy + h, cz + lz, argb, nx, nz);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int argb, float nx, float nz) {
        buffer.addVertex(pose, x, y, z)
                .setColor(argb)
                .setUv(0.5F, 0.5F)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT)
                .setNormal(pose, nx, 0, nz);
    }
}
