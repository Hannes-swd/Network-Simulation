package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.device.CableType;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.network.NetPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The sockets of a device, like looking at its front or back panel: green LED = link, amber =
 * traffic. Click a free socket with a cable in hand to plug it in, click a used one to pull the
 * cable out.
 */
public class PortScreen extends Screen {
    private static final int MIN_SOCKET_W = 30;
    private static final int SOCKET_H = 24;
    private static final int GAP = 6;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFA0A0A0;
    private static final int YELLOW = 0xFFFFD866;
    private static final int GREEN = 0xFF7CE38B;

    private NetPayloads.PortView view;
    private int ticks;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private String lastMessage = "";
    private long lastMessageAt;
    /** Opened with the network tester: show speeds and traffic too. */
    private final boolean tester;

    public PortScreen(NetPayloads.PortView view) {
        super(Component.translatable(view.tester() ? "virtual_mashine.ports.tester_title" : "virtual_mashine.ports.title"));
        this.view = view;
        this.tester = view.tester();
        remember(view);
    }

    public UUID deviceId() {
        return view.device();
    }

    public void update(NetPayloads.PortView newView) {
        this.view = newView;
        remember(newView);
    }

    /** Results of a click stay visible for a few seconds, the LED refreshes carry no message. */
    private void remember(NetPayloads.PortView newView) {
        if (!newView.message().isEmpty()) {
            lastMessage = newView.message();
            lastMessageAt = System.currentTimeMillis();
        }
    }

    /** Wide enough for the longest port label ("eth1 WAN", "Console"). */
    private int socketWidth = MIN_SOCKET_W;

