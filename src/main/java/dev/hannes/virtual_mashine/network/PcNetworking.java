package dev.hannes.virtual_mashine.network;

import dev.hannes.virtual_mashine.vm.VmManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class PcNetworking {
    private PcNetworking() {
    }

    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(PcInfoPayload.TYPE, PcInfoPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PcActionPayload.TYPE, PcActionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TunnelPayloads.Open.TYPE, TunnelPayloads.Open.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TunnelPayloads.Data.TYPE, TunnelPayloads.Data.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TunnelPayloads.Close.TYPE, TunnelPayloads.Close.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TunnelPayloads.Data.TYPE, TunnelPayloads.Data.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TunnelPayloads.Close.TYPE, TunnelPayloads.Close.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NetPayloads.PortView.TYPE, NetPayloads.PortView.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NetPayloads.CableSync.TYPE, NetPayloads.CableSync.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NetPayloads.HeldCable.TYPE, NetPayloads.HeldCable.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(NetPayloads.PortAction.TYPE, NetPayloads.PortAction.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NetPayloads.WlanView.TYPE, NetPayloads.WlanView.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NetPayloads.ApView.TYPE, NetPayloads.ApView.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(NetPayloads.WlanAction.TYPE, NetPayloads.WlanAction.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(NetPayloads.ApAction.TYPE, NetPayloads.ApAction.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(NetPayloads.WlanAction.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().handleWlanAction(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(NetPayloads.ApAction.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().handleApAction(context.player(), payload);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(NetPayloads.PortAction.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().handlePortAction(context.player(), payload);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(PcActionPayload.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.handleAction(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(TunnelPayloads.Open.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.openTunnel(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(TunnelPayloads.Data.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.tunnelData(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(TunnelPayloads.Close.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.closeTunnel(context.player(), payload);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.onPlayerLeft(handler.getPlayer().getUUID());
            }
        });
    }
}
