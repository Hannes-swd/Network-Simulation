package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.client.ClientPcState;
import dev.hannes.virtual_mashine.client.vnc.RfbClient;
import dev.hannes.virtual_mashine.client.vnc.RfbTransport;
import dev.hannes.virtual_mashine.client.vnc.TextTyper;
import dev.hannes.virtual_mashine.client.vnc.TunnelTransport;
import dev.hannes.virtual_mashine.client.vnc.VncKeyboard;
import dev.hannes.virtual_mashine.network.MediaPayloads;
import dev.hannes.virtual_mashine.network.PcActionPayload;
import dev.hannes.virtual_mashine.network.PcInfoPayload;
import dev.hannes.virtual_mashine.vm.VmConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.Optional;

/**
 * Shows the guest screen. Clicking into it captures keyboard and mouse: every key (including
 * ESC and F-keys) then goes to the guest until Left Ctrl + Left Alt releases it again.
 * Closing the screen leaves the machine running in the background.
 * <p>
 * A machine with several screens (HDMI monitors) shows one of them; the screen button switches.
 * <p>
 * The clipboard is shared with the guest (when spice-vdagent runs there): the guest's copies arrive
 * at once, this computer's clipboard is handed over when the player clicks into the screen. The
 * paste button types the clipboard text instead, which works with every system.
 */
public class PcDisplayScreen extends PcScreen {
    private static final int TOOLBAR = 24;
    private static final int FOOTER = 12;
    private static final SystemToast.SystemToastId MESSAGE_TOAST = new SystemToast.SystemToastId(8000L);

    private final VmScreenTexture texture = new VmScreenTexture();
    private final VncKeyboard keyboard = new VncKeyboard();
    /** Which screen of the graphics card is shown. */
    private int head;

    private @Nullable RfbClient client;
    private boolean captured;
    private int buttonMask;
    private boolean cursorHidden;
    private int reconnectDelay;
    /** Clipboard text both sides already have, so nothing is sent back and forth twice. */
    private @Nullable String lastClipboard;

    private Button freezeButton;
    private Button shutdownButton;
    private Button resetButton;
    private Button powerOffButton;
    private Button ctrlAltDelButton;
    private Button pasteButton;
    private Button layoutButton;
    private Button screenButton;

    private int viewX;
    private int viewY;
    private int viewW;
    private int viewH;

    public PcDisplayScreen(PcInfoPayload info) {
        super(info);
    }

