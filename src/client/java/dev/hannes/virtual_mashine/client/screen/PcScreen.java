package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.client.ClientPcState;
import dev.hannes.virtual_mashine.client.IsoImport;
import dev.hannes.virtual_mashine.network.PcInfoPayload;
import dev.hannes.virtual_mashine.vm.VmConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Shared parts of the PC control panel and the PC display. */
public abstract class PcScreen extends Screen {
    protected static final int WHITE = 0xFFFFFFFF;
    protected static final int GRAY = 0xFFA0A0A0;
    protected static final int YELLOW = 0xFFFFD866;
    protected static final int RED = 0xFFFF6B6B;
    protected static final int GREEN = 0xFF7CE38B;

    protected PcInfoPayload info;
    protected VmConfig serverConfig;

    protected PcScreen(PcInfoPayload info) {
        super(Component.translatable("virtual_mashine.screen.title"));
        this.info = info;
        this.serverConfig = VmConfig.fromJson(info.configJson());
    }

    public UUID pcId() {
        return info.pcId();
    }

    public void onInfo(PcInfoPayload newInfo) {
        this.info = newInfo;
        this.serverConfig = VmConfig.fromJson(newInfo.configJson());
    }

    public static boolean isRunning(PcInfoPayload info) {
        return "RUNNING".equals(info.state());
    }

    /** True when this game also runs the world's server, i.e. the virtual machines run on this computer. */
    protected boolean isHost() {
        return minecraft.hasSingleplayerServer();
    }

    protected boolean isOff() {
        return "OFF".equals(info.state());
    }

    protected void send(String action) {
        ClientPcState.send(pcId(), action);
    }

    protected Component stateText() {
        String state = info.state().toLowerCase(Locale.ROOT);
        if (isOff() && serverConfig.frozen) {
            state = "frozen";
        }
        return Component.translatable("virtual_mashine.state." + state);
    }

    protected int stateColor() {
        return switch (info.state()) {
            case "RUNNING" -> GREEN;
            case "OFF" -> serverConfig.frozen ? YELLOW : GRAY;
            default -> YELLOW;
        };
    }

    /** The last message of the machine (errors, hints), or null if there is none. */
    protected Component messageText() {
        if (!info.qemuFound()) {
            return Component.translatable("virtual_mashine.msg.qemu_missing");
        }
        if (info.messageKey().isEmpty()) {
            return null;
        }
        Component text = Component.translatable(info.messageKey());
        if (!info.messageDetail().isBlank()) {
            text = Component.empty().append(text).append(" ").append(Component.literal(info.messageDetail()));
        }
        return text;
    }

    protected boolean messageIsError() {
        String key = info.messageKey();
        return !info.qemuFound() || key.endsWith("failed") || key.endsWith("missing") || key.endsWith("crashed") || key.endsWith("unsupported");
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (isHost()) {
            IsoImport.importDropped(files, info.isoFolder(), pcId());
        } else {
            IsoImport.hostOnly();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