    @Override
    protected void init() {
        int ports = view.ports().size();
        socketWidth = MIN_SOCKET_W;
        for (NetPayloads.PortInfo port : view.ports()) {
            socketWidth = Math.max(socketWidth, font.width(port.name()) + 4);
        }
        panelW = Math.max(220, ports * (socketWidth + GAP) + GAP + 20);
        panelH = SOCKET_H + (tester ? 54 : 44);
        panelX = (width - panelW) / 2;
        panelY = height / 2 - panelH / 2 - 10;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 50, panelY + panelH + 52, 100, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % 10 == 0) {
            send(NetPayloads.PortAction.REFRESH, -1);
        }
    }

    private void send(String action, int port) {
        if (ClientPlayNetworking.canSend(NetPayloads.PortAction.TYPE)) {
            ClientPlayNetworking.send(new NetPayloads.PortAction(view.device(), view.pos(), action, port));
        }
    }

    private int socketX(int port) {
        int ports = view.ports().size();
        int rowWidth = ports * socketWidth + (ports - 1) * GAP;
        return panelX + (panelW - rowWidth) / 2 + port * (socketWidth + GAP);
    }

    private int socketY() {
        return panelY + 18;
    }

    private int socketAt(double x, double y) {
        for (int i = 0; i < view.ports().size(); i++) {
            int sx = socketX(i);
            if (x >= sx && x < sx + socketWidth && y >= socketY() && y < socketY() + SOCKET_H) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int port = socketAt(event.x(), event.y());
        if (port >= 0) {
            NetPayloads.PortInfo info = view.ports().get(port);
            send(info.state() == NetPayloads.PortInfo.FREE ? NetPayloads.PortAction.PLUG : NetPayloads.PortAction.UNPLUG, port);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        Component title = Component.translatable(tester ? "virtual_mashine.ports.tester_heading" : "virtual_mashine.ports.heading", view.deviceName());
        graphics.centeredText(font, title, width / 2, panelY - 26, WHITE);
        Component power = Component.translatable(view.powered() ? "virtual_mashine.ports.powered" : "virtual_mashine.ports.unpowered");
        graphics.centeredText(font, power, width / 2, panelY - 14, view.powered() ? GREEN : GRAY);

        // Housing.
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF2B2D31);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF55585E);
        boolean blink = (System.currentTimeMillis() / 120) % 2 == 0;
        for (int i = 0; i < view.ports().size(); i++) {
            drawSocket(graphics, i, view.ports().get(i), blink, mouseX, mouseY);
        }

        int y = panelY + panelH + 8;
        for (FormattedCharSequence line : statusLines()) {
            graphics.centeredText(font, line, width / 2, y, YELLOW);
            y += 10;
        }

        int hovered = socketAt(mouseX, mouseY);
        if (hovered >= 0) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (Component line : tooltip(hovered)) {
                lines.add(line.getVisualOrderText());
            }
            graphics.setTooltipForNextFrame(lines, mouseX, mouseY);
        }
    }

    private void drawSocket(GuiGraphicsExtractor graphics, int index, NetPayloads.PortInfo port, boolean blink, int mouseX, int mouseY) {
        int x = socketX(index);
        int y = socketY();
        boolean hover = socketAt(mouseX, mouseY) == index;
        if (isUsb(index)) {
            drawUsbSocket(graphics, x, y, port, hover);
            return;
        }
        if (isHdmi(index)) {
            drawHdmiSocket(graphics, x, y, port, hover);
            return;
        }
        // Console/COM sockets are light blue, like the rollover cables that go into them.
        int housing = port.console() ? (hover ? 0xFF4F86A8 : 0xFF35607A) : (hover ? 0xFF6A6E75 : 0xFF4A4D52);
        graphics.fill(x, y, x + socketWidth, y + SOCKET_H, housing);
        // LEDs: link (green) and activity (amber).
        graphics.fill(x + 3, y + 2, x + 8, y + 5, port.link() ? 0xFF3DDC5A : 0xFF1E3322);
        graphics.fill(x + socketWidth - 8, y + 2, x + socketWidth - 3, y + 5, port.activity() && blink ? 0xFFFFB12E : 0xFF3A2E1A);
        // RJ45 opening.
        int holeX = x + 6;
        int holeY = y + 8;
        graphics.fill(holeX, holeY, holeX + socketWidth - 12, holeY + 12, 0xFF101114);
        graphics.fill(holeX + 6, holeY + 10, holeX + socketWidth - 18, holeY + 12, 0xFF4A4D52);
        if (port.state() != NetPayloads.PortInfo.FREE) {
            // The plug, in the cable's colour.
            int plug = 0xFF000000 | port.color();
            graphics.fill(holeX + 1, holeY + 1, holeX + socketWidth - 13, holeY + 11, plug);
            graphics.fill(holeX + 4, holeY + 3, holeX + socketWidth - 16, holeY + 5, 0xFFE8E8E8);
        }
        String name = port.name();
        int nameWidth = font.width(name);
        graphics.text(font, name, x + (socketWidth - nameWidth) / 2, y + SOCKET_H + 3, port.link() ? GREEN : GRAY, false);
        if (tester && port.link() && !port.console()) {
            String speed = port.speedMbit() >= 1000 ? (port.speedMbit() / 1000) + "G" : port.speedMbit() + "M";
            graphics.text(font, speed, x + (socketWidth - font.width(speed)) / 2, y + SOCKET_H + 13, YELLOW, false);
        }
    }

    private boolean isUsb(int index) {
        return DeviceType.byId(view.deviceType()).isUsbPort(index);
    }

    private boolean isHdmi(int index) {
        return DeviceType.byId(view.deviceType()).isHdmiPort(index);
    }

    /** An HDMI socket: wide, with the bevelled lower corners; the plug in the cable's colour. */
    private void drawHdmiSocket(GuiGraphicsExtractor graphics, int x, int y, NetPayloads.PortInfo port, boolean hover) {
        graphics.fill(x, y, x + socketWidth, y + SOCKET_H, hover ? 0xFF5C5F66 : 0xFF3C3E43);
        int holeX = x + 4;
        int holeY = y + 8;
        int holeW = socketWidth - 8;
        graphics.fill(holeX, holeY, holeX + holeW, holeY + 5, 0xFF101114);
        graphics.fill(holeX + 2, holeY + 5, holeX + holeW - 2, holeY + 7, 0xFF101114);
        graphics.fill(holeX + 3, holeY + 2, holeX + holeW - 3, holeY + 4, 0xFFC9A227); // Gold contacts.
        if (port.state() != NetPayloads.PortInfo.FREE) {
            graphics.fill(holeX - 2, holeY - 2, holeX + holeW + 2, holeY + 9, 0xFF000000 | port.color());
            graphics.fill(holeX, holeY, holeX + holeW, holeY + 5, 0xFFB8BCC2);
        }
        // Picture LED: lit while a running computer's picture goes through this socket.
        graphics.fill(x + socketWidth / 2 - 2, y + 2, x + socketWidth / 2 + 2, y + 5, port.link() ? 0xFF3DA8FF : 0xFF1A2633);
        String name = port.name();
        graphics.text(font, name, x + (socketWidth - font.width(name)) / 2, y + SOCKET_H + 3, port.link() ? GREEN : GRAY, false);
    }

    /** A flat USB-A socket: no LEDs, the plug in the cable's colour. */
    private void drawUsbSocket(GuiGraphicsExtractor graphics, int x, int y, NetPayloads.PortInfo port, boolean hover) {
        graphics.fill(x, y, x + socketWidth, y + SOCKET_H, hover ? 0xFF5C5F66 : 0xFF3C3E43);
        int holeX = x + 5;
        int holeY = y + 9;
        int holeW = socketWidth - 10;
        graphics.fill(holeX, holeY, holeX + holeW, holeY + 8, 0xFF101114);
        graphics.fill(holeX + 2, holeY + 1, holeX + holeW - 2, holeY + 4, 0xFF2F6FD6);
        if (port.state() != NetPayloads.PortInfo.FREE) {
            graphics.fill(holeX - 1, holeY - 2, holeX + holeW + 1, holeY + 10, 0xFF000000 | port.color());
            graphics.fill(holeX + 1, holeY, holeX + holeW - 1, holeY + 8, 0xFFB8BCC2);
        }
        String name = port.name();
        graphics.text(font, name, x + (socketWidth - font.width(name)) / 2, y + SOCKET_H + 3, port.link() ? GREEN : GRAY, false);
    }

    private List<Component> tooltip(int index) {
        NetPayloads.PortInfo port = view.ports().get(index);
        if (!isUsb(index) && !isHdmi(index)) {
            return tooltip(port);
        }
        DeviceType type = DeviceType.byId(view.deviceType());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(port.name()));
        if (isHdmi(index)) {
            lines.add(Component.translatable(type.isHdmiOutput(index) ? "virtual_mashine.ports.tip.hdmi_out" : "virtual_mashine.ports.tip.hdmi_in"));
        } else {
            lines.add(Component.translatable(type.isUsbUpstream(index) ? "virtual_mashine.ports.tip.usb_up" : "virtual_mashine.ports.tip.usb"));
        }
        switch (port.state()) {
            case NetPayloads.PortInfo.CONNECTED -> {
                lines.add(Component.translatable("virtual_mashine.ports.tip.connected", port.peer()));
                if (isHdmi(index)) {
                    lines.add(Component.translatable(port.link() ? "virtual_mashine.ports.tip.picture" : "virtual_mashine.ports.tip.no_picture"));
                } else {
                    lines.add(Component.translatable(port.link() ? "virtual_mashine.ports.tip.link" : "virtual_mashine.ports.tip.no_link"));
                }
                lines.add(Component.translatable("virtual_mashine.ports.tip.click_unplug"));
            }
            case NetPayloads.PortInfo.HALF -> {
                lines.add(Component.translatable("virtual_mashine.ports.tip.half"));
                lines.add(Component.translatable("virtual_mashine.ports.tip.click_cancel"));
            }
            default -> {
                lines.add(Component.translatable("virtual_mashine.ports.tip.free"));
                if (!view.heldCable().isEmpty()) {
                    lines.add(Component.translatable("virtual_mashine.ports.tip.click_plug"));
                }
            }
        }
        return lines;
    }

    private List<Component> tooltip(NetPayloads.PortInfo port) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(port.name()));
        if (!port.mac().isEmpty()) {
            lines.add(Component.translatable("virtual_mashine.ports.tip.mac", port.mac()));
        }
        if (port.console()) {
            lines.add(Component.translatable("virtual_mashine.ports.tip.console"));
        }
        if (port.link() && !port.console()) {
            lines.add(Component.translatable("virtual_mashine.ports.tip.speed", formatSpeed(port.speedMbit())));
        }
        if (tester && !port.console()) {
            lines.add(Component.translatable("virtual_mashine.ports.tip.traffic", formatBytes(port.rxBytes()), formatBytes(port.txBytes())));
        }
        switch (port.state()) {
            case NetPayloads.PortInfo.CONNECTED -> {
                lines.add(Component.translatable("virtual_mashine.ports.tip.connected", port.peer()));
                lines.add(Component.translatable("virtual_mashine.ports.tip.cable",
                        Component.translatable("item.virtual_mashine." + port.cableType() + "_cable"), port.length(),
                        Component.translatable(CableType.colorKey(port.color()))));
                lines.add(Component.translatable(port.link() ? "virtual_mashine.ports.tip.link" : "virtual_mashine.ports.tip.no_link"));
                lines.add(Component.translatable("virtual_mashine.ports.tip.click_unplug"));
            }
            case NetPayloads.PortInfo.HALF -> {
                lines.add(Component.translatable("virtual_mashine.ports.tip.half"));
                lines.add(Component.translatable("virtual_mashine.ports.tip.click_cancel"));
            }
            default -> {
                lines.add(Component.translatable("virtual_mashine.ports.tip.free"));
                if (!view.heldCable().isEmpty()) {
                    lines.add(Component.translatable("virtual_mashine.ports.tip.click_plug"));
                }
            }
        }
        return lines;
    }

    private static String formatSpeed(int mbit) {
        return mbit >= 1000 ? (mbit / 1000) + " Gbit/s" : mbit + " Mbit/s";
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return bytes >= 1024 ? (bytes / 1024) + " KB" : bytes + " B";
    }

    private List<FormattedCharSequence> statusLines() {
        Component status;
        if (!lastMessage.isEmpty() && System.currentTimeMillis() - lastMessageAt < 4000) {
            status = Component.translatable(lastMessage);
        } else if (!view.pending().isEmpty()) {
            status = Component.translatable("virtual_mashine.ports.status.pending", view.pending());
        } else if (!view.heldCable().isEmpty()) {
            status = Component.translatable("virtual_mashine.ports.status.choose");
        } else {
            status = Component.translatable("virtual_mashine.ports.status.no_cable");
        }
        return font.split(status, Math.min(width - 20, 360));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
