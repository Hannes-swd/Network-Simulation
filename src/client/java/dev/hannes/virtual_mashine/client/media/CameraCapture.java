package dev.hannes.virtual_mashine.client.media;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.mixin.client.CameraAccessor;
import dev.hannes.virtual_mashine.mixin.client.LevelRendererAccessor;
import dev.hannes.virtual_mashine.mixin.client.MinecraftAccessor;
import dev.hannes.virtual_mashine.network.MediaPayloads;
import dev.hannes.virtual_mashine.vm.ModConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Films for camera blocks the server put this game in charge of. About ten times a second, at the
 * start of a frame, the level is drawn once more from the camera's lens into a small target of its
 * own; the picture goes to the server as JPEG, and the player's own frame is drawn over everything
 * right after. The player is drawn like everybody else, so the camera sees them standing in front of it.
 */
public final class CameraCapture {
    public static final int WIDTH = 640;
    public static final int HEIGHT = 360;
    private static final long INTERVAL_NANOS = 100_000_000L;
    /** Vertical field of view, like a desk webcam. */
    private static final float FOV = 55.0F;

    private record Job(UUID camera, BlockPos pos, Direction facing) {
    }

    private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();
    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "virtual_mashine-camera-jpeg");
        thread.setDaemon(true);
        return thread;
    });
    /** A picture is still on its way (read back or encoded): skip a turn rather than queue up. */
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile long busySince;
    private static @Nullable TextureTarget target;
    private static long nextAt;
    private static int turn;
    private static int frame;

    private CameraCapture() {
    }

    public static void onJob(MediaPayloads.CameraJob job) {
        if (job.active()) {
            JOBS.put(job.camera(), new Job(job.camera(), job.pos(), job.facing()));
        } else {
            JOBS.remove(job.camera());
        }
    }

    public static void reset() {
        JOBS.clear();
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    /** Render thread, at the start of a frame (see MinecraftRenderMixin). */
    public static void renderDue(Minecraft minecraft) {
        if (JOBS.isEmpty() || minecraft.level == null || minecraft.player == null || !ModConfig.get().allowCamera) {
            return;
        }
        long now = System.nanoTime();
        if (BUSY.get() && now - busySince > 2_000_000_000L) {
            BUSY.set(false); // A read-back that never came back must not stop the camera for good.
        }
        if (now < nextAt || BUSY.get()) {
            return;
        }
        nextAt = now + INTERVAL_NANOS / JOBS.size();
        List<Job> jobs = new ArrayList<>(JOBS.values());
        Job job = jobs.get(turn++ % jobs.size());
        if (!minecraft.level.isLoaded(job.pos())) {
            return;
        }
        BUSY.set(true);
        busySince = now;
        try {
            render(minecraft, job);
        } catch (RuntimeException e) {
            BUSY.set(false);
            JOBS.remove(job.camera());
            Virtual_mashine.LOGGER.warn("Camera picture failed, the camera stops filming", e);
        }
    }

    private static void render(Minecraft minecraft, Job job) {
        if (target == null) {
            target = new TextureTarget("virtual_mashine camera", WIDTH, HEIGHT, true);
        }
        TextureTarget picture = target;
        GameRenderer renderer = minecraft.gameRenderer;
        Camera camera = renderer.getMainCamera();
        CameraAccessor lens = (CameraAccessor) camera;
        LevelRendererAccessor level = (LevelRendererAccessor) minecraft.levelRenderer;
        DeltaTracker delta = minecraft.getDeltaTracker();
        RenderTarget main = minecraft.getMainRenderTarget();

        Direction facing = job.facing();
        Vec3 eye = new Vec3(job.pos().getX() + 0.5 + facing.getStepX() * 0.32, job.pos().getY() + 0.375,
                job.pos().getZ() + 0.5 + facing.getStepZ() * 0.32);
        lens.virtual_mashine$setPosition(eye);
        lens.virtual_mashine$setRotation(facing.toYRot(), 0.0F);
        lens.virtual_mashine$setDetached(true); // so the player in front of the camera is drawn
        camera.enablePanoramicMode(); // no hand, no screen effects, no outlines
        float depthFar = lens.virtual_mashine$depthFar();
        Matrix4f view = camera.getViewRotationMatrix(new Matrix4f());
        Matrix4f cull = new Matrix4f().perspective(FOV * (float) (Math.PI / 180.0), (float) WIDTH / HEIGHT, 0.05F, depthFar,
                RenderSystem.getDevice().isZZeroToOne());
        lens.virtual_mashine$prepareCullFrustum(view, cull, eye);
        lens.virtual_mashine$setupPerspective(0.05F, depthFar, FOV, WIDTH, HEIGHT);
        // The chunk sections the player's game knows, cut to what the camera looks at.
        level.virtual_mashine$applyFrustum(LevelRenderer.offsetFrustum(camera.getCullFrustum()));
        renderer.setRenderBlockOutline(false);
        ((MinecraftAccessor) minecraft).virtual_mashine$setMainRenderTarget(picture);
        try {
            renderer.extract(delta, true);
            GameRenderState state = renderer.getGameRenderState();
            state.optionsRenderState.bobView = false;
            state.optionsRenderState.cameraType = CameraType.THIRD_PERSON_BACK;
            state.levelRenderState.haveGlowingEntities = false;
            state.levelRenderState.cameraRenderState.entityRenderState.isLiving = false; // no hurt tilt
            renderer.getGlobalSettingsUniform().update(WIDTH, HEIGHT, state.optionsRenderState.glintStrength,
                    minecraft.level.getGameTime(), delta, 0, eye, state.optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS);
            renderer.renderLevel(delta);
            UUID cameraId = job.camera();
            Screenshot.takeScreenshot(picture, image -> encode(cameraId, image));
        } finally {
            ((MinecraftAccessor) minecraft).virtual_mashine$setMainRenderTarget(main);
            camera.disablePanoramicMode();
            renderer.setRenderBlockOutline(true);
            // The frame drawn next aligns the camera with the player again; make it pick its own sections too.
            level.virtual_mashine$setPrevCamRotX(Double.NaN);
        }
    }

    private static void encode(UUID camera, NativeImage image) {
        int[] pixels;
        try (image) {
            pixels = image.getPixels();
        } catch (RuntimeException e) {
            BUSY.set(false);
            throw e;
        }
        ENCODER.execute(() -> {
            try {
                BufferedImage picture = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
                picture.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
                byte[] jpeg = jpeg(picture);
                Minecraft.getInstance().execute(() -> send(camera, jpeg));
            } catch (IOException | RuntimeException e) {
                Virtual_mashine.LOGGER.debug("Camera picture could not be encoded", e);
            } finally {
                BUSY.set(false);
            }
        });
    }

    private static byte[] jpeg(BufferedImage picture) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(48 * 1024);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.7F);
            writer.write(null, new IIOImage(picture, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static void send(UUID camera, byte[] jpeg) {
        if (!JOBS.containsKey(camera) || !ClientPlayNetworking.canSend(MediaPayloads.CameraFrame.TYPE)) {
            return;
        }
        int count = (jpeg.length + MediaPayloads.MAX_FRAME_CHUNK - 1) / MediaPayloads.MAX_FRAME_CHUNK;
        if (count > MediaPayloads.MAX_FRAME_CHUNKS) {
            return;
        }
        int id = frame++;
        for (int i = 0; i < count; i++) {
            int start = i * MediaPayloads.MAX_FRAME_CHUNK;
            byte[] part = Arrays.copyOfRange(jpeg, start, Math.min(jpeg.length, start + MediaPayloads.MAX_FRAME_CHUNK));
            ClientPlayNetworking.send(new MediaPayloads.CameraFrame(camera, id, i, count, part));
        }
    }
}
