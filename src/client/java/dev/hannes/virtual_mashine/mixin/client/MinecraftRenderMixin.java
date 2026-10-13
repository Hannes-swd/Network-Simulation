package dev.hannes.virtual_mashine.mixin.client;

import dev.hannes.virtual_mashine.client.media.CameraCapture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camera blocks take their picture at the start of a frame, before the player's own view is drawn over it. */
@Mixin(Minecraft.class)
public abstract class MinecraftRenderMixin {
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void virtual_mashine$cameraPictures(boolean advanceGameTime, CallbackInfo ci) {
        if (advanceGameTime) {
            CameraCapture.renderDue((Minecraft) (Object) this);
        }
    }
}
