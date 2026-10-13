package dev.hannes.virtual_mashine.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level renders into the main target; a camera block's picture swaps in its own for a moment. */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Mutable
    @Accessor("mainRenderTarget")
    void virtual_mashine$setMainRenderTarget(RenderTarget target);
}
