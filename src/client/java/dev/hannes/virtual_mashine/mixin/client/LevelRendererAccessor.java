package dev.hannes.virtual_mashine.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Picks the chunk sections a camera block sees, and makes the next frame pick the player's again. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Invoker("applyFrustum")
    void virtual_mashine$applyFrustum(Frustum frustum);

    @Accessor("prevCamRotX")
    void virtual_mashine$setPrevCamRotX(double value);
}
