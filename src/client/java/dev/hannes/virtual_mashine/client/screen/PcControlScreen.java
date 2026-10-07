package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.client.ClientPcState;
import dev.hannes.virtual_mashine.network.PcActionPayload;
import dev.hannes.virtual_mashine.network.PcInfoPayload;
import dev.hannes.virtual_mashine.vm.VmConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/** The PC's settings and power buttons, opened when the machine is off (or via the display toolbar). */
public class PcControlScreen extends PcScreen {
    private static final int PANEL_WIDTH = 320;
    private static final int ROW = 22;
    private static final int[] DISK_SIZES = {2, 4, 8, 16, 20, 25, 32, 40, 50, 64, 80, 100, 128, 160, 200, 256, 320, 400, 512};

    private VmConfig config;
    private boolean dirty;
    private int ticksSinceSend;
    private List<String> builtIsoList = List.of();
    private List<String> builtImageList = List.of();
    private boolean wantsDisplay;
    private boolean sawStarting;
    private boolean syncing;

    private EditBox nameBox;
    private CycleButton<String> isoButton;
    private ValueSlider ramSlider;
    private ValueSlider cpuSlider;
    private ValueSlider diskSlider;
    private CycleButton<Boolean> bootButton;
    private CycleButton<Boolean> accelButton;
    private CycleButton<Boolean> soundButton;
    private CycleButton<String> networkButton;
    private CycleButton<Boolean> autoStartButton;
    private CycleButton<String> nicButton;
    private Button startButton;
    private Button displayButton;
    private Button shutdownButton;
    private Button powerOffButton;
    private Button resetButton;
    private Button freezeButton;
    private Button discardButton;
    private CycleButton<String> imageButton;
    private Button diskActionButton;

    private int left;
    private int top;
    private int messageY;

    public PcControlScreen(PcInfoPayload info) {
        super(info);
        this.config = serverConfig.copy();
    }

