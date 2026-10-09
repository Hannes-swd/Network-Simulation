package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.client.ClientPcState;
import dev.hannes.virtual_mashine.network.PcActionPayload;
import dev.hannes.virtual_mashine.network.PcInfoPayload;
import dev.hannes.virtual_mashine.vm.VmConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.stream.IntStream;

/**
 * Settings of a display block: the page it shows and its size. Using the page happens in the world
 * (look at it and click); this panel is reached by sneaking and right-clicking the block.
 */
public class DisplaySettingsScreen extends PcScreen {
    private static final int PANEL = 300;

    private VmConfig config;
    private EditBox urlBox;
    private ValueSlider widthSlider;
    private ValueSlider heightSlider;
    private Button powerButton;
    private int left;
    private int top;

    public DisplaySettingsScreen(PcInfoPayload info) {
        super(info);
        this.config = serverConfig.copy();
    }

    @Override
    protected void init() {
        left = (width - PANEL) / 2;
        top = Math.max(8, (height - 190) / 2);
        int y = top + 30;
        urlBox = new EditBox(font, left, y, PANEL, 20, Component.translatable("virtual_mashine.display.url"));
        urlBox.setMaxLength(512);
        urlBox.setValue(config.displayUrl);
        urlBox.setHint(Component.literal("http://server.netz.lan/"));
        addRenderableWidget(urlBox);
        y += 26;
        int half = (PANEL - 6) / 2;
        widthSlider = addRenderableWidget(new ValueSlider(left, y, half, 20, IntStream.rangeClosed(1, VmConfig.MAX_DISPLAY_WIDTH).toArray(),
                config.displayWidth, v -> Component.translatable("virtual_mashine.display.width", v), v -> config.displayWidth = v));
        heightSlider = addRenderableWidget(new ValueSlider(left + half + 6, y, half, 20,
                IntStream.rangeClosed(1, VmConfig.MAX_DISPLAY_HEIGHT).toArray(), config.displayHeight,
                v -> Component.translatable("virtual_mashine.display.height", v), v -> config.displayHeight = v));
        y += 26;
        addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.display.apply"), b -> apply())
                .bounds(left, y, half, 20)
                .tooltip(Tooltip.create(Component.translatable("virtual_mashine.display.apply.tooltip"))).build());
        powerButton = addRenderableWidget(Button.builder(Component.empty(), b -> send(isOff() ? PcActionPayload.START : PcActionPayload.POWER_OFF))
                .bounds(left + half + 6, y, half, 20).build());
        y += 24;
        int third = (PANEL - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.button.ports"), b -> send(PcActionPayload.PORTS))
                .bounds(left, y, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.button.display"), b -> {
            if (isRunning(info)) {
                minecraft.setScreen(new PcDisplayScreen(info));
            }
        }).bounds(left + third + 4, y, third, 20).tooltip(Tooltip.create(Component.translatable("virtual_mashine.display.window.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.display.advanced"), b -> minecraft.setScreen(new PcControlScreen(info)))
                .bounds(left + 2 * (third + 4), y, PANEL - 2 * (third + 4), 20).build());
        y += 30;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + PANEL / 2 - 50, y, 100, 20).build());
        updateButtons();
    }

    /** Saves page and size; a running display starts again with them. */
    private void apply() {
        config.displayUrl = urlBox.getValue().strip();
        config.displayWidth = widthSlider.intValue();
        config.displayHeight = heightSlider.intValue();
        ClientPcState.send(pcId(), PcActionPayload.CONFIG, config.toJson());
        if (!isOff()) {
            send(PcActionPayload.RESTART);
        } else {
            send(PcActionPayload.START);
        }
    }

    @Override
    public void onInfo(PcInfoPayload newInfo) {
        super.onInfo(newInfo);
        updateButtons();
    }

    private void updateButtons() {
        if (powerButton != null) {
            powerButton.setMessage(Component.translatable(isOff() ? "virtual_mashine.display.on" : "virtual_mashine.display.off"));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, Component.literal(serverConfig.name), width / 2, top, WHITE);
        graphics.centeredText(font, stateText(), width / 2, top + 12, stateColor());
        graphics.text(font, Component.translatable("virtual_mashine.display.url"), left, top + 21, GRAY);
        Component message = messageText();
        if (message != null) {
            graphics.centeredText(font, message, width / 2, top + 166, messageIsError() ? RED : YELLOW);
        }
        graphics.centeredText(font, Component.translatable("virtual_mashine.display.hint"), width / 2, top + 180, GRAY);
    }
}
