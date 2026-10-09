package dev.hannes.virtual_mashine.client.display;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.hannes.virtual_mashine.client.vnc.RfbClient;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.nio.ByteBuffer;

/** The picture of a display block as a normal Minecraft texture, fed with the changed rows of its guest screen. */
final class DisplayTexture extends AbstractTexture {
    private int width;
    private int height;

    /** Render thread, outside of a render pass. */
    void update(RfbClient client) {
        client.consumeFrame(this::upload);
    }

    private void upload(ByteBuffer pixels, int w, int h, int top, int bottom, boolean resized) {
        if (w <= 0 || h <= 0) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        if (texture == null || resized || w != width || h != height) {
            releaseGpu();
            texture = device.createTexture(() -> "virtual_mashine display", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    TextureFormat.RGBA8, w, h, 1, 1);
            textureView = device.createTextureView(texture);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            width = w;
            height = h;
            top = 0;
            bottom = h - 1;
        }
        int first = Math.max(0, top);
        int rows = Math.min(h - 1, bottom) - first + 1;
        if (rows > 0) {
            device.createCommandEncoder().writeToTexture(texture, pixels.slice(first * w * 4, rows * w * 4),
                    NativeImage.Format.RGBA, 0, 0, 0, first, w, rows);
        }
    }

    boolean ready() {
        return textureView != null;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    private void releaseGpu() {
        if (textureView != null) {
            textureView.close();
            textureView = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }

    @Override
    public void close() {
        releaseGpu();
    }
}
