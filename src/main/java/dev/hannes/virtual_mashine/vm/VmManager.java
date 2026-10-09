package dev.hannes.virtual_mashine.vm;

import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.net.NetworkManager;
import dev.hannes.virtual_mashine.network.PcActionPayload;
import dev.hannes.virtual_mashine.network.PcInfoPayload;
import dev.hannes.virtual_mashine.network.TunnelPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** All PCs of the currently running world (singleplayer / LAN host). */
public final class VmManager {
    private static volatile @Nullable VmManager instance;

    private final MinecraftServer server;
    private final Path root;
    private final Path worldIsoDir;
    private final Path globalIsoDir;
    private final Map<UUID, VirtualMachine> machines = new ConcurrentHashMap<>();
    /** Which PCs each player opened through a block; only those may be streamed to them. */
    private final Map<UUID, Set<UUID>> openedBy = new ConcurrentHashMap<>();
    private final VncTunnelServer tunnels = new VncTunnelServer();
    private final NetworkManager network;

    private VmManager(MinecraftServer server) {
        this.server = server;
        this.root = server.getWorldPath(LevelResource.ROOT).resolve(Virtual_mashine.MOD_ID).toAbsolutePath().normalize();
        this.worldIsoDir = root.resolve("isos");
        this.globalIsoDir = FabricLoader.getInstance().getGameDir().resolve("vm_isos").toAbsolutePath().normalize();
        try {
            Files.createDirectories(worldIsoDir);
            Files.createDirectories(globalIsoDir);
        } catch (IOException e) {
            Virtual_mashine.LOGGER.warn("Could not create ISO folders", e);
        }
        try {
            this.network = new NetworkManager(server, this, root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the network fabric", e);
        }
    }

    public NetworkManager network() {
        return network;
    }

    Path globalIsoDir() {
        return globalIsoDir;
    }

    /** The machine if it is running right now, else null. */
    public @Nullable VirtualMachine runningMachine(UUID id) {
        VirtualMachine vm = machines.get(id);
        return vm != null && vm.state() == VirtualMachine.State.RUNNING ? vm : null;
    }

    /** OpenWrt role of a device ("router" / "switch") once it is set up, else empty. */
    public String provisionOf(UUID id) {
        VirtualMachine vm = machines.get(id);
        return vm != null && vm.config().provisioned ? vm.config().provision : "";
    }

    public static void onServerTick(MinecraftServer server) {
        VmManager manager = instance;
        if (manager != null) {
            manager.network.tick();
        }
    }

    public static void trackProcess(Process process) {
        VirtualMachine.LIVE_PROCESSES.add(process);
    }

    public static void untrackProcess(Process process) {
        VirtualMachine.LIVE_PROCESSES.remove(process);
    }

    public static @Nullable VmManager get() {
        return instance;
    }

    public static void onServerStarted(MinecraftServer server) {
        instance = new VmManager(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        VmManager manager = instance;
        instance = null;
        if (manager != null) {
            manager.parkAll();
        }
    }

    /** Last resort when the game dies without stopping the world: no QEMU may outlive it. */
    public static void killAllProcesses() {
        for (Process process : VirtualMachine.LIVE_PROCESSES) {
            process.destroyForcibly();
        }
    }

    // ---------------------------------------------------------------- called from the game

    public void openFor(ServerPlayer player, UUID id, DeviceType type) {
        openedBy.computeIfAbsent(player.getUUID(), key -> ConcurrentHashMap.newKeySet()).add(id);
        ServerPlayNetworking.send(player, info(machine(id, type), true));
    }

    /** Only when there is something to boot: a fresh router waits until it got OpenWrt or an ISO. */
    public boolean shouldAutoStart(UUID id, DeviceType type) {
        VirtualMachine vm = machine(id, type);
        VmConfig config = vm.config();
        return config.autoStart && (vm.diskExists() || !config.iso.isEmpty() || !config.diskImage.isEmpty());
    }

    public void autoStart(UUID id, DeviceType type) {
        machine(id, type).start();
    }

    public String nameOf(UUID id, DeviceType type) {
        return machine(id, type).config().name;
    }

    /** What a display block shows: {width, height, VNC port (0 = off)}. */
    public int[] displayState(UUID id) {
        VirtualMachine vm = machines.get(id);
        if (vm == null) {
            return new int[]{2, 1, 0};
        }
        VmConfig c = vm.config();
        return new int[]{c.displayWidth, c.displayHeight, vm.state() == VirtualMachine.State.RUNNING ? vm.vncPort() : 0};
    }

    public List<VirtualMachine> runningMachines() {
        List<VirtualMachine> running = new ArrayList<>();
        for (VirtualMachine vm : machines.values()) {
            if (vm.state() == VirtualMachine.State.RUNNING) {
                running.add(vm);
            }
        }
        return running;
    }

    // ---------------------------------------------------------------- screen tunnels for remote players

    public void openTunnel(ServerPlayer player, TunnelPayloads.Open payload) {
        Set<UUID> allowed = openedBy.get(player.getUUID());
        VirtualMachine vm = machines.get(payload.pcId());
        // Displays are watched by everyone standing near them, without opening them first.
        boolean permitted = vm != null && (allowed != null && allowed.contains(payload.pcId())
                || vm.deviceType() == DeviceType.DISPLAY && network.isNear(payload.pcId(), player, 96));
        tunnels.open(player, payload.tunnelId(), permitted ? vm.vncPort() : -1);
    }

    public void tunnelData(ServerPlayer player, TunnelPayloads.Data payload) {
        tunnels.data(player, payload.tunnelId(), payload.bytes());
    }

    public void closeTunnel(ServerPlayer player, TunnelPayloads.Close payload) {
        tunnels.close(player, payload.tunnelId());
    }

    public void onPlayerLeft(UUID player) {
        tunnels.closeAllOf(player);
        openedBy.remove(player);
        network.onPlayerLeft(player);
    }

    public boolean isPowered(UUID id) {
        VirtualMachine vm = machines.get(id);
        return vm != null && vm.isPowered();
    }

    public void onPcRemoved(UUID id) {
        VirtualMachine vm = machines.get(id);
        if (vm != null && vm.isPowered()) {
            vm.parkAsync();
        }
    }

    public void handleAction(ServerPlayer player, PcActionPayload payload) {
        UUID id = payload.pcId();
        if (!machines.containsKey(id) && !Files.isDirectory(root.resolve(id.toString()))) {
            return; // Only PCs that were opened through a block.
        }
        VirtualMachine vm = machine(id, DeviceType.PC);
        switch (payload.action()) {
            case PcActionPayload.PORTS -> network.openPortViewOf(player, id);
            case PcActionPayload.START -> vm.start();
            case PcActionPayload.SHUTDOWN -> vm.acpiShutdown();
            case PcActionPayload.POWER_OFF -> vm.powerOff();
            case PcActionPayload.RESET -> vm.reset();
            case PcActionPayload.RESTART -> vm.restart();
            case PcActionPayload.FREEZE -> vm.freeze();
            case PcActionPayload.DISCARD_STATE -> vm.discardFrozenState();
            case PcActionPayload.DOWNLOAD_OPENWRT -> vm.downloadOpenWrt();
            case PcActionPayload.DOWNLOAD_ANDROID -> vm.downloadAndroid();
            case PcActionPayload.RESET_DISK -> vm.resetDisk();
            case PcActionPayload.CONFIG -> {
                try {
                    vm.updateConfig(VmConfig.fromJson(payload.argument()));
                } catch (RuntimeException e) {
                    Virtual_mashine.LOGGER.warn("Ignoring invalid PC config from {}", player.getName().getString(), e);
                }
            }
            case PcActionPayload.REFRESH -> ServerPlayNetworking.send(player, info(vm, false));
            default -> Virtual_mashine.LOGGER.debug("Unknown PC action {}", payload.action());
        }
    }

    // ---------------------------------------------------------------- called from machines

    void onVmChanged(VirtualMachine vm) {
        server.execute(() -> {
            PcInfoPayload payload = info(vm, false);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerPlayNetworking.send(player, payload);
            }
        });
    }

    /** Looks an ISO up by file name in the world's and the game's ISO folder. */
    @Nullable Path resolveIso(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        for (Path dir : List.of(worldIsoDir, globalIsoDir)) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- internals

    /** @param type only used when the machine has no config yet (a freshly placed device) */
    private VirtualMachine machine(UUID id, DeviceType type) {
        return machines.computeIfAbsent(id, key -> new VirtualMachine(key, root.resolve(key.toString()), this, type));
    }

    private PcInfoPayload info(VirtualMachine vm, boolean open) {
        return new PcInfoPayload(
                vm.id(),
                open,
                vm.state().name(),
                vm.vncPort(),
                vm.canFreeze(),
                vm.diskExists(),
                vm.messageKey(),
                vm.messageDetail(),
                vm.config().toJson(),
                listFiles(ISO_ENDINGS),
                listFiles(IMAGE_ENDINGS),
                globalIsoDir.toString(),
                QemuLocator.findSystemEmulator() != null,
                HostInfo.cpuCount(),
                HostInfo.maxRamMb(),
                HostInfo.freeRamMb()
        );
    }

    private static final List<String> ISO_ENDINGS = List.of(".iso");
    /** Ready-made disks: OpenWrt, vJunos, cloud images, exported VMs, ... */
    private static final List<String> IMAGE_ENDINGS = List.of(".qcow2", ".img", ".img.gz", ".raw", ".vmdk", ".vdi", ".vhd", ".vhdx");

    private List<String> listFiles(List<String> endings) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Path dir : List.of(worldIsoDir, globalIsoDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> endings.stream().anyMatch(name.toLowerCase(Locale.ROOT)::endsWith))
                        .forEach(names::add);
            } catch (IOException e) {
                Virtual_mashine.LOGGER.warn("Could not list {}", dir, e);
            }
        }
        return new ArrayList<>(names);
    }

    /** Freezes (or shuts down) every running PC before the world is saved and closed. */
    private void parkAll() {
        tunnels.closeAll();
        try {
            parkMachines();
        } finally {
            network.close();
        }
    }

    private void parkMachines() {
        List<Future<?>> pending = new ArrayList<>();
        for (VirtualMachine vm : machines.values()) {
            if (vm.isPowered()) {
                Virtual_mashine.LOGGER.info("Parking PC {} before the world closes", vm.id());
                pending.add(vm.parkAsync());
            }
        }
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ModConfig.get().shutdownWaitSeconds() + 180L);
        for (Future<?> future : pending) {
            try {
                future.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Virtual_mashine.LOGGER.warn("A PC did not stop in time", e);
            }
        }
        killAllProcesses();
        for (VirtualMachine vm : machines.values()) {
            vm.dispose();
        }
        machines.clear();
    }
}
