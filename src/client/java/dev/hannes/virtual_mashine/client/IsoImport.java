package dev.hannes.virtual_mashine.client;

import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.network.PcActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Copies ISO files dropped onto a PC screen into the ISO folder. */
public final class IsoImport {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);

    private IsoImport() {
    }

    public static void importDropped(List<Path> files, String isoFolder, UUID pcId) {
        List<Path> isos = files.stream()
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".iso"))
                .toList();
        if (isos.isEmpty() || isoFolder.isEmpty()) {
            toast(Component.translatable("virtual_mashine.import.not_iso"), null);
            return;
        }
        Path target = Path.of(isoFolder);
        Thread thread = new Thread(() -> {
            for (Path iso : isos) {
                String name = iso.getFileName().toString();
                toast(Component.translatable("virtual_mashine.import.copying"), Component.literal(name));
                try {
                    Files.createDirectories(target);
                    Files.copy(iso, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    toast(Component.translatable("virtual_mashine.import.done"), Component.literal(name));
                } catch (IOException e) {
                    Virtual_mashine.LOGGER.warn("Could not copy {}", iso, e);
                    toast(Component.translatable("virtual_mashine.import.failed"), Component.literal(name));
                }
            }
            Minecraft.getInstance().execute(() -> ClientPcState.send(pcId, PcActionPayload.REFRESH));
        }, "virtual_mashine-iso-import");
        thread.setDaemon(true);
        thread.start();
    }

    /** ISOs live on the computer hosting the world; a joined friend cannot add files there. */
    public static void hostOnly() {
        toast(Component.translatable("virtual_mashine.import.host_only"), null);
    }

    private static void toast(Component title, Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> SystemToast.addOrUpdate(minecraft.getToastManager(), TOAST, title, message));
    }
}
