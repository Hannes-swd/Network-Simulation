package dev.hannes.virtual_mashine.client;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.client.net.CableRenderer;
import dev.hannes.virtual_mashine.client.net.DeviceLedRenderer;
import dev.hannes.virtual_mashine.client.screen.AccessPointScreen;
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
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
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
