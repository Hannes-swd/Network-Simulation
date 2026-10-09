package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.network.NetPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Panel of a network I/O module: its address and the six redstone channels. The same can be done
 * over the network (Modbus TCP, web page, JSON API); this panel is the module's local display.
 */
public class IoModuleScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFA0A0A0;
    private static final int GREEN = 0xFF7CE38B;
    private static final int RED = 0xFFFF6B6B;
    private static final int YELLOW = 0xFFFFD866;
    private static final int PANEL = 320;
    private static final int ROW = 20;
    private static final String[] SIDES = {"front", "back", "left", "right", "top", "bottom"};

    private NetPayloads.IoView view;
    private String message = "";
    private long messageAt;
    private int ticks;
    private int left;
    private int channelsTop;
    private boolean networkEdited;

    private EditBox nameBox;
    private EditBox ipBox;
    private EditBox maskBox;
    private EditBox gatewayBox;
    private CycleButton<Boolean> dhcpButton;
    private final Button[] modeButtons = new Button[6];
    private final Button[][] levelButtons = new Button[6][4];

    public IoModuleScreen(NetPayloads.IoView view) {
        super(Component.translatable("virtual_mashine.io.title"));
        this.view = view;
    }

    public UUID deviceId() {
        return view.device();
    }

    public void update(NetPayloads.IoView newView) {
        view = newView;
        if (!newView.message().isEmpty()) {
            message = newView.message();
            messageAt = System.currentTimeMillis();
            networkEdited = false;
        }
        if (!networkEdited && dhcpButton != null) {
            nameBox.setValue(newView.name());
            ipBox.setValue(newView.staticIp());
            maskBox.setValue(newView.staticMask());
            gatewayBox.setValue(newView.staticGateway());
            dhcpButton.setValue(newView.dhcp());
        }
        refreshWidgets();
    }

    @Override
    protected void init() {
        left = (width - PANEL) / 2;
        int y = 46;
        nameBox = box(left, y, 120, Component.translatable("virtual_mashine.io.name"), view.name(), 32);
        dhcpButton = CycleButton.booleanBuilder(Component.translatable("virtual_mashine.io.dhcp"),
                        Component.translatable("virtual_mashine.io.static"), view.dhcp())
                .create(left + 124, y, 112, 20, Component.translatable("virtual_mashine.io.address"), (button, value) -> {
                    networkEdited = true;
                    refreshWidgets();
                });
        addRenderableWidget(dhcpButton);
        addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.io.save"), b -> saveNetwork())
                .bounds(left + 240, y, 80, 20).build());
        y += 24;
        ipBox = box(left, y, 104, Component.translatable("virtual_mashine.io.ip"), view.staticIp(), 15);
        maskBox = box(left + 108, y, 104, Component.translatable("virtual_mashine.io.mask"), view.staticMask(), 15);
        gatewayBox = box(left + 216, y, 104, Component.translatable("virtual_mashine.io.gateway"), view.staticGateway(), 15);

        channelsTop = y + 30;
        for (int ch = 0; ch < 6; ch++) {
            int rowY = channelsTop + ch * ROW;
            int channel = ch;
            modeButtons[ch] = addRenderableWidget(Button.builder(Component.empty(), b -> send(NetPayloads.IoAction.MODE, channel,
                            isOutput(channel) ? 0 : 1))
                    .bounds(left + 70, rowY, 70, 18)
                    .tooltip(Tooltip.create(Component.translatable("virtual_mashine.io.mode.tooltip"))).build());
            String[] labels = {"-", "+", "0", "15"};
            int[] widths = {18, 18, 22, 26};
            int x = left + 236;
            for (int i = 0; i < 4; i++) {
                int which = i;
                levelButtons[ch][i] = addRenderableWidget(Button.builder(Component.literal(labels[i]), b -> {
                    int level = view.levels()[channel];
                    int next = switch (which) {
                        case 0 -> level - 1;
                        case 1 -> level + 1;
                        case 2 -> 0;
                        default -> 15;
                    };
                    send(NetPayloads.IoAction.LEVEL, channel, Math.max(0, Math.min(15, next)));
                }).bounds(x, rowY, widths[i], 18).build());
                x += widths[i] + 2;
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + PANEL / 2 - 50, channelsTop + 6 * ROW + 6, 100, 20).build());
        refreshWidgets();
    }

    private EditBox box(int x, int y, int w, Component hint, String value, int max) {
        EditBox box = new EditBox(font, x, y, w, 20, hint);
        box.setMaxLength(max);
        box.setValue(value);
        box.setHint(hint);
        box.setTooltip(Tooltip.create(hint));
        box.setResponder(text -> networkEdited = true);
        return addRenderableWidget(box);
    }

    private boolean isOutput(int ch) {
        return (view.outputMask() & 1 << ch) != 0;
    }

    private void refreshWidgets() {
        if (dhcpButton == null) {
            return;
        }
        boolean fixed = !dhcpButton.getValue();
        ipBox.setEditable(fixed);
        maskBox.setEditable(fixed);
        gatewayBox.setEditable(fixed);
        for (int ch = 0; ch < 6; ch++) {
            boolean out = isOutput(ch);
            modeButtons[ch].setMessage(Component.translatable(out ? "virtual_mashine.io.mode.output" : "virtual_mashine.io.mode.input"));
            for (Button button : levelButtons[ch]) {
                button.visible = out;
            }
        }
    }

    private void saveNetwork() {
        if (ClientPlayNetworking.canSend(NetPayloads.IoAction.TYPE)) {
            ClientPlayNetworking.send(new NetPayloads.IoAction(view.device(), NetPayloads.IoAction.NETWORK, 0, 0, nameBox.getValue(),
                    dhcpButton.getValue(), ipBox.getValue(), maskBox.getValue(), gatewayBox.getValue()));
        }
    }

    private void send(String action, int channel, int value) {
        if (ClientPlayNetworking.canSend(NetPayloads.IoAction.TYPE)) {
            ClientPlayNetworking.send(NetPayloads.IoAction.simple(view.device(), action, channel, value));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % 10 == 0) {
            send(NetPayloads.IoAction.REFRESH, 0, 0);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, Component.literal(view.deviceName()), width / 2, 8, WHITE);
        Component status;
        int color;
        if (!view.link()) {
            status = Component.translatable("virtual_mashine.io.no_link", view.mac());
            color = RED;
        } else if (view.address().isEmpty()) {
            status = Component.translatable("virtual_mashine.io.no_address", view.mac());
            color = YELLOW;
        } else {
            status = Component.translatable("virtual_mashine.io.status", view.address(),
                    Component.translatable(view.dhcp() ? "virtual_mashine.io.dhcp" : "virtual_mashine.io.static"), view.mac(),
                    view.connections());
            color = GREEN;
        }
        graphics.centeredText(font, status, width / 2, 20, color);
        String ip = view.address().isEmpty() ? "" : view.address().split("/")[0];
        graphics.centeredText(font, ip.isEmpty() ? Component.translatable("virtual_mashine.io.help.noip")
                : Component.translatable("virtual_mashine.io.help", ip), width / 2, 32, GRAY);

        for (int ch = 0; ch < 6; ch++) {
            int rowY = channelsTop + ch * ROW;
            graphics.text(font, Component.translatable("virtual_mashine.io.side." + SIDES[ch]).append(" (" + ch + ")"), left, rowY + 5, WHITE);
            int level = view.levels()[ch];
            int barX = left + 146;
            for (int i = 0; i < 15; i++) {
                int segment = i < level ? (isOutput(ch) ? 0xFFFFA040 : 0xFFE03030) : 0xFF3A3A3A;
                graphics.fill(barX + i * 5, rowY + 4, barX + i * 5 + 4, rowY + 14, segment);
            }
            graphics.text(font, Component.literal(Integer.toString(level)), barX + 78, rowY + 5, level > 0 ? WHITE : GRAY);
        }
        if (!message.isEmpty() && System.currentTimeMillis() - messageAt < 5000) {
            graphics.centeredText(font, Component.translatable(message), width / 2, channelsTop + 6 * ROW + 30, YELLOW);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
