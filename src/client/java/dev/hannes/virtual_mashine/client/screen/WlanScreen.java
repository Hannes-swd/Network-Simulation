package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.network.NetPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** A laptop's WLAN menu, like the network list in the system tray. */
public class WlanScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFA0A0A0;
    private static final int GREEN = 0xFF7CE38B;
    private static final int YELLOW = 0xFFFFD866;
    private static final int ROW = 20;
    private static final int MAX_ROWS = 6;

    private NetPayloads.WlanView view;
    private String selected = "";
    private EditBox passwordBox;
    private Button connectButton;
    private Button forgetButton;
    private int ticks;
    private int listX;
    private int listY;
    private int listW;

    public WlanScreen(NetPayloads.WlanView view) {
        super(Component.translatable("virtual_mashine.wlan.title"));
        this.view = view;
    }

    public UUID deviceId() {
        return view.device();
    }

    public void update(NetPayloads.WlanView newView) {
        this.view = newView;
        if (!newView.message().isEmpty()) {
            message = newView.message();
            messageAt = System.currentTimeMillis();
        }
        updateButtons();
    }

    private String message = "";
    private long messageAt;

    @Override
    protected void init() {
        listW = Math.min(300, width - 20);
        listX = (width - listW) / 2;
        listY = 50;
        int bottom = listY + MAX_ROWS * ROW + 8;
        passwordBox = new EditBox(font, listX, bottom, listW - 104, 20, Component.translatable("virtual_mashine.wlan.password"));
        passwordBox.setMaxLength(63);
        passwordBox.setHint(Component.translatable("virtual_mashine.wlan.password"));
        addRenderableWidget(passwordBox);
        connectButton = addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.wlan.connect"), b -> connect())
                .bounds(listX + listW - 100, bottom, 100, 20).build());
        forgetButton = addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.wlan.forget"), b -> send(NetPayloads.WlanAction.FORGET, "", ""))
                .bounds(listX, bottom + 26, (listW - 4) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(listX + (listW + 4) / 2, bottom + 26, (listW - 4) / 2, 20).build());
        updateButtons();
    }

    private void updateButtons() {
        if (connectButton == null) {
            return;
        }
        connectButton.active = !selected.isEmpty() && view.powered();
        forgetButton.active = !view.remembered().isEmpty();
    }

    private void connect() {
        send(NetPayloads.WlanAction.CONNECT, selected, passwordBox.getValue());
    }

    private void send(String action, String ssid, String password) {
        if (ClientPlayNetworking.canSend(NetPayloads.WlanAction.TYPE)) {
            ClientPlayNetworking.send(new NetPayloads.WlanAction(view.device(), action, ssid, password));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % 20 == 0) {
            send(NetPayloads.WlanAction.SCAN, "", "");
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int i = 0; i < Math.min(MAX_ROWS, view.networks().size()); i++) {
            int y = listY + i * ROW;
            if (event.x() >= listX && event.x() < listX + listW && event.y() >= y && event.y() < y + ROW - 2) {
                NetPayloads.WlanNetwork network = view.networks().get(i);
                selected = network.ssid();
                if (!network.secured()) {
                    passwordBox.setValue("");
                }
                updateButtons();
                if (doubleClick && !network.secured()) {
                    connect();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, Component.translatable("virtual_mashine.wlan.heading", view.deviceName()), width / 2, 14, WHITE);
        Component status;
        int statusColor;
        if (!view.powered()) {
            status = Component.translatable("virtual_mashine.wlan.status.off");
            statusColor = GRAY;
        } else if (!view.connected().isEmpty()) {
            status = Component.translatable("virtual_mashine.wlan.status.connected", view.connected(), view.mbit());
            statusColor = GREEN;
        } else if (!view.remembered().isEmpty()) {
            status = Component.translatable("virtual_mashine.wlan.status.searching", view.remembered());
            statusColor = YELLOW;
        } else {
            status = Component.translatable("virtual_mashine.wlan.status.none");
            statusColor = GRAY;
        }
        graphics.centeredText(font, status, width / 2, 28, statusColor);

        if (view.networks().isEmpty()) {
            graphics.centeredText(font, Component.translatable("virtual_mashine.wlan.empty"), width / 2, listY + 20, GRAY);
        }
        for (int i = 0; i < Math.min(MAX_ROWS, view.networks().size()); i++) {
            NetPayloads.WlanNetwork network = view.networks().get(i);
            int y = listY + i * ROW;
            boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW - 2;
            int background = network.ssid().equals(selected) ? 0xFF3A5A80 : hover ? 0xFF3A3D42 : 0xFF2B2D31;
            graphics.fill(listX, y, listX + listW, y + ROW - 2, background);
            bars(graphics, listX + 6, y + 4, network.dbm());
            graphics.text(font, network.ssid(), listX + 30, y + 5, network.connected() ? GREEN : WHITE, false);
            graphics.text(font, network.secured() ? network.dbm() + " dBm  WPA2" : network.dbm() + " dBm",
                    listX + listW - 6 - font.width(network.secured() ? network.dbm() + " dBm  WPA2" : network.dbm() + " dBm"), y + 5, GRAY, false);
        }
        if (!message.isEmpty() && System.currentTimeMillis() - messageAt < 5000) {
            graphics.centeredText(font, Component.translatable(message), width / 2, listY + MAX_ROWS * ROW + 82, YELLOW);
        }
    }

    /** Signal strength as four bars. */
    private static void bars(GuiGraphicsExtractor graphics, int x, int y, int dbm) {
        int strength = dbm >= -55 ? 4 : dbm >= -65 ? 3 : dbm >= -72 ? 2 : dbm >= -80 ? 1 : 0;
        for (int i = 0; i < 4; i++) {
            int h = 3 + i * 3;
            graphics.fill(x + i * 4, y + 12 - h, x + i * 4 + 3, y + 12, i < strength ? 0xFF7CE38B : 0xFF55585E);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
