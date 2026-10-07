package dev.hannes.virtual_mashine.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.hannes.virtual_mashine.client.vnc.RfbClient;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/** GPU texture mirroring the guest screen; only rows that changed are uploaded. */
final class VmScreenTexture implements AutoCloseable {
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private int width;
    private int height;

    /** Copies new guest pixels to the GPU. Must run on the render thread outside of a render pass. */
    void update(RfbClient client) {
        client.consumeFrame(this::upload);
    }

    private void upload(ByteBuffer pixels, int w, int h, int top, int bottom, boolean resized) {
        if (w <= 0 || h <= 0) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        if (texture == null || resized || w != width || h != height) {
            close();
            texture = device.createTexture(() -> "virtual_mashine screen", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    TextureFormat.RGBA8, w, h, 1, 1);
            view = device.createTextureView(texture);
            width = w;
            height = h;
            top = 0;
            bottom = h - 1;
        }
        int first = Math.max(0, top);
        int rows = Math.min(h - 1, bottom) - first + 1;
        if (rows <= 0) {
            return;
        }
        ByteBuffer band = pixels.slice(first * w * 4, rows * w * 4);
        device.createCommandEncoder().writeToTexture(texture, band, NativeImage.Format.RGBA, 0, 0, 0, first, w, rows);
    }

    boolean ready() {
        return view != null;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    @Nullable GpuTextureView view() {
        return view;
    }

    /** Smooth when scaled down (sharp text matters less than not losing pixels), sharp when scaled up. */
    GpuSampler sampler(boolean magnified) {
        return RenderSystem.getSamplerCache().getClampToEdge(magnified ? FilterMode.NEAREST : FilterMode.LINEAR);
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }
}
