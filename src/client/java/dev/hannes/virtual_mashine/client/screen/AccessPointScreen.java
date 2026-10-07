package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.network.NetPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** Settings of a WLAN access point: network name, WPA2 key and the connected clients. */
public class AccessPointScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFA0A0A0;
    private static final int YELLOW = 0xFFFFD866;
    private static final int GREEN = 0xFF7CE38B;

    private NetPayloads.ApView view;
    private EditBox ssidBox;
    private EditBox passwordBox;
    private int ticks;
    private int left;
    private int panelW;

    public AccessPointScreen(NetPayloads.ApView view) {
        super(Component.translatable("virtual_mashine.ap.title"));
        this.view = view;
    }

    public UUID deviceId() {
        return view.device();
    }

    public void update(NetPayloads.ApView newView) {
        this.view = newView;
        if (!newView.message().isEmpty()) {
            message = newView.message();
            messageAt = System.currentTimeMillis();
        }
    }

    private String message = "";
    private long messageAt;

    @Override
    protected void init() {
        panelW = Math.min(280, width - 20);
        left = (width - panelW) / 2;
        int y = 46;
        ssidBox = new EditBox(font, left + 90, y, panelW - 90, 20, Component.translatable("virtual_mashine.ap.ssid"));
        ssidBox.setMaxLength(32);
        ssidBox.setValue(view.ssid());
        addRenderableWidget(ssidBox);
        passwordBox = new EditBox(font, left + 90, y + 26, panelW - 90, 20, Component.translatable("virtual_mashine.ap.password"));
        passwordBox.setMaxLength(63);
        passwordBox.setValue(view.password());
        addRenderableWidget(passwordBox);
        addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.ap.save"), b -> send(true))
                .bounds(left, y + 52, (panelW - 4) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + (panelW + 4) / 2, y + 52, (panelW - 4) / 2, 20).build());
    }

    private void send(boolean save) {
        if (ClientPlayNetworking.canSend(NetPayloads.ApAction.TYPE)) {
            ClientPlayNetworking.send(new NetPayloads.ApAction(view.device(), save, ssidBox.getValue(), passwordBox.getValue()));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % 20 == 0) {
            send(false);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, Component.translatable("virtual_mashine.ap.heading", view.deviceName()), width / 2, 16, WHITE);
        graphics.centeredText(font, Component.translatable("virtual_mashine.ap.hint"), width / 2, 30, GRAY);
        graphics.text(font, Component.translatable("virtual_mashine.ap.ssid"), left, 52, WHITE);
        graphics.text(font, Component.translatable("virtual_mashine.ap.password"), left, 78, WHITE);
        int y = 128;
        graphics.text(font, Component.translatable("virtual_mashine.ap.clients", view.clients().size()), left, y, WHITE);
        y += 12;
        if (view.clients().isEmpty()) {
            graphics.text(font, Component.translatable("virtual_mashine.ap.no_clients"), left + 6, y, GRAY);
        }
        for (int i = 0; i < Math.min(8, view.clients().size()); i++) {
            graphics.text(font, view.clients().get(i), left + 6, y + i * 11, GREEN);
        }
        if (!message.isEmpty() && System.currentTimeMillis() - messageAt < 5000) {
            graphics.centeredText(font, Component.translatable(message), width / 2, height - 24, YELLOW);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
