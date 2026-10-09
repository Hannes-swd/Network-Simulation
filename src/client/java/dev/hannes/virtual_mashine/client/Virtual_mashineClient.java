package dev.hannes.virtual_mashine.client;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.client.net.CableRenderer;
import dev.hannes.virtual_mashine.client.net.DeviceLedRenderer;
import dev.hannes.virtual_mashine.client.display.DisplayManager;
import dev.hannes.virtual_mashine.client.media.CameraCapture;
import dev.hannes.virtual_mashine.client.media.MicCapture;
import dev.hannes.virtual_mashine.client.media.VmSound;
import dev.hannes.virtual_mashine.network.MediaPayloads;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.client.screen.AccessPointScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import dev.hannes.virtual_mashine.client.screen.IoModuleScreen;
import dev.hannes.virtual_mashine.client.screen.PortScreen;
import dev.hannes.virtual_mashine.client.screen.WlanScreen;
import dev.hannes.virtual_mashine.client.vnc.TunnelTransport;
import dev.hannes.virtual_mashine.network.NetPayloads;
import dev.hannes.virtual_mashine.network.PcInfoPayload;
import dev.hannes.virtual_mashine.network.TunnelPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class Virtual_mashineClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(PcInfoPayload.TYPE,
                (payload, context) -> ClientPcState.onInfo(payload, context.client()));
        ClientPlayNetworking.registerGlobalReceiver(TunnelPayloads.Data.TYPE, (payload, context) -> TunnelTransport.onData(payload));
        ClientPlayNetworking.registerGlobalReceiver(TunnelPayloads.Close.TYPE, (payload, context) -> TunnelTransport.onClose(payload));
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.PortView.TYPE, (payload, context) -> onPortView(payload, context.client()));
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.CableSync.TYPE, (payload, context) -> CableRenderer.setCables(payload.cables()));
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.HeldCable.TYPE, (payload, context) -> CableRenderer.setHeld(payload));
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.WlanView.TYPE, (payload, context) -> {
            Minecraft minecraft = context.client();
            if (minecraft.screen instanceof WlanScreen screen && screen.deviceId().equals(payload.device())) {
                screen.update(payload);
            } else {
                minecraft.setScreen(new WlanScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.ApView.TYPE, (payload, context) -> {
            Minecraft minecraft = context.client();
            if (minecraft.screen instanceof AccessPointScreen screen && screen.deviceId().equals(payload.device())) {
                screen.update(payload);
            } else {
                minecraft.setScreen(new AccessPointScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.IoView.TYPE, (payload, context) -> {
            Minecraft minecraft = context.client();
            if (minecraft.screen instanceof IoModuleScreen screen && screen.deviceId().equals(payload.device())) {
                screen.update(payload);
            } else {
                minecraft.setScreen(new IoModuleScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(MediaPayloads.VmAudio.TYPE, (payload, context) -> VmSound.onAudio(payload));
        ClientPlayNetworking.registerGlobalReceiver(MediaPayloads.MicState.TYPE, (payload, context) -> MicCapture.onState(payload));
        ClientPlayNetworking.registerGlobalReceiver(MediaPayloads.CameraJob.TYPE, (payload, context) -> CameraCapture.onJob(payload));
        ClientTickEvents.END_CLIENT_TICK.register(DisplayManager::tick);
        ClientTickEvents.END_CLIENT_TICK.register(VmSound::tick);
        ClientTickEvents.END_CLIENT_TICK.register(MicCapture::tick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Virtual_mashine.id("microphone"), MicCapture::hud);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DisplayManager.disconnectAll();
            VmSound.stopAll();
            MicCapture.reset();
            CameraCapture.reset();
            TunnelTransport.closeAll();
            CableRenderer.clear();
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(CableRenderer::render);
        BlockEntityRenderers.register(ModRegistry.DEVICE_BLOCK_ENTITY, DeviceLedRenderer::new);
    }

    private static void onPortView(NetPayloads.PortView view, Minecraft minecraft) {
        if (minecraft.screen instanceof PortScreen screen && screen.deviceId().equals(view.device())) {
            screen.update(view);
        } else if (view.open()) {
            minecraft.setScreen(new PortScreen(view));
        }
    }
}