    @Override
    protected void init() {
        sendAudioFocus(true);
        Component[] labels = {
                Component.translatable("virtual_mashine.button.control"),
                Component.translatable("virtual_mashine.button.ctrl_alt_del"),
                Component.translatable("virtual_mashine.button.paste"),
                layoutLabel(),
                screenLabel(),
                Component.translatable("virtual_mashine.button.shutdown"),
                Component.translatable("virtual_mashine.button.reset"),
                Component.translatable("virtual_mashine.button.power_off"),
                Component.translatable("virtual_mashine.button.freeze"),
        };
        int gap = 3;
        int[] widths = new int[labels.length];
        int total = gap * (labels.length - 1);
        for (int i = 0; i < labels.length; i++) {
            widths[i] = font.width(labels[i]) + 12;
            total += widths[i];
        }
        int available = width - 8;
        if (total > available) {
            // Narrow window: shrink every button alike, long labels scroll.
            double factor = (double) (available - gap * (labels.length - 1)) / (total - gap * (labels.length - 1));
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(24, (int) (widths[i] * factor));
            }
        }
        Button[] buttons = new Button[labels.length];
        Button.OnPress[] actions = {
                b -> openControl(),
                b -> sendCtrlAltDel(),
                b -> pasteAsKeys(),
                b -> toggleLayout(),
                b -> nextScreen(),
                b -> send(PcActionPayload.SHUTDOWN),
                b -> send(PcActionPayload.RESET),
                b -> send(PcActionPayload.POWER_OFF),
                b -> send(PcActionPayload.FREEZE),
        };
        int x = 4;
        for (int i = 0; i < labels.length; i++) {
            buttons[i] = addRenderableWidget(Button.builder(labels[i], actions[i]).bounds(x, 2, widths[i], 20).build());
            x += widths[i] + gap;
        }
        ctrlAltDelButton = buttons[1];
        pasteButton = buttons[2];
        layoutButton = buttons[3];
        screenButton = buttons[4];
        shutdownButton = buttons[5];
        resetButton = buttons[6];
        powerOffButton = buttons[7];
        freezeButton = buttons[8];
        screenButton.setTooltip(Tooltip.create(Component.translatable("virtual_mashine.button.screen.tooltip")));
        pasteButton.setTooltip(Tooltip.create(Component.translatable("virtual_mashine.button.paste.tooltip")));
        layoutButton.setTooltip(Tooltip.create(Component.translatable("virtual_mashine.button.layout.tooltip")));
        shutdownButton.setTooltip(Tooltip.create(Component.translatable("virtual_mashine.button.shutdown.tooltip")));
        powerOffButton.setTooltip(Tooltip.create(Component.translatable("virtual_mashine.button.power_off.tooltip")));
        updateButtons();
        connectIfNeeded();
    }

    @Override
    public void onInfo(PcInfoPayload newInfo) {
        String previousKey = info.messageKey();
        String previousDetail = info.messageDetail();
        super.onInfo(newInfo);
        Component message = messageText();
        if (message != null && (!previousKey.equals(newInfo.messageKey()) || !previousDetail.equals(newInfo.messageDetail()))) {
            SystemToast previous = minecraft.getToastManager().getToast(SystemToast.class, MESSAGE_TOAST);
            if (previous != null) {
                previous.forceHide();
            }
            minecraft.getToastManager().addToast(SystemToast.multiline(minecraft, MESSAGE_TOAST, Component.literal(serverConfig.name), message));
        }
        if (isOff()) {
            // The guest shut down (or was frozen): back to the control panel.
            minecraft.setScreen(new PcControlScreen(newInfo));
            return;
        }
        updateButtons();
        connectIfNeeded();
    }

    private void updateButtons() {
        boolean running = isRunning(info);
        shutdownButton.active = running;
        resetButton.active = running;
        powerOffButton.active = running || "STOPPING".equals(info.state());
        ctrlAltDelButton.active = running;
        pasteButton.active = running;
        layoutButton.setMessage(layoutLabel());
        if (!isActiveScreen(head)) {
            head = 0; // Its monitor was unplugged.
        }
        screenButton.setMessage(screenLabel());
        screenButton.active = running && Integer.bitCount(info.activeScreens()) > 1;
        freezeButton.active = running && info.canFreeze();
        freezeButton.setTooltip(Tooltip.create(Component.translatable(info.canFreeze()
                ? "virtual_mashine.button.freeze.tooltip" : "virtual_mashine.button.freeze.unsupported")));
    }

    /** VNC port of the screen shown, -1 if there is none. */
    private int screenPort() {
        if (head == 0) {
            return info.vncPort();
        }
        return head < info.screenPorts().size() && isRunning(info) ? info.screenPorts().get(head) : -1;
    }

    private boolean isActiveScreen(int screen) {
        return screen == 0 || (info.activeScreens() & (1 << screen)) != 0 && screen < info.screenPorts().size();
    }

    private Component screenLabel() {
        return Component.translatable("virtual_mashine.button.screen", head + 1);
    }

    /** Shows the next screen that has a monitor. */
    private void nextScreen() {
        int count = Math.max(1, info.screenPorts().size());
        for (int step = 1; step <= count; step++) {
            int candidate = (head + step) % count;
            if (isActiveScreen(candidate)) {
                if (candidate != head) {
                    releaseCapture();
                    head = candidate;
                    reconnectDelay = 0;
                    connectIfNeeded();
                }
                break;
            }
        }
        screenButton.setMessage(screenLabel());
    }

    private void connectIfNeeded() {
        int port = screenPort();
        if (!isRunning(info) || port <= 0) {
            return;
        }
        if (client != null && client.port() == port && client.status() != RfbClient.Status.CLOSED) {
            return;
        }
        if (reconnectDelay > 0) {
            return;
        }
        if (client != null) {
            client.close();
        }
        // The host's game runs next to QEMU; everybody else gets the screen through the Minecraft connection.
        RfbTransport transport = isHost() ? RfbTransport.local(port) : new TunnelTransport(pcId(), head);
        client = new RfbClient(transport, port);
        reconnectDelay = 20;
    }

    private void openControl() {
        minecraft.setScreen(new PcControlScreen(info));
    }

    @Override
    public void tick() {
        super.tick();
        if (reconnectDelay > 0) {
            reconnectDelay--;
        }
        connectIfNeeded();
        if (captured && !minecraft.isWindowActive()) {
            releaseCapture(); // Alt-Tab etc.: never leave keys stuck in the guest.
        }
        syncClipboard();
    }

    // ---------------------------------------------------------------- clipboard

    /** Text the guest copied lands in this computer's clipboard (the guest side needs spice-vdagent). */
    private void syncClipboard() {
        if (client == null || client.status() != RfbClient.Status.CONNECTED) {
            return;
        }
        String fromGuest = client.takeGuestClipboard();
        if (fromGuest != null && !fromGuest.isEmpty() && serverConfig.clipboardSharing) {
            lastClipboard = fromGuest;
            minecraft.keyboardHandler.setClipboard(fromGuest);
        }
    }

    /**
     * Hands this computer's clipboard to the guest, only at the moment the player clicks into the
     * screen: a program in the VM never sees what is copied while nobody works in it.
     */
    private void pushClipboard() {
        if (!serverConfig.clipboardSharing || client == null) {
            return;
        }
        String host = minecraft.keyboardHandler.getClipboard();
        if (!host.isEmpty() && !host.equals(lastClipboard) && client.sendClipboard(host)) {
            lastClipboard = host;
        }
    }

    /** Types the clipboard text into the guest, for systems without a clipboard agent. */
    private void pasteAsKeys() {
        String text = minecraft.keyboardHandler.getClipboard();
        if (client == null || client.status() != RfbClient.Status.CONNECTED || text.isEmpty()) {
            return;
        }
        TextTyper.type(client, text, serverConfig.typingLayout);
        int missing = TextTyper.untypable(text, serverConfig.typingLayout);
        if (missing > 0) {
            SystemToast.addOrUpdate(minecraft.getToastManager(), MESSAGE_TOAST, Component.literal(serverConfig.name),
                    Component.translatable("virtual_mashine.msg.paste_skipped", missing));
        }
    }

    private Component layoutLabel() {
        return Component.literal(serverConfig.typingLayout.toUpperCase(Locale.ROOT));
    }

    private void toggleLayout() {
        VmConfig changed = serverConfig.copy();
        int next = (VmConfig.TYPING_LAYOUTS.indexOf(changed.typingLayout) + 1) % VmConfig.TYPING_LAYOUTS.size();
        changed.typingLayout = VmConfig.TYPING_LAYOUTS.get(next);
        serverConfig = changed;
        layoutButton.setMessage(layoutLabel());
        ClientPcState.send(pcId(), PcActionPayload.CONFIG, changed.toJson());
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, 0xFF000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        RfbClient vnc = client;
        if (vnc != null) {
            texture.update(vnc);
        }
        layoutView();
        if (texture.ready() && vnc != null) {
            boolean magnified = (long) viewW * minecraft.getWindow().getGuiScale() >= texture.width();
            graphics.blit(texture.view(), texture.sampler(magnified), viewX, viewY, viewX + viewW, viewY + viewH, 0.0F, 1.0F, 0.0F, 1.0F);
            if (captured) {
                graphics.outline(viewX - 1, viewY - 1, viewW + 2, viewH + 2, 0xFF3C8DFF);
            }
        }

        graphics.fill(0, 0, width, TOOLBAR, 0xFF1E1E1E);
        super.extractRenderState(graphics, mouseX, mouseY, a);

        int toolbarEnd = freezeButton.getX() + freezeButton.getWidth() + 8;
        Component status = Component.empty().append(serverConfig.name).append(" – ").append(stateText());
        if (toolbarEnd + font.width(status) < width - 4) {
            graphics.text(font, status, width - 4 - font.width(status), 8, stateColor());
        }

        Component overlay = overlayText(vnc);
        if (overlay != null) {
            int textWidth = font.width(overlay);
            int cx = width / 2;
            int cy = TOOLBAR + (height - TOOLBAR - FOOTER) / 2;
            graphics.fill(cx - textWidth / 2 - 6, cy - 8, cx + textWidth / 2 + 6, cy + 12, 0xC0000000);
            graphics.centeredText(font, overlay, cx, cy - 2, YELLOW);
        }

        Component hint = Component.translatable(captured ? "virtual_mashine.hint.captured" : "virtual_mashine.hint.click_to_capture");
        graphics.centeredText(font, hint, width / 2, height - FOOTER + 2, captured ? 0xFF8AB8FF : GRAY);

        setCursorHidden(captured && isOverView(mouseX, mouseY));
    }

    private @Nullable Component overlayText(@Nullable RfbClient vnc) {
        if (!isRunning(info)) {
            return stateText();
        }
        if (vnc == null || vnc.status() == RfbClient.Status.CONNECTING || !texture.ready()) {
            return Component.translatable("virtual_mashine.display.connecting");
        }
        if (vnc.status() == RfbClient.Status.CLOSED) {
            return Component.translatable("virtual_mashine.display.lost", vnc.error());
        }
        return null;
    }

    /** Fits the guest screen into the area below the toolbar, keeping its aspect ratio. */
    private void layoutView() {
        int availW = width - 4;
        int availH = height - TOOLBAR - FOOTER - 2;
        int fbW = texture.ready() ? texture.width() : 4;
        int fbH = texture.ready() ? texture.height() : 3;
        double scale = Math.min((double) availW / fbW, (double) availH / fbH);
        viewW = Math.max(1, (int) Math.floor(fbW * scale));
        viewH = Math.max(1, (int) Math.floor(fbH * scale));
        viewX = (width - viewW) / 2;
        viewY = TOOLBAR + 1 + (availH - viewH) / 2;
    }

    private boolean isOverView(double x, double y) {
        return texture.ready() && x >= viewX && x < viewX + viewW && y >= viewY && y < viewY + viewH;
    }

    private int guestX(double x) {
        int w = texture.width();
        return Math.max(0, Math.min(w - 1, (int) ((x - viewX) * w / viewW)));
    }

    private int guestY(double y) {
        int h = texture.height();
        return Math.max(0, Math.min(h - 1, (int) ((y - viewY) * h / viewH)));
    }

    // ---------------------------------------------------------------- mouse

    private boolean connected() {
        return client != null && client.status() == RfbClient.Status.CONNECTED && texture.ready();
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (connected() && isOverView(x, y)) {
            client.sendPointer(buttonMask, guestX(x), guestY(y));
        }
        super.mouseMoved(x, y);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (connected() && isOverView(event.x(), event.y())) {
            if (!captured) {
                pushClipboard();
            }
            captured = true;
            buttonMask |= vncButton(event.button());
            client.sendPointer(buttonMask, guestX(event.x()), guestY(event.y()));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int bit = vncButton(event.button());
        if (bit != 0 && (buttonMask & bit) != 0) {
            buttonMask &= ~bit;
            if (connected()) {
                client.sendPointer(buttonMask, guestX(event.x()), guestY(event.y()));
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (buttonMask != 0) {
            return true; // Movement is already forwarded by mouseMoved.
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!connected() || !isOverView(x, y)) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }
        int gx = guestX(x);
        int gy = guestY(y);
        wheel(scrollY > 0 ? 1 << 3 : 1 << 4, scrollY, gx, gy);
        wheel(scrollX > 0 ? 1 << 5 : 1 << 6, scrollX, gx, gy);
        return true;
    }

    private void wheel(int bit, double amount, int gx, int gy) {
        int notches = Math.min(5, (int) Math.round(Math.abs(amount)));
        if (amount != 0 && notches == 0) {
            notches = 1;
        }
        for (int i = 0; i < notches; i++) {
            client.sendPointer(buttonMask | bit, gx, gy);
            client.sendPointer(buttonMask, gx, gy);
        }
    }

    private static int vncButton(int glfwButton) {
        return switch (glfwButton) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> 1;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 1 << 1;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 1 << 2;
            default -> 0;
        };
    }

    private void setCursorHidden(boolean hidden) {
        if (hidden != cursorHidden) {
            cursorHidden = hidden;
            GLFW.glfwSetInputMode(minecraft.getWindow().handle(), GLFW.GLFW_CURSOR, hidden ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    // ---------------------------------------------------------------- keyboard

    /** While true, {@code KeyboardHandlerMixin} sends every key straight to {@link #handleRawKey}. */
    public boolean isCapturingKeyboard() {
        return captured && client != null && client.status() == RfbClient.Status.CONNECTED;
    }

    public void handleRawKey(KeyEvent event, int action) {
        if (action == VncKeyboard.ACTION_PRESS && VncKeyboard.isReleaseCombo(minecraft.getWindow().handle(), event.key())) {
            releaseCapture();
            return;
        }
        keyboard.key(client, event, action);
    }

    private void releaseCapture() {
        keyboard.releaseAll(client);
        if (client != null && buttonMask != 0) {
            client.sendPointer(0, 0, 0);
        }
        buttonMask = 0;
        captured = false;
        setCursorHidden(false);
    }

    private void sendCtrlAltDel() {
        if (client == null) {
            return;
        }
        int[][] keys = {{0x1D, 0xFFE3}, {0x38, 0xFFE9}, {0xD3, 0xFFFF}};
        for (int[] k : keys) {
            client.sendKey(true, k[0], k[1]);
        }
        for (int i = keys.length - 1; i >= 0; i--) {
            client.sendKey(false, keys[i][0], keys[i][1]);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isCapturingKeyboard()) {
            handleRawKey(event, VncKeyboard.ACTION_PRESS);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return isCapturingKeyboard() || super.charTyped(event);
    }

    /** While this screen is open the machine's sound plays directly (stereo) instead of from its block. */
    private void sendAudioFocus(boolean open) {
        if (ClientPlayNetworking.canSend(MediaPayloads.AudioFocus.TYPE)) {
            ClientPlayNetworking.send(new MediaPayloads.AudioFocus(open ? Optional.of(pcId()) : Optional.empty()));
        }
    }

    @Override
    public void removed() {
        sendAudioFocus(false);
        releaseCapture();
        if (client != null) {
            client.close();
            client = null;
        }
        texture.close();
        super.removed();
    }
}
