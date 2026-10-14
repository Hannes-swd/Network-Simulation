package dev.hannes.virtual_mashine.mixin.client;

import dev.hannes.virtual_mashine.client.media.GameSoundTap;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every sound the game starts also reaches the microphone blocks that listen to game sounds. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "play", at = @At("RETURN"))
    private void virtual_mashine$tapForMicrophones(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (cir.getReturnValue() != SoundEngine.PlayResult.NOT_STARTED) {
            GameSoundTap.onPlay(instance);
        }
    }
}
