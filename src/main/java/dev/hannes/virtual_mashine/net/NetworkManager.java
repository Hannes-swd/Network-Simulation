package dev.hannes.virtual_mashine.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.device.CableType;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.item.CableItem;
import dev.hannes.virtual_mashine.network.NetPayloads;
import dev.hannes.virtual_mashine.vm.QemuLocator;
import dev.hannes.virtual_mashine.vm.SerialLine;
import dev.hannes.virtual_mashine.vm.VirtualMachine;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cabling of one world: which port is plugged into which, the devices that live inside the mod
 * (switch, modem), and the half-plugged cables players are carrying around.
 * All public methods are called on the server thread.
 */
public final class NetworkManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double REACH = 8.0;
    private static final long ACTIVITY_NANOS = 400_000_000L;

    /** A device block that ticked recently. */
    record Placed(DeviceType type, String dimension, BlockPos pos, Direction facing) {
    }

    /** One end of a cable is plugged in, the player walks to the second device. */
    private record Pending(PortRef port, DeviceType deviceType, String dimension, BlockPos pos, Direction facing, CableType cable, int color) {
    }

    private final MinecraftServer server;
    private final VmManager vms;
    private final Path file;
    private final Path root;
    private final NetworkFabric fabric;
    private final WlanManager wlan;
    private final List<Cable> cables = new CopyOnWriteArrayList<>();
    private final Map<UUID, Placed> devices = new ConcurrentHashMap<>();
    private final Map<UUID, Process> modems = new ConcurrentHashMap<>();
    /** When each modem was last started, so a failing one is not restarted every second. */
    private final Map<UUID, Long> modemStarted = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, String> syncedDimension = new HashMap<>();
    private final Set<UUID> autoStarted = ConcurrentHashMap.newKeySet();
    private int ticks;

    public NetworkManager(MinecraftServer server, VmManager vms, Path root) throws IOException {
        this.server = server;
        this.vms = vms;
        this.root = root;
        this.file = root.resolve("cables.json");
        this.fabric = new NetworkFabric();
        this.wlan = new WlanManager(server, this, fabric, root);
        load();
    }

    public NetworkFabric fabric() {
        return fabric;
    }

    public WlanManager wlan() {
        return wlan;
    }

    @Nullable Placed placed(UUID id) {
        return devices.get(id);
    }

    List<UUID> devicesOfType(DeviceType type) {
        List<UUID> found = new ArrayList<>();
        for (Map.Entry<UUID, Placed> entry : devices.entrySet()) {
            if (entry.getValue().type == type) {
                found.add(entry.getKey());
            }
        }
        return found;
    }

    String nameOf(UUID id, DeviceType type) {
        return deviceName(id, type);
    }

    // ---------------------------------------------------------------- devices

    /** Called about once a second by every loaded device block. */
    public void deviceTick(UUID id, DeviceType type, ServerLevel level, BlockPos pos, Direction facing) {
        devices.put(id, new Placed(type, dimensionOf(level), pos.immutable(), facing));
        switch (type.kind) {
            case SWITCH -> {
                if (!fabric.hasSwitch(id)) {
                    fabric.addSwitch(id, type.ports);
                    refreshLinks();
                }
            }
            case MODEM -> ensureModem(id);
            case AP -> {
                if (!fabric.hasSwitch(id)) {
                    // Port 0 is the LAN socket, ports 1..n are the wireless clients.
                    fabric.addSwitch(id, 1 + DeviceType.AP_CLIENT_SLOTS);
                    wlan.accessPoint(id);
                    refreshLinks();
                }
            }
            case VM -> {
                if (autoStarted.add(id) && vms.shouldAutoStart(id, type)) {
                    vms.autoStart(id, type);
                }
            }
        }
    }

    /** LED bits for the block: low 32 bits = link per port, high 32 bits = traffic per port. */
    public long ledState(UUID id, DeviceType type) {
        long now = System.nanoTime();
        long link = 0;
        long activity = 0;
        for (int i = 0; i < Math.min(32, type.totalPorts()); i++) {
            PortRef port = new PortRef(id, i);
            Cable cable = cableAt(port);
            if (cable == null) {
                continue;
            }
            boolean up = type.isConsolePort(i) ? consoleLink(port, cable) : fabric.hasLink(port);
            if (up) {
                link |= 1L << i;
                NetworkFabric.PortStats stats = fabric.stats(port);
                if (stats.lastActivity != 0 && now - stats.lastActivity < ACTIVITY_NANOS) {
                    activity |= 1L << i;
                }
            }
        }
        return link | (activity << 32);
    }

    public boolean isPowered(UUID id, DeviceType type) {
        return switch (type.kind) {
            case SWITCH, AP -> devices.containsKey(id);
            case MODEM -> {
                Process modem = modems.get(id);
                yield modem != null && modem.isAlive();
            }
            case VM -> vms.isPowered(id);
        };
    }

    /** The block is gone: its cables fall off and drop as items. */
    public void deviceRemoved(UUID id, ServerLevel level, BlockPos pos) {
        boolean changed = false;
        for (Cable cable : List.copyOf(cables)) {
            if (cable.touches(id)) {
                removeCable(cable);
                Block.popResource(level, pos, cableItem(cable.cableType(), cable.color));
                changed = true;
            }
        }
        pending.entrySet().removeIf(entry -> entry.getValue().port.device().equals(id));
        wlan.deviceRemoved(id);
        devices.remove(id);
        fabric.removeSwitch(id);
        stopModem(id);
        if (changed) {
            save();
            broadcastCables();
        }
        refreshLinks();
    }

    // ---------------------------------------------------------------- VM integration

    public NetworkFabric.UdpBinding[] openVmPorts(UUID id, int count) throws IOException {
        return fabric.openUdpPorts(id, count);
    }

    /** A VM's network cards are up (or gone): every link LED and carrier state may change. */
    public void vmNetworkChanged(UUID id, boolean running) {
        if (!running) {
            fabric.closeUdpPorts(id);
        }
        server.execute(this::refreshLinks);
    }

    /** Pushes "cable plugged/unplugged" to the network cards and serial ports of all running VMs. */
    public void refreshLinks() {
        for (VirtualMachine vm : vms.runningMachines()) {
            int ports = vm.cablePorts();
            for (int port = 0; port < ports; port++) {
                vm.updateLink(port, fabric.hasLink(new PortRef(vm.id(), port)));
            }
            SerialLine serial = vm.serial();
            if (serial != null) {
                SerialLine wanted = consolePartner(vm.id(), vm.deviceType());
                if (serial.peer() != wanted) {
                    serial.connectTo(wanted);
                }
            }
        }
    }

    /** The serial port at the other end of this machine's console cable, if that machine runs. */
    private @Nullable SerialLine consolePartner(UUID id, DeviceType type) {
        Cable cable = cableAt(new PortRef(id, type.ports));
        if (cable == null) {
            return null;
        }
        VirtualMachine other = vms.runningMachine(cable.otherEnd(new PortRef(id, type.ports)).device);
        return other == null ? null : other.serial();
    }

    private boolean consoleLink(PortRef port, Cable cable) {
        Cable.End other = cable.otherEnd(port);
        return vms.runningMachine(port.device()) != null && vms.runningMachine(other.device) != null;
    }

    /** Connects a new or loaded cable in the fabric (network cables only; consoles are linked in refreshLinks). */
    private void wire(Cable cable) {
        if (!cable.cableType().isConsole()) {
            fabric.connect(cable.a.ref(), cable.b.ref(), cable.cableType().speedMbit(cable.length()));
        }
    }

    // ---------------------------------------------------------------- port panel

    public void openPortView(ServerPlayer player, UUID id, DeviceType type, BlockPos pos, Direction facing) {
        openPortView(player, id, type, pos, facing, false);
    }

    /** @param tester opened with the network tester: the panel also shows speeds, MACs and traffic */
    public void openPortView(ServerPlayer player, UUID id, DeviceType type, BlockPos pos, Direction facing, boolean tester) {
        devices.put(id, new Placed(type, dimensionOf(player.level()), pos.immutable(), facing));
        NetPayloads.PortView view = portView(player, id, type, pos, true, "");
        ServerPlayNetworking.send(player, new NetPayloads.PortView(view.device(), view.pos(), view.deviceType(), view.deviceName(),
                view.powered(), true, view.ports(), view.heldCable(), view.pending(), view.message(), tester));
    }

    // ---------------------------------------------------------------- WLAN panels

    public void openWlanView(ServerPlayer player, UUID laptop, String message) {
        Placed placed = devices.get(laptop);
        if (placed == null || !placed.dimension.equals(dimensionOf(player.level()))) {
            return;
        }
        List<NetPayloads.WlanNetwork> networks = new ArrayList<>();
        for (WlanManager.Network seen : wlan.scan(laptop)) {
            networks.add(new NetPayloads.WlanNetwork(seen.ssid(), seen.dbm(), seen.secured(), seen.connected()));
        }
        String connected = wlan.connectedSsid(laptop);
        String remembered = wlan.rememberedSsid(laptop);
        ServerPlayNetworking.send(player, new NetPayloads.WlanView(laptop, placed.pos, deviceName(laptop, placed.type), networks,
                connected == null ? "" : connected, remembered == null ? "" : remembered, wlan.linkSpeed(laptop),
                isPowered(laptop, placed.type), message));
    }

    public void handleWlanAction(ServerPlayer player, NetPayloads.WlanAction action) {
        Placed placed = devices.get(action.device());
        if (placed == null || placed.type != DeviceType.LAPTOP || !placed.dimension.equals(dimensionOf(player.level()))
                || player.distanceToSqr(Vec3.atCenterOf(placed.pos)) > REACH * REACH * 4) {
            return;
        }
        String message = switch (action.action()) {
            case NetPayloads.WlanAction.CONNECT -> wlan.connect(action.device(), action.ssid(), action.password());
            case NetPayloads.WlanAction.FORGET -> {
                wlan.forget(action.device());
                yield "virtual_mashine.wlan.msg.forgotten";
            }
            default -> "";
        };
        openWlanView(player, action.device(), message);
    }

    public void openApView(ServerPlayer player, UUID ap, BlockPos pos, Direction facing, String message) {
        devices.put(ap, new Placed(DeviceType.ACCESS_POINT, dimensionOf(player.level()), pos.immutable(), facing));
        WlanManager.ApConfig config = wlan.accessPoint(ap);
        ServerPlayNetworking.send(player, new NetPayloads.ApView(ap, pos, deviceName(ap, DeviceType.ACCESS_POINT), config.ssid,
                config.password, wlan.clientsOf(ap), message));
    }

    public void handleApAction(ServerPlayer player, NetPayloads.ApAction action) {
        Placed placed = devices.get(action.device());
        if (placed == null || placed.type != DeviceType.ACCESS_POINT || !placed.dimension.equals(dimensionOf(player.level()))
                || player.distanceToSqr(Vec3.atCenterOf(placed.pos)) > REACH * REACH) {
            return;
        }
        String message = action.save() ? wlan.configure(action.device(), action.ssid(), action.password()) : "";
        openApView(player, action.device(), placed.pos, placed.facing, message);
    }

    /** The "Anschlüsse" button of a computer's control panel. */
    public void openPortViewOf(ServerPlayer player, UUID id) {
        Placed placed = devices.get(id);
        if (placed != null && placed.type == DeviceType.LAPTOP) {
            openWlanView(player, id, "");
            return;
        }
        if (placed != null && placed.dimension.equals(dimensionOf(player.level()))
                && player.distanceToSqr(Vec3.atCenterOf(placed.pos)) <= REACH * REACH * 4) {
            ServerPlayNetworking.send(player, portView(player, id, placed.type, placed.pos, true, ""));
        }
    }

    public void handlePortAction(ServerPlayer player, NetPayloads.PortAction action) {
        Placed placed = devices.get(action.device());
        if (placed == null || !placed.pos.equals(action.pos()) || !placed.dimension.equals(dimensionOf(player.level()))
                || player.distanceToSqr(Vec3.atCenterOf(action.pos())) > REACH * REACH) {
            return;
        }
        if (action.port() < 0 || action.port() >= placed.type.totalPorts()) {
            if (action.action().equals(NetPayloads.PortAction.REFRESH)) {
                ServerPlayNetworking.send(player, portView(player, action.device(), placed.type, placed.pos, false, ""));
            }
            return;
        }
        PortRef port = new PortRef(action.device(), action.port());
        String message = switch (action.action()) {
            case NetPayloads.PortAction.PLUG -> plug(player, port, placed);
            case NetPayloads.PortAction.UNPLUG -> unplug(player, port);
            default -> "";
        };
        ServerPlayNetworking.send(player, portView(player, action.device(), placed.type, placed.pos, false, message));
    }

    private String plug(ServerPlayer player, PortRef port, Placed placed) {
        ItemStack held = heldCable(player);
        if (held.isEmpty()) {
            return "virtual_mashine.ports.msg.no_cable";
        }
        if (cableAt(port) != null) {
            return "virtual_mashine.ports.msg.occupied";
        }
        CableItem item = (CableItem) held.getItem();
        if (placed.type.isWirelessPort(port.port())) {
            return "virtual_mashine.ports.msg.wireless";
        }
        boolean consolePort = placed.type.isConsolePort(port.port());
        if (item.cableType.isConsole() != consolePort) {
            return consolePort ? "virtual_mashine.ports.msg.needs_console_cable" : "virtual_mashine.ports.msg.needs_network_cable";
        }
        Pending start = pending.get(player.getUUID());
        if (start == null) {
            pending.put(player.getUUID(), new Pending(port, placed.type, placed.dimension, placed.pos, placed.facing,
                    item.cableType, CableItem.color(held)));
            sendHeldCable(player);
            return "virtual_mashine.ports.msg.first_end";
        }
        if (start.port.equals(port)) {
            return "";
        }
        if (!start.dimension.equals(placed.dimension)) {
            return "virtual_mashine.ports.msg.other_dimension";
        }
        Cable cable = new Cable();
        cable.id = UUID.randomUUID();
        cable.type = start.cable.id;
        cable.color = start.color;
        cable.a = Cable.End.of(start.port, start.deviceType, start.dimension, start.pos, start.facing);
        cable.b = Cable.End.of(port, placed.type, placed.dimension, placed.pos, placed.facing);
        if (cable.length() > start.cable.maxLength) {
            return "virtual_mashine.ports.msg.too_long";
        }
        if (cableAt(start.port) != null) {
            pending.remove(player.getUUID());
            sendHeldCable(player);
            return "virtual_mashine.ports.msg.occupied";
        }
        if (!player.isCreative()) {
            held.shrink(1);
        }
        pending.remove(player.getUUID());
        cables.add(cable);
        wire(cable);
        save();
        broadcastCables();
        sendHeldCable(player);
        refreshLinks();
        return "virtual_mashine.ports.msg.connected";
    }

    private String unplug(ServerPlayer player, PortRef port) {
        Pending start = pending.get(player.getUUID());
        if (start != null && start.port.equals(port)) {
            pending.remove(player.getUUID());
            sendHeldCable(player);
            return "virtual_mashine.ports.msg.cancelled";
        }
        Cable cable = cableAt(port);
        if (cable == null) {
            return "";
        }
        removeCable(cable);
        ItemStack stack = cableItem(cable.cableType(), cable.color);
        if (!player.isCreative() && !player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        save();
        broadcastCables();
        refreshLinks();
        return "virtual_mashine.ports.msg.unplugged";
    }

    /** Right-click into the air with a cable while one end is plugged in: pull it out again. */
    public boolean cancelPending(ServerPlayer player) {
        if (pending.remove(player.getUUID()) != null) {
            sendHeldCable(player);
            player.sendOverlayMessage(Component.translatable("virtual_mashine.ports.msg.cancelled"));
            return true;
        }
        return false;
    }

    private NetPayloads.PortView portView(ServerPlayer player, UUID id, DeviceType type, BlockPos pos, boolean open, String message) {
        long now = System.nanoTime();
        Pending start = pending.get(player.getUUID());
        String role = type.isVm() ? vms.provisionOf(id) : "";
        List<NetPayloads.PortInfo> ports = new ArrayList<>(type.totalPorts());
        for (int i = 0; i < type.totalPorts(); i++) {
            PortRef port = new PortRef(id, i);
            boolean console = type.isConsolePort(i);
            String name = portLabel(type, i, role);
            String mac = type.isVm() && !console ? DeviceMac.of(id, i) : "";
            Cable cable = cableAt(port);
            NetworkFabric.PortStats stats = fabric.stats(port);
            boolean activity = now - stats.lastActivity < ACTIVITY_NANOS && stats.lastActivity != 0;
            long rx = stats.rxBytes.get();
            long tx = stats.txBytes.get();
            if (type.isWirelessPort(i)) {
                String ssid = wlan.connectedSsid(id);
                boolean link = ssid != null && fabric.hasLink(port);
                ports.add(new NetPayloads.PortInfo(name, link ? NetPayloads.PortInfo.CONNECTED : NetPayloads.PortInfo.FREE, link,
                        activity && link, ssid == null ? "" : "WLAN " + ssid, "", 0, 0, false, mac, link ? wlan.linkSpeed(id) : 0, rx, tx));
                continue;
            }
            if (cable != null) {
                Cable.End other = cable.otherEnd(port);
                String peer = deviceName(other.device, other.type()) + " · " + other.type().portName(other.port);
                boolean link = console ? consoleLink(port, cable) : fabric.hasLink(port);
                int speed = link ? cable.cableType().speedMbit(cable.length()) : 0;
                ports.add(new NetPayloads.PortInfo(name, NetPayloads.PortInfo.CONNECTED, link, activity && link, peer, cable.type,
                        cable.color, (int) Math.round(cable.length()), console, mac, speed, rx, tx));
            } else if (start != null && start.port.equals(port)) {
                ports.add(new NetPayloads.PortInfo(name, NetPayloads.PortInfo.HALF, false, false, "",
                        start.cable.id, start.color, 0, console, mac, 0, rx, tx));
            } else {
                ports.add(new NetPayloads.PortInfo(name, NetPayloads.PortInfo.FREE, false, false, "", "", 0, 0, console, mac, 0, rx, tx));
            }
        }
        ItemStack held = heldCable(player);
        String heldType = held.isEmpty() ? "" : ((CableItem) held.getItem()).cableType.id;
        String pendingText = start == null ? "" : deviceName(start.port.device(), start.deviceType) + " · " + start.deviceType.portName(start.port.port());
        return new NetPayloads.PortView(id, pos, type.id, deviceName(id, type), isPowered(id, type), open, ports, heldType, pendingText,
                message, false);
    }

    /** Port name, with the role OpenWrt gave it after the automatic set-up. */
    private static String portLabel(DeviceType type, int port, String role) {
        String name = type.portName(port);
        if (role.equals("router") && !type.isConsolePort(port)) {
            return name + (port == 1 ? " WAN" : " LAN");
        }
        return name;
    }

    private String deviceName(UUID id, DeviceType type) {
        if (type.isVm()) {
            return vms.nameOf(id, type);
        }
        String kind = switch (type) {
            case SWITCH -> "Switch";
            case MODEM -> "Modem";
            case ACCESS_POINT -> "Access Point";
            default -> type.id;
        };
        return kind + " " + id.toString().substring(0, 4).toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- held cable & sync

    public void tick() {
        if (++ticks % 20 != 0) {
            return;
        }
        wlan.tick();
        // Cheap safety net: carrier states only change on the guest when they differ.
        refreshLinks();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String dimension = dimensionOf(player.level());
            if (!dimension.equals(syncedDimension.get(player.getUUID()))) {
                syncedDimension.put(player.getUUID(), dimension);
                sendCables(player);
                sendHeldCable(player);
            }
            Pending start = pending.get(player.getUUID());
            if (start != null && heldCable(player).isEmpty()) {
                // Put the cable away: it is pulled out of the first port again.
                pending.remove(player.getUUID());
                sendHeldCable(player);
            }
        }
    }

    public void onPlayerLeft(UUID player) {
        pending.remove(player);
        syncedDimension.remove(player);
    }

    private void sendHeldCable(ServerPlayer player) {
        Pending start = pending.get(player.getUUID());
        if (start == null) {
            ServerPlayNetworking.send(player, new NetPayloads.HeldCable(false, 0, 0, 0, 0));
            return;
        }
        Vec3 socket = DeviceGeometry.socket(start.deviceType, start.pos, start.facing, start.port.port());
        ServerPlayNetworking.send(player, new NetPayloads.HeldCable(true, socket.x, socket.y, socket.z, start.color));
    }

    private void broadcastCables() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendCables(player);
        }
    }

    private void sendCables(ServerPlayer player) {
        String dimension = dimensionOf(player.level());
        List<NetPayloads.CableVisual> visuals = new ArrayList<>();
        for (Cable cable : cables) {
            if (cable.a.dimension.equals(dimension)) {
                Vec3 a = DeviceGeometry.socket(cable.a);
                Vec3 b = DeviceGeometry.socket(cable.b);
                visuals.add(new NetPayloads.CableVisual(a.x, a.y, a.z, b.x, b.y, b.z, cable.color));
            }
        }
        ServerPlayNetworking.send(player, new NetPayloads.CableSync(visuals));
    }

    // ---------------------------------------------------------------- modem

    /** The internet uplink: QEMU's user-mode NAT on its own, handing out addresses like a home router. */
    private void ensureModem(UUID id) {
        Process running = modems.get(id);
        if (running != null && running.isAlive()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = modemStarted.get(id);
        if (last != null && now - last < 30_000) {
            return;
        }
        modemStarted.put(id, now);
        if (running != null) {
            stopModem(id);
        }
        Path qemu = QemuLocator.findSystemEmulator();
        if (qemu == null) {
            return;
        }
        try {
            NetworkFabric.UdpBinding binding = fabric.openUdpPorts(id, 1)[0];
            Path dir = root.resolve(id.toString());
            Files.createDirectories(dir);
            List<String> command = List.of(qemu.toString(), "-M", "none", "-nodefaults", "-display", "none",
                    "-monitor", "none", "-serial", "none",
                    "-netdev", "dgram,id=lan,local.type=inet,local.host=127.0.0.1,local.port=" + binding.qemuPort()
                            + ",remote.type=inet,remote.host=127.0.0.1,remote.port=" + binding.modPort(),
                    "-netdev", "user,id=wan,net=192.168.178.0/24,host=192.168.178.1,dhcpstart=192.168.178.20,dns=192.168.178.2",
                    "-netdev", "hubport,id=h1,hubid=0,netdev=lan",
                    "-netdev", "hubport,id=h2,hubid=0,netdev=wan");
            Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(dir.resolve("modem.log").toFile()).start();
            modems.put(id, process);
            VmManager.trackProcess(process);
            refreshLinks();
        } catch (IOException e) {
            Virtual_mashine.LOGGER.warn("Could not start the internet modem {}", id, e);
            fabric.closeUdpPorts(id);
        }
    }

    private void stopModem(UUID id) {
        modemStarted.remove(id);
        Process process = modems.remove(id);
        if (process != null) {
            process.destroyForcibly();
            VmManager.untrackProcess(process);
        }
        fabric.closeUdpPorts(id);
    }

    public void close() {
        for (UUID id : List.copyOf(modems.keySet())) {
            stopModem(id);
        }
        save();
        fabric.close();
    }

    // ---------------------------------------------------------------- storage

    private @Nullable Cable cableAt(PortRef port) {
        for (Cable cable : cables) {
            if (cable.uses(port)) {
                return cable;
            }
        }
        return null;
    }

    private void removeCable(Cable cable) {
        cables.remove(cable);
        fabric.disconnect(cable.a.ref());
    }

    private static ItemStack heldCable(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof CableItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack cableItem(CableType type, int color) {
        ItemStack stack = new ItemStack(ModRegistry.CABLES.get(type));
        if (color != type.defaultColor) {
            stack.set(ModRegistry.CABLE_COLOR, color);
        }
        return stack;
    }

    private static String dimensionOf(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                List<Cable> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Cable>>() { }.getType());
                if (loaded != null) {
                    for (Cable cable : loaded) {
                        if (cable != null && cable.a != null && cable.b != null && cable.id != null) {
                            cables.add(cable);
                            wire(cable);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Virtual_mashine.LOGGER.warn("Could not read {}", file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new ArrayList<>(cables)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Virtual_mashine.LOGGER.warn("Could not save {}", file, e);
        }
    }
}