    @Override
    protected void init() {
        left = (width - PANEL_WIDTH) / 2;
        top = Math.max(4, (height - 254) / 2);
        int half = (PANEL_WIDTH - 6) / 2;
        int right = left + half + 6;
        int y = top + 18;

        nameBox = new EditBox(font, left + 1, y + 1, half - 2, 18, Component.translatable("virtual_mashine.option.name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(config.name);
        nameBox.setResponder(value -> {
            config.name = value;
            markDirty();
        });
        nameBox.setTooltip(Tooltip.create(Component.translatable("virtual_mashine.option.name")));
        addRenderableWidget(nameBox);

        List<String> isoValues = new ArrayList<>();
        isoValues.add("");
        isoValues.addAll(info.isos());
        if (!config.iso.isEmpty() && !isoValues.contains(config.iso)) {
            isoValues.add(config.iso); // Inserted but the file is gone; keep showing it.
        }
        builtIsoList = List.copyOf(info.isos());
        isoButton = CycleButton.<String>builder(iso -> iso.isEmpty() ? Component.translatable("virtual_mashine.option.iso.none") : Component.literal(iso), config.iso)
                .withValues(isoValues)
                .withTooltip(iso -> Tooltip.create(Component.translatable("virtual_mashine.option.iso.tooltip")))
                .create(right, y, half, 20, Component.translatable("virtual_mashine.option.iso"), (button, iso) -> {
                    config.iso = iso;
                    markDirty();
                });
        addRenderableWidget(isoButton);
        y += ROW;

        int[] ramValues = IntStream.iterate(256, v -> v <= info.hostMaxRamMb(), v -> v + 256).toArray();
        ramSlider = new ValueSlider(left, y, half, 20, ramValues, config.ramMb,
                v -> Component.translatable("virtual_mashine.option.ram", formatRam(v)), v -> {
            config.ramMb = v;
            markDirty();
        });
        addRenderableWidget(ramSlider);
        int[] cpuValues = IntStream.rangeClosed(1, Math.max(1, info.hostCpus())).toArray();
        cpuSlider = new ValueSlider(right, y, half, 20, cpuValues, config.cpus,
                v -> Component.translatable("virtual_mashine.option.cpus", v), v -> {
            config.cpus = v;
            markDirty();
        });
        addRenderableWidget(cpuSlider);
        y += ROW;

        diskSlider = new ValueSlider(left, y, half, 20, DISK_SIZES, config.diskGb,
                v -> Component.translatable("virtual_mashine.option.disk", v), v -> {
            config.diskGb = v;
            markDirty();
        });
        addRenderableWidget(diskSlider);
        bootButton = CycleButton.booleanBuilder(Component.translatable("virtual_mashine.option.boot.cd"),
                        Component.translatable("virtual_mashine.option.boot.disk"), config.bootFromCd)
                .withTooltip(v -> Tooltip.create(Component.translatable("virtual_mashine.option.boot.tooltip")))
                .create(right, y, half, 20, Component.translatable("virtual_mashine.option.boot"), (button, v) -> {
                    config.bootFromCd = v;
                    markDirty();
                });
        addRenderableWidget(bootButton);
        y += ROW;

        accelButton = CycleButton.onOffBuilder(config.acceleration)
                .withTooltip(v -> Tooltip.create(Component.translatable("virtual_mashine.option.acceleration.tooltip")))
                .create(left, y, half, 20, Component.translatable("virtual_mashine.option.acceleration"), (button, v) -> {
                    config.acceleration = v;
                    markDirty();
                });
        addRenderableWidget(accelButton);
        soundButton = CycleButton.onOffBuilder(config.sound)
                .create(right, y, half, 20, Component.translatable("virtual_mashine.option.sound"), (button, v) -> {
                    config.sound = v;
                    markDirty();
                });
        addRenderableWidget(soundButton);
        y += ROW;

        networkButton = CycleButton.<String>builder(mode -> Component.translatable("virtual_mashine.option.network." + mode), config.netMode)
                .withValues(VmConfig.NET_CABLE, VmConfig.NET_DIRECT, VmConfig.NET_OFF)
                .withTooltip(mode -> Tooltip.create(Component.translatable("virtual_mashine.option.network." + mode + ".tooltip")))
                .create(left, y, half, 20, Component.translatable("virtual_mashine.option.network"), (button, mode) -> {
                    config.netMode = mode;
                    markDirty();
                });
        addRenderableWidget(networkButton);
        int quarterWidth = (half - 4) / 2;
        Button isoFolderButton = addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.button.iso_folder_short"), b -> openIsoFolder())
                .bounds(right, y, quarterWidth, 20)
                .tooltip(Tooltip.create(Component.translatable(isHost()
                        ? "virtual_mashine.button.iso_folder.tooltip" : "virtual_mashine.button.iso_folder.host_only")))
                .build());
        isoFolderButton.active = isHost();
        boolean laptop = "laptop".equals(config.type);
        addRenderableWidget(Button.builder(Component.translatable(laptop ? "virtual_mashine.button.wlan" : "virtual_mashine.button.ports"), b -> openPorts())
                .bounds(right + quarterWidth + 4, y, half - quarterWidth - 4, 20)
                .tooltip(Tooltip.create(Component.translatable("virtual_mashine.button.ports.tooltip")))
                .build());
        y += ROW;

        autoStartButton = CycleButton.onOffBuilder(config.autoStart)
                .withTooltip(v -> Tooltip.create(Component.translatable("virtual_mashine.option.autostart.tooltip")))
                .create(left, y, half, 20, Component.translatable("virtual_mashine.option.autostart"), (button, v) -> {
                    config.autoStart = v;
                    markDirty();
                });
        addRenderableWidget(autoStartButton);
        nicButton = CycleButton.<String>builder(Component::literal, config.nicModel)
                .withValues(VmConfig.NIC_MODELS)
                .withTooltip(v -> Tooltip.create(Component.translatable("virtual_mashine.option.nic.tooltip")))
                .create(right, y, half, 20, Component.translatable("virtual_mashine.option.nic"), (button, v) -> {
                    config.nicModel = v;
                    markDirty();
                });
        addRenderableWidget(nicButton);
        y += ROW;

        // Ready-made systems (OpenWrt, vJunos, cloud images) instead of installing from an ISO.
        List<String> imageValues = new ArrayList<>();
        imageValues.add("");
        imageValues.addAll(info.images());
        if (!config.diskImage.isEmpty() && !imageValues.contains(config.diskImage)) {
            imageValues.add(config.diskImage);
        }
        builtImageList = List.copyOf(info.images());
        imageButton = CycleButton.<String>builder(image -> info.diskExists()
                        ? Component.translatable("virtual_mashine.option.image.created")
                        : image.isEmpty() ? Component.translatable("virtual_mashine.option.image.none") : Component.literal(image), config.diskImage)
                .withValues(imageValues)
                .withTooltip(image -> Tooltip.create(Component.translatable("virtual_mashine.option.image.tooltip")))
                .create(left, y, half, 20, Component.translatable("virtual_mashine.option.image"), (button, image) -> {
                    config.diskImage = image;
                    markDirty();
                });
        addRenderableWidget(imageButton);
        diskActionButton = addRenderableWidget(Button.builder(Component.empty(), b -> diskAction())
                .bounds(right, y, half, 20).build());
        y += ROW;

        messageY = y + 2;
        y += 34;

        // The middle-right column is wider: "Herunterfahren" is the longest label.
        int[] columns = {72, 72, 92, 72};
        int[] columnX = new int[4];
        for (int i = 0, cx = left; i < 4; cx += columns[i] + 4, i++) {
            columnX[i] = cx;
        }
        startButton = addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.button.start"), b -> start())
                .bounds(columnX[0], y, columns[0], 20).build());
        displayButton = addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.button.display"), b -> openDisplay())
                .bounds(columnX[1], y, columns[1], 20).build());
        shutdownButton = addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.button.shutdown"), b -> send(PcActionPayload.SHUTDOWN))
                .bounds(columnX[2], y, columns[2], 20)
                .tooltip(Tooltip.create(Component.translatable("virtual_mashine.button.shutdown.tooltip"))).build());
        powerOffButton = addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.button.power_off"), b -> send(PcActionPayload.POWER_OFF))
                .bounds(columnX[3], y, columns[3], 20)
                .tooltip(Tooltip.create(Component.translatable("virtual_mashine.button.power_off.tooltip"))).build());
        y += ROW;

        resetButton = addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.button.reset"), b -> send(PcActionPayload.RESET))
                .bounds(columnX[0], y, columns[0], 20).build());
        freezeButton = addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.button.freeze"), b -> send(PcActionPayload.FREEZE))
                .bounds(columnX[1], y, columns[1], 20).build());
        discardButton = addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.button.discard"), b -> send(PcActionPayload.DISCARD_STATE))
                .bounds(columnX[2], y, columns[2], 20)
                .tooltip(Tooltip.create(Component.translatable("virtual_mashine.button.discard.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(columnX[3], y, columns[3], 20).build());

        updateWidgetStates();
    }

    @Override
    public void onInfo(PcInfoPayload newInfo) {
        super.onInfo(newInfo);
        if (dirty) {
            config.frozen = serverConfig.frozen;
        } else {
            config = serverConfig.copy();
        }
        boolean isoChoicesChanged = !Objects.equals(builtIsoList, newInfo.isos()) || !Objects.equals(builtImageList, newInfo.images());
        if (isoChoicesChanged) {
            rebuildWidgets();
        } else {
            if (!dirty) {
                syncWidgetValues();
            }
            updateWidgetStates();
        }
        if (wantsDisplay) {
            if (isRunning(newInfo)) {
                wantsDisplay = false;
                minecraft.setScreen(new PcDisplayScreen(newInfo));
            } else if (!isOff()) {
                sawStarting = true;
            } else if (sawStarting) {
                wantsDisplay = false; // The start failed; stay here and show why.
            }
        }
    }

    /** Shows values the server confirmed without rebuilding (which would steal the text field focus). */
    private void syncWidgetValues() {
        syncing = true;
        try {
            if (!nameBox.isFocused() && !nameBox.getValue().equals(config.name)) {
                nameBox.setValue(config.name);
            }
            isoButton.setValue(config.iso);
            ramSlider.setIntValue(config.ramMb);
            cpuSlider.setIntValue(config.cpus);
            diskSlider.setIntValue(config.diskGb);
            bootButton.setValue(config.bootFromCd);
            accelButton.setValue(config.acceleration);
            soundButton.setValue(config.sound);
            networkButton.setValue(config.netMode);
            autoStartButton.setValue(config.autoStart);
            nicButton.setValue(config.nicModel);
            imageButton.setValue(config.diskImage);
        } finally {
            syncing = false;
        }
    }

    private void start() {
        flushConfig();
        wantsDisplay = true;
        sawStarting = false;
        send(PcActionPayload.START);
    }

    private void openDisplay() {
        flushConfig();
        minecraft.setScreen(new PcDisplayScreen(info));
    }

    /** Empty disk: fetch OpenWrt. Existing disk: wipe it (after asking). */
    private void diskAction() {
        flushConfig();
        if (!info.diskExists()) {
            ClientPcState.send(pcId(), PcActionPayload.DOWNLOAD_OPENWRT);
            return;
        }
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                ClientPcState.send(pcId(), PcActionPayload.RESET_DISK);
            }
            minecraft.setScreen(this);
        }, Component.translatable("virtual_mashine.reset_disk.title"), Component.translatable("virtual_mashine.reset_disk.message", config.name)));
    }

    private void openPorts() {
        flushConfig();
        ClientPcState.send(pcId(), PcActionPayload.PORTS);
    }

    private void openIsoFolder() {
        if (!info.isoFolder().isEmpty()) {
            Util.getPlatform().openPath(Path.of(info.isoFolder()));
        }
    }

    private void updateWidgetStates() {
        boolean off = isOff();
        boolean running = isRunning(info);
        boolean frozen = serverConfig.frozen;
        boolean hardwareEditable = off && !frozen;

        ramSlider.active = hardwareEditable;
        cpuSlider.active = hardwareEditable;
        accelButton.active = hardwareEditable;
        soundButton.active = hardwareEditable;
        networkButton.active = hardwareEditable;
        nicButton.active = hardwareEditable;
        diskSlider.active = off && !info.diskExists();
        imageButton.active = off && !info.diskExists() && !frozen;
        diskActionButton.active = off && !frozen && info.qemuFound() && isHost();
        diskActionButton.setMessage(Component.translatable(info.diskExists()
                ? "virtual_mashine.button.reset_disk" : "virtual_mashine.button.download_openwrt"));
        diskActionButton.setTooltip(Tooltip.create(Component.translatable(info.diskExists()
                ? "virtual_mashine.button.reset_disk.tooltip" : "virtual_mashine.button.download_openwrt.tooltip")));
        if (info.diskExists()) {
            diskSlider.setTooltip(Tooltip.create(Component.translatable("virtual_mashine.option.disk.created")));
        }
        bootButton.active = !frozen;
        isoButton.active = !frozen && !"SAVING".equals(info.state());

        startButton.active = off && info.qemuFound();
        startButton.setMessage(Component.translatable(frozen ? "virtual_mashine.button.resume" : "virtual_mashine.button.start"));
        displayButton.active = running;
        shutdownButton.active = running;
        powerOffButton.active = running || "STOPPING".equals(info.state());
        resetButton.active = running;
        freezeButton.active = running && info.canFreeze();
        freezeButton.setTooltip(Tooltip.create(Component.translatable(info.canFreeze()
                ? "virtual_mashine.button.freeze.tooltip" : "virtual_mashine.button.freeze.unsupported")));
        discardButton.active = off && frozen;
    }

    private void markDirty() {
        if (!syncing) {
            dirty = true;
        }
    }

    private void flushConfig() {
        if (dirty) {
            dirty = false;
            ticksSinceSend = 0;
            ClientPcState.send(pcId(), PcActionPayload.CONFIG, config.toJson());
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (dirty && ++ticksSinceSend >= 10) {
            flushConfig();
        }
    }

    @Override
    public void removed() {
        flushConfig();
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        Component title = Component.translatable("virtual_mashine.screen.control", config.name);
        graphics.text(font, title, left, top + 4, WHITE);
        Component state = stateText();
        graphics.text(font, state, left + PANEL_WIDTH - font.width(state), top + 4, stateColor());

        Component message = messageText();
        int y = messageY;
        List<FormattedCharSequence> lines = new ArrayList<>();
        if (message != null) {
            lines.addAll(font.split(message, PANEL_WIDTH));
        }
        if (lines.isEmpty() && ramTooHigh()) {
            Component warning = Component.translatable("virtual_mashine.hint.ram_low", formatRam(info.hostFreeRamMb()), formatRam(config.ramMb));
            lines.addAll(font.split(warning, PANEL_WIDTH));
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                graphics.text(font, lines.get(i), left, y + i * 10, YELLOW);
            }
            return;
        }
        if (lines.isEmpty()) {
            Component hint = serverConfig.frozen
                    ? Component.translatable("virtual_mashine.hint.frozen")
                    : info.canFreeze()
                    ? Component.translatable("virtual_mashine.hint.freezable")
                    : Component.translatable("virtual_mashine.hint.not_freezable");
            lines.addAll(font.split(hint, PANEL_WIDTH));
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                graphics.text(font, lines.get(i), left, y + i * 10, GRAY);
            }
            return;
        }
        int color = messageIsError() ? RED : YELLOW;
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            graphics.text(font, lines.get(i), left, y + i * 10, color);
        }
    }

    /** Starting with more RAM than the host has free makes Windows swap and everything crawl. */
    private boolean ramTooHigh() {
        return isOff() && !serverConfig.frozen && info.hostFreeRamMb() >= 0 && config.ramMb > info.hostFreeRamMb();
    }

    private static String formatRam(int mb) {
        return mb % 1024 == 0 ? (mb / 1024) + " GB" : mb >= 1024 ? String.format("%.2f GB", mb / 1024.0) : mb + " MB";
    }
}
