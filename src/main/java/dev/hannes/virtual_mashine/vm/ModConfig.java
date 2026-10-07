package dev.hannes.virtual_mashine.vm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.hannes.virtual_mashine.Virtual_mashine;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Global settings in {@code config/virtual_mashine.json}. */
public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ModConfig instance = new ModConfig();

    /** Folder containing qemu-system-x86_64 (or the executable itself). Empty = search automatically. */
    public String qemuPath = "";
    /** How long a guest gets to shut down by itself when the world closes and freezing is not possible. */
    public int shutdownTimeoutSeconds = 10;

    /**
     * Capped at 12 s: Minecraft reports a hung shutdown after 15 s when the game window is closed.
     */
    public int shutdownWaitSeconds() {
        return Math.max(1, Math.min(12, shutdownTimeoutSeconds));
    }

    public static ModConfig get() {
        return instance;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(Virtual_mashine.MOD_ID + ".json");
    }

    public static void load() {
        Path file = file();
        try {
            if (Files.exists(file)) {
                ModConfig loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ModConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            }
            if (instance.qemuPath == null) {
                instance.qemuPath = "";
            }
            Files.writeString(file, GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Virtual_mashine.LOGGER.warn("Could not read {}, using defaults", file, e);
        }
    }
}
