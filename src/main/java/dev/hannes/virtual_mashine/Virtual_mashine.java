package dev.hannes.virtual_mashine;

import dev.hannes.virtual_mashine.network.PcNetworking;
import dev.hannes.virtual_mashine.vm.ModConfig;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Virtual_mashine implements ModInitializer {
    public static final String MOD_ID = "virtual_mashine";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModConfig.load();
        ModRegistry.register();
        PcNetworking.registerCommon();

        ServerLifecycleEvents.SERVER_STARTED.register(VmManager::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(VmManager::onServerTick);
        // Runs before the world is saved, so every running PC gets frozen (or shut down) first.
        ServerLifecycleEvents.SERVER_STOPPING.register(VmManager::onServerStopping);
        Runtime.getRuntime().addShutdownHook(new Thread(VmManager::killAllProcesses, "virtual_mashine-shutdown"));
    }
}
