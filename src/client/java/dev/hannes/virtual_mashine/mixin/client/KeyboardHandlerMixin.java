package dev.hannes.virtual_mashine.mixin.client;

import dev.hannes.virtual_mashine.client.display.DisplayManager;
import dev.hannes.virtual_mashine.client.screen.PcDisplayScreen;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a PC has captured the keyboard (in its window, or through a monitor in the world), keys must
 * reach the guest before Minecraft's global shortcuts (fullscreen, screenshot, narrator, debug keys)
 * and the movement keys can swallow them.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void virtual_mashine$sendKeysToVm(long handle, int action, KeyEvent event, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (handle == minecraft.getWindow().handle()
                && minecraft.screen instanceof PcDisplayScreen screen
                && screen.isCapturingKeyboard()) {
            screen.handleRawKey(event, action);
            ci.cancel();
        } else if (handle == minecraft.getWindow().handle() && DisplayManager.isCapturingKeyboard()) {
            DisplayManager.handleRawKey(event, action);
            ci.cancel();
        }
    }
}
