package dev.hannes.virtual_mashine.client;

import dev.hannes.virtual_mashine.client.screen.PcControlScreen;
import dev.hannes.virtual_mashine.client.screen.PcDisplayScreen;
import dev.hannes.virtual_mashine.client.screen.PcScreen;
import dev.hannes.virtual_mashine.network.PcActionPayload;
import dev.hannes.virtual_mashine.network.PcInfoPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/** Routes machine updates from the server to the open PC screen. */
public final class ClientPcState {
    private ClientPcState() {
    }

    public static void onInfo(PcInfoPayload info, Minecraft minecraft) {
        if (minecraft.screen instanceof PcScreen screen && screen.pcId().equals(info.pcId())) {
            screen.onInfo(info);
            return;
        }
        if (info.open()) {
            minecraft.setScreen(PcScreen.isRunning(info) ? new PcDisplayScreen(info) : new PcControlScreen(info));
        }
    }

    public static void send(UUID pcId, String action) {
        send(pcId, action, "");
    }

    public static void send(UUID pcId, String action, String argument) {
        if (ClientPlayNetworking.canSend(PcActionPayload.TYPE)) {
            ClientPlayNetworking.send(new PcActionPayload(pcId, action, argument));
        }
    }
}
