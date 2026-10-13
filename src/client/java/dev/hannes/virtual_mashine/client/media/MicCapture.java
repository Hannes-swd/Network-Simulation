package dev.hannes.virtual_mashine.client.media;

import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.network.MediaPayloads;
import dev.hannes.virtual_mashine.vm.ModConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The player's voice for microphone blocks. Recording only runs while the server says the player
 * stands at a microphone that is on and plugged into a running computer, and the HUD shows it the
 * whole time. {@code allowMicrophone} in the mod's config switches it off for good.
 */
public final class MicCapture {
    private static final int RATE = 24_000;
    /** At most this many samples per packet (200 ms). */
    private static final int PACKET_SAMPLES = MediaPayloads.MAX_MIC_BYTES / 2;

    private static boolean wanted;
    private static String target = "";
    private static long device;
    /** Opening the default recording device failed: do not retry every tick. */
    private static long retryAt;
    private static boolean unavailable;
    private static float level;

    private MicCapture() {
    }

    public static void onState(MediaPayloads.MicState state) {
        wanted = state.active();
        target = state.target();
        if (!wanted) {
            close();
        }
    }

    public static void tick(Minecraft minecraft) {
        if (!wanted || minecraft.player == null || !ModConfig.get().allowMicrophone
                || !ClientPlayNetworking.canSend(MediaPayloads.MicAudio.TYPE)) {
            close();
            return;
        }
        if (device == 0) {
            if (System.currentTimeMillis() < retryAt) {
                return;
            }
            device = ALC11.alcCaptureOpenDevice((CharSequence) null, RATE, AL10.AL_FORMAT_MONO16, RATE);
            if (device == 0) {
                unavailable = true;
                retryAt = System.currentTimeMillis() + 5000;
                Virtual_mashine.LOGGER.warn("No microphone to record from");
                return;
            }
            unavailable = false;
            ALC11.alcCaptureStart(device);
        }
        int available = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
        float peak = 0;
        while (available > 0) {
            int count = Math.min(available, PACKET_SAMPLES);
            short[] samples = new short[count];
            ALC11.alcCaptureSamples(device, samples, count);
            ByteBuffer bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short sample : samples) {
                bytes.putShort(sample);
                peak = Math.max(peak, Math.abs(sample) / 32768F);
            }
            ClientPlayNetworking.send(new MediaPayloads.MicAudio(bytes.array()));
            available -= count;
        }
        level = Math.max(peak, level * 0.8F);
    }

    private static void close() {
        if (device != 0) {
            ALC11.alcCaptureStop(device);
            ALC11.alcCaptureCloseDevice(device);
            device = 0;
        }
        level = 0;
        retryAt = 0;
    }

    public static void reset() {
        wanted = false;
        unavailable = false;
        close();
    }

    /** Red dot, level meter and where the voice goes, top left, while recording. */
    public static void hud(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (!wanted) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Component text;
        int color;
        if (!ModConfig.get().allowMicrophone) {
            text = Component.translatable("virtual_mashine.mic.disabled");
            color = 0xFFAAAAAA;
        } else if (unavailable) {
            text = Component.translatable("virtual_mashine.mic.unavailable");
            color = 0xFFFFAA44;
        } else {
            text = Component.translatable("virtual_mashine.mic.active", target);
            color = 0xFFFFFFFF;
        }
        int x = 6;
        int y = 6;
        int width = minecraft.font.width(text) + 30;
        graphics.fill(x - 3, y - 3, x + width, y + 11, 0x90000000);
        boolean on = device != 0;
        graphics.fill(x, y + 1, x + 6, y + 7, on ? 0xFFE53935 : 0xFF666666);
        int meter = Math.round(Math.min(1F, level * 3) * 14);
        graphics.fill(x + 9, y + 5, x + 23, y + 7, 0xFF333333);
        graphics.fill(x + 9, y + 5, x + 9 + meter, y + 7, 0xFF4CAF50);
        graphics.text(minecraft.font, text, x + 27, y, color, true);
    }
}
