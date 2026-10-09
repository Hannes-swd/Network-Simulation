package dev.hannes.virtual_mashine.vm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.net.NetworkFabric;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * One PC: a QEMU process with its disk image in {@code <world>/virtual_mashine/<id>/}.
 * Every operation runs on the machine's own worker thread, so callers never block on QEMU.
 */
public final class VirtualMachine {
    public enum State { OFF, STARTING, RUNNING, SAVING, STOPPING }

    /** Every QEMU process this game started, so a crashing game can still take them down. */
    static final Set<Process> LIVE_PROCESSES = ConcurrentHashMap.newKeySet();
    /** VNC ports handed to a QEMU that may not have bound them yet (several PCs starting at once). */
    private static final Set<Integer> RESERVED_VNC_PORTS = ConcurrentHashMap.newKeySet();

    private static final String SNAPSHOT_TAG = "minecraft_freeze";
    /** Kiosk system of display blocks (Debian + Firefox full screen), looked for in the ISO folder. */
    static final String DISPLAY_IMAGE = "netz-display.qcow2";
    private static final int SAVEVM_TIMEOUT_MS = 30 * 60 * 1000;

    private final UUID id;
    private final Path dir;
    private final VmManager manager;
    private final ExecutorService worker;

    private volatile VmConfig config;
    private volatile State state = State.OFF;
    private volatile String messageKey = "";
    private volatile String messageDetail = "";
    private volatile int vncPort = -1;
    private volatile boolean freezeSupported;

    // Only touched on the worker thread.
    private @Nullable Process process;
    private @Nullable QmpClient qmp;
    /** Network cards wired to the mod's cables while running; 0 when off or not in cable mode. */
    private volatile int cablePorts;
    private volatile @Nullable SerialLine serial;
    private int reservedVncPort = -1;
    /** This start boots the Android installer straight from the image (worker thread only). */
    private boolean installerBoot;
    /** Last carrier state pushed to each card: -1 unknown, 0 down, 1 up. */
    private volatile int[] linkState = new int[0];

    VirtualMachine(UUID id, Path dir, VmManager manager, DeviceType typeForNew) {
        this.id = id;
        this.dir = dir;
        this.manager = manager;
        this.config = loadConfig(typeForNew);
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "virtual_mashine-" + id.toString().substring(0, 8));
            thread.setDaemon(true);
            return thread;
        });
    }

    // ---------------------------------------------------------------- state for the UI

    public UUID id() {
        return id;
    }

    public VmConfig config() {
        return config;
    }

    public DeviceType deviceType() {
        return DeviceType.byId(config.type);
    }

    /** The serial port while the machine runs (console cable, OpenWrt set-up). */
    public @Nullable SerialLine serial() {
        return state == State.RUNNING ? serial : null;
    }

    /** Number of network cards that hang on cables, while the machine runs. */
    public int cablePorts() {
        return state == State.RUNNING ? cablePorts : 0;
    }

    /** Plugs or unplugs the cable of one network card as the guest sees it (carrier on/off). */
    public void updateLink(int port, boolean up) {
        int[] states = linkState;
        if (port >= states.length || states[port] == (up ? 1 : 0)) {
            return;
        }
        states[port] = up ? 1 : 0;
        worker.execute(() -> {
            if (qmp == null) {
                return;
            }
            try {
                JsonObject args = new JsonObject();
                args.addProperty("name", "nic" + port);
                args.addProperty("up", up);
                qmp.execute("set_link", args);
            } catch (IOException e) {
                Virtual_mashine.LOGGER.debug("set_link failed for {} port {}", id, port, e);
            }
        });
    }

    public State state() {
        return state;
    }

    public boolean isPowered() {
        return state != State.OFF;
    }

    public int vncPort() {
        return state == State.RUNNING ? vncPort : -1;
    }

    public String messageKey() {
        return messageKey;
    }

    public String messageDetail() {
        return messageDetail;
    }

    public boolean diskExists() {
        return Files.isRegularFile(diskPath());
    }

    /** While running this is what QEMU reports; while off it is a prediction from the settings. */
    public boolean canFreeze() {
        if (state == State.RUNNING || state == State.SAVING) {
            return freezeSupported;
        }
        return !config.acceleration || HostInfo.accelerationAllowsFreezing();
    }

    // ---------------------------------------------------------------- requests (asynchronous)

    public void start() {
        worker.execute(this::doStart);
    }

    public void acpiShutdown() {
        worker.execute(() -> {
            if (state == State.RUNNING && qmp != null) {
                try {
                    qmp.execute("system_powerdown", null);
                    setMessage("virtual_mashine.msg.shutdown_requested", "");
                } catch (IOException e) {
                    setMessage("virtual_mashine.msg.command_failed", e.getMessage());
                }
                manager.onVmChanged(this);
            }
        });
    }

    public void powerOff() {
        worker.execute(this::doPowerOff);
    }

    /** A new QEMU process, e.g. so a display picks up its new page and size. */
    public void restart() {
        worker.execute(() -> {
            if (process != null) {
                doPowerOff();
            }
            doStart();
        });
    }

    public void reset() {
        worker.execute(() -> {
            if (state == State.RUNNING && qmp != null) {
                try {
                    qmp.execute("system_reset", null);
                    setMessage("", "");
                } catch (IOException e) {
                    setMessage("virtual_mashine.msg.command_failed", e.getMessage());
                }
                manager.onVmChanged(this);
            }
        });
    }

    public void freeze() {
        worker.execute(this::doFreeze);
    }

    public void discardFrozenState() {
        worker.execute(() -> {
            if (state != State.OFF || !config.frozen) {
                return;
            }
            Path qemuImg = QemuLocator.findImageTool();
            if (qemuImg != null && diskExists()) {
                try {
                    runTool(List.of(qemuImg.toString(), "snapshot", "-d", SNAPSHOT_TAG, diskPath().toString()), 120);
                } catch (IOException e) {
                    Virtual_mashine.LOGGER.warn("Could not delete snapshot of {}", id, e);
                }
            }
            config.frozen = false;
            saveConfig();
            setMessage("virtual_mashine.msg.state_discarded", "");
            manager.onVmChanged(this);
        });
    }

    public void updateConfig(VmConfig incoming) {
        worker.execute(() -> {
            VmConfig wanted = incoming.copy().sanitized();
            VmConfig current = config;
            boolean running = process != null;
            boolean locked = current.frozen;
            current.name = wanted.name;
            if (!running && !locked) {
                current.ramMb = wanted.ramMb;
                current.cpus = wanted.cpus;
                current.acceleration = wanted.acceleration;
                current.sound = wanted.sound;
                current.netMode = wanted.netMode;
                current.nicModel = wanted.nicModel;
            }
            current.autoStart = wanted.autoStart;
            current.typingLayout = wanted.typingLayout;
            current.clipboardSharing = wanted.clipboardSharing;
            current.displayUrl = wanted.displayUrl;
            current.displayWidth = wanted.displayWidth;
            current.displayHeight = wanted.displayHeight;
            if (!locked) {
                current.bootFromCd = wanted.bootFromCd;
            }
            if (!running && !diskExists()) {
                current.diskGb = wanted.diskGb;
                if (!java.util.Objects.equals(current.diskImage, wanted.diskImage)) {
                    current.diskImage = wanted.diskImage;
                    // A different image: an OpenWrt role picked by the download no longer applies.
                    current.provision = wanted.diskImage.toLowerCase(Locale.ROOT).startsWith("openwrt")
                            ? (deviceType() == DeviceType.MANAGED_SWITCH ? OpenWrtProvisioner.SWITCH : OpenWrtProvisioner.ROUTER) : "";
                    current.provisioned = false;
                }
            }
            if (!locked && !Objects.equals(current.iso, wanted.iso)) {
                current.iso = wanted.iso;
                if (running) {
                    changeMedium();
                }
            }
            saveConfig();
            manager.onVmChanged(this);
        });
    }

    /** Freezes the machine if possible, otherwise asks the guest to shut down and pulls the plug after a timeout. */
    public Future<?> parkAsync() {
        return worker.submit(() -> {
            if (process == null) {
                return;
            }
            if (state == State.RUNNING && freezeSupported) {
                doFreeze();
            }
            Process p = process;
            if (p == null) {
                return;
            }
            try {
                if (qmp != null) {
                    qmp.execute("system_powerdown", null);
                }
                setState(State.STOPPING, "virtual_mashine.msg.shutdown_requested", "");
                if (!p.waitFor(ModConfig.get().shutdownWaitSeconds(), TimeUnit.SECONDS)) {
                    Virtual_mashine.LOGGER.info("PC {} did not shut down in time, powering off", id);
                }
            } catch (IOException e) {
                Virtual_mashine.LOGGER.warn("Graceful shutdown of {} failed", id, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            doPowerOff();
        });
    }

    void dispose() {
        worker.shutdown();
    }

    // ---------------------------------------------------------------- worker thread

    private void doStart() {
        if (state != State.OFF) {
            return;
        }
        Path qemu = QemuLocator.findSystemEmulator();
        if (qemu == null) {
            setMessage("virtual_mashine.msg.qemu_missing", "");
            manager.onVmChanged(this);
            return;
        }
        boolean resume = config.frozen;
        setState(State.STARTING, resume ? "virtual_mashine.msg.resuming" : "virtual_mashine.msg.starting", "");
        Process started = null;
        try {
            Files.createDirectories(dir);
            ensureDisk();
            killStaleProcess();

            int display = findFreeVncPort();
            reservedVncPort = display;
            int qmpPort = findFreePort();
            int serialPort = findFreePort();
            NetworkFabric.UdpBinding[] wires = new NetworkFabric.UdpBinding[0];
            if (VmConfig.NET_CABLE.equals(config.netMode)) {
                wires = manager.network().openVmPorts(id, deviceType().ports);
            } else if (VmConfig.NET_DIRECT.equals(config.netMode)) {
                wires = new NetworkFabric.UdpBinding[]{manager.network().openDirectUplink(id, dir)};
            }
            installerBoot = !resume && AndroidSetup.PROVISION.equals(config.provision) && !config.provisioned;
            if (installerBoot) {
                Path image = manager.resolveIso(config.iso);
                if (image == null) {
                    throw new IOException("Android image missing: " + config.iso);
                }
                AndroidSetup.prepareBoot(image, dir);
            }
            // A frozen machine resumes on the hardware it was frozen with; a fresh start follows the setting.
            config.clipboardPort = resume ? config.clipboardPort : config.clipboardSharing;
            List<String> command = buildCommand(qemu, display, qmpPort, serialPort, resume, wires);
            Virtual_mashine.LOGGER.info("Starting PC {}: {}", id, String.join(" ", command));

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logPath().toFile());
            started = builder.start();
            process = started;
            LIVE_PROCESSES.add(started);
            Files.writeString(dir.resolve("qemu.pid"), Long.toString(started.pid()));

            qmp = QmpClient.connect(qmpPort, started, 30_000);
            freezeSupported = detectFreezeSupport(qmp);
            if (resume) {
                // -loadvm already restored RAM and disk. Drop the snapshot so a crash later cannot roll the disk back.
                qmp.hmp("delvm " + SNAPSHOT_TAG, SAVEVM_TIMEOUT_MS);
                config.frozen = false;
                saveConfig();
            }
            vncPort = display;
            SerialLine line = new SerialLine(serialPort, id.toString().substring(0, 8));
            serial = line;
            if (installerBoot) {
                AndroidSetup.attach(line, () -> worker.execute(this::finishAndroidSetup));
            } else if (!config.provision.isEmpty() && !config.provisioned && !AndroidSetup.PROVISION.equals(config.provision)) {
                OpenWrtProvisioner.attach(line, config.provision, () -> worker.execute(this::markProvisioned));
                setMessage("virtual_mashine.msg.provisioning", "");
            }
            if (VmConfig.NET_CABLE.equals(config.netMode) && wires.length > 0) {
                int[] unknown = new int[wires.length];
                java.util.Arrays.fill(unknown, -1);
                linkState = unknown;
                cablePorts = wires.length;
            }
            watchForExit(started);
            String startMessage = resume ? "virtual_mashine.msg.resumed" : isoMissing() ? "virtual_mashine.msg.iso_missing"
                    : installerBoot ? "virtual_mashine.msg.android_installing"
                    : !config.provision.isEmpty() && !config.provisioned ? "virtual_mashine.msg.provisioning" : "";
            setState(State.RUNNING, startMessage, "");
            if (VmConfig.NET_CABLE.equals(config.netMode) && wires.length > 0) {
                manager.network().vmNetworkChanged(id, true);
            }
        } catch (IOException | RuntimeException e) {
            Virtual_mashine.LOGGER.warn("Could not start PC {}", id, e);
            if (started != null) {
                started.destroyForcibly();
                try {
                    started.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            cleanUpAfterExit(started);
            String key = resume ? "virtual_mashine.msg.resume_failed" : "virtual_mashine.msg.start_failed";
            setState(State.OFF, key, joinDetail(e.getMessage(), readLogTail()));
        }
    }

    private void doFreeze() {
        Process p = process;
        QmpClient monitor = qmp;
        if (state != State.RUNNING || p == null || monitor == null) {
            return;
        }
        if (!freezeSupported) {
            setMessage("virtual_mashine.msg.freeze_unsupported", "");
            manager.onVmChanged(this);
            return;
        }
        setState(State.SAVING, "virtual_mashine.msg.saving", "");
        try {
            monitor.execute("stop", null);
            String output = monitor.hmp("savevm " + SNAPSHOT_TAG, SAVEVM_TIMEOUT_MS);
            String snapshots = monitor.hmp("info snapshots", 30_000);
            if (!snapshots.contains(SNAPSHOT_TAG)) {
                throw new IOException(output.isBlank() ? "Snapshot was not created" : output.trim());
            }
            config.frozen = true;
            saveConfig();
            try {
                monitor.execute("quit", null);
            } catch (IOException ignored) {
                // QEMU closes the monitor while quitting.
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            cleanUpAfterExit(p);
            setState(State.OFF, "virtual_mashine.msg.frozen", "");
        } catch (IOException e) {
            Virtual_mashine.LOGGER.warn("Freezing PC {} failed", id, e);
            try {
                monitor.execute("cont", null);
                setState(State.RUNNING, "virtual_mashine.msg.freeze_failed", e.getMessage());
            } catch (IOException ignored) {
                setState(State.RUNNING, "virtual_mashine.msg.freeze_failed", e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void doPowerOff() {
        Process p = process;
        if (p == null) {
            return;
        }
        setState(State.STOPPING, "", "");
        if (qmp != null) {
            try {
                qmp.execute("quit", null);
            } catch (IOException ignored) {
                // Connection drops while QEMU quits.
            }
        }
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cleanUpAfterExit(p);
        setState(State.OFF, "", "");
    }

    /**
     * Notices when the guest powers itself off. Uses a plain thread rather than {@code Process.onExit()},
     * whose callbacks may run on the JDK's small-stack process reaper threads.
     */
    private void watchForExit(Process p) {
        Thread watcher = new Thread(() -> {
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            worker.execute(() -> onProcessExit(p));
        }, "virtual_mashine-watch-" + id.toString().substring(0, 8));
        watcher.setDaemon(true);
        watcher.start();
    }

    private void onProcessExit(Process p) {
        if (p != process) {
            return; // Already handled by the operation that stopped it.
        }
        State before = state;
        int exitCode = p.exitValue();
        cleanUpAfterExit(p);
        if (exitCode != 0 && before == State.RUNNING) {
            setState(State.OFF, "virtual_mashine.msg.crashed", readLogTail());
        } else {
            setState(State.OFF, "", "");
        }
    }

    private void cleanUpAfterExit(@Nullable Process p) {
        if (qmp != null) {
            qmp.close();
            qmp = null;
        }
        SerialLine line = serial;
        if (line != null) {
            line.close();
            serial = null;
        }
        if (cablePorts > 0 || VmConfig.NET_CABLE.equals(config.netMode)) {
            cablePorts = 0;
            linkState = new int[0];
            manager.network().vmNetworkChanged(id, false);
        }
        if (VmConfig.NET_DIRECT.equals(config.netMode)) {
            manager.network().closeDirectUplink(id);
            manager.network().vmNetworkChanged(id, false);
        }
        if (p != null) {
            LIVE_PROCESSES.remove(p);
        }
        process = null;
        vncPort = -1;
        if (reservedVncPort > 0) {
            RESERVED_VNC_PORTS.remove(reservedVncPort);
            reservedVncPort = -1;
        }
        try {
            Files.deleteIfExists(dir.resolve("qemu.pid"));
        } catch (IOException ignored) {
            // Harmless, it is checked against the process command line anyway.
        }
    }

    private void changeMedium() {
        if (qmp == null) {
            return;
        }
        try {
            JsonObject args = new JsonObject();
            args.addProperty("id", "cdrom0");
            Path iso = manager.resolveIso(config.iso);
            if (iso == null) {
                args.addProperty("force", true);
                qmp.execute("eject", args);
            } else {
                args.addProperty("filename", iso.toString());
                args.addProperty("format", "raw");
                args.addProperty("read-only-mode", "read-only");
                args.addProperty("force", true);
                qmp.execute("blockdev-change-medium", args);
            }
            setMessage("", "");
        } catch (IOException e) {
            setMessage("virtual_mashine.msg.command_failed", e.getMessage());
        }
    }

    private List<String> buildCommand(Path qemu, int vncPort, int qmpPort, int serialPort, boolean resume, NetworkFabric.UdpBinding[] wires) {
        VmConfig c = config;
        List<String> cmd = new ArrayList<>();
        cmd.add(qemu.toString());
        cmd.add("-name");
        cmd.add("guest=" + escape("Minecraft " + c.name));
        cmd.add("-machine");
        cmd.add("q35");
        if (c.acceleration) {
            switch (HostInfo.OS) {
                case WINDOWS -> cmd.addAll(List.of("-accel", "whpx,kernel-irqchip=off"));
                case LINUX -> cmd.addAll(List.of("-accel", "kvm"));
                case MAC -> cmd.addAll(List.of("-accel", "hvf"));
            }
        }
        // Fallback when no hypervisor is available (QEMU uses the first accelerator that works).
        cmd.addAll(List.of("-accel", "tcg,thread=multi"));
        // WHPX cannot save and restore the extended (AVX) register state ("failed to get xsave state"), so
        // programs using AVX crash at random (Android's init does so every boot). "max" minus XSAVE is not
        // enough: it still reports AVX-512, which new kernels then use and crash on. A CPU model without AVX
        // (x86-64-v2 level, plus a few features that do not need XSAVE) is consistent for every guest.
        boolean whpx = c.acceleration && HostInfo.OS == HostInfo.Os.WINDOWS;
        cmd.addAll(List.of("-cpu", whpx ? "Westmere,+rdrand,+smep,+smap,+fsgsbase" : "max"));
        cmd.addAll(List.of("-smp", Integer.toString(c.cpus)));
        cmd.addAll(List.of("-m", c.ramMb + "M"));

        cmd.addAll(List.of("-drive", "if=none,id=hd0,format=qcow2,file=" + escape(diskPath().toString())));
        // With -kernel, QEMU's kernel loader takes boot index 0 itself.
        int firstBoot = installerBoot ? 1 : 0;
        cmd.addAll(List.of("-device", "ide-hd,drive=hd0,bus=ide.0,bootindex=" + (firstBoot + (c.bootFromCd ? 1 : 0))));
        Path iso = manager.resolveIso(c.iso);
        String cdrom = "if=none,id=cd0,media=cdrom,readonly=on" + (iso != null ? ",format=raw,file=" + escape(iso.toString()) : "");
        cmd.addAll(List.of("-drive", cdrom));
        cmd.addAll(List.of("-device", "ide-cd,drive=cd0,bus=ide.1,id=cdrom0,bootindex=" + (firstBoot + (c.bootFromCd ? 0 : 1))));

        cmd.addAll(List.of("-vga", "std"));
        if (deviceType() == DeviceType.DISPLAY) {
            // The guest's preferred screen mode (EDID) matches the display's shape, and the page comes
            // through fw_cfg, which the kiosk system reads at start.
            int perBlock = c.displayPixelsPerBlock();
            cmd.addAll(List.of("-global", "VGA.xres=" + perBlock * c.displayWidth, "-global", "VGA.yres=" + perBlock * c.displayHeight));
            if (!c.displayUrl.isEmpty()) {
                cmd.addAll(List.of("-fw_cfg", "name=opt/netz/url,string=" + escape(c.displayUrl)));
            }
        }
        // Absolute pointer device, so the guest cursor follows the Minecraft mouse exactly.
        cmd.addAll(List.of("-device", "qemu-xhci,id=xhci", "-device", "usb-tablet,bus=xhci.0"));
        switch (c.netMode) {
            case VmConfig.NET_CABLE -> {
                // Each socket of the device is a network card whose frames go through the mod's cables.
                for (int port = 0; port < wires.length; port++) {
                    cmd.addAll(List.of("-netdev", "dgram,id=net" + port
                            + ",local.type=inet,local.host=127.0.0.1,local.port=" + wires[port].qemuPort()
                            + ",remote.type=inet,remote.host=127.0.0.1,remote.port=" + wires[port].modPort()));
                    cmd.addAll(List.of("-device", c.nicModel + ",netdev=net" + port + ",id=nic" + port + ",mac=" + macAddress(port)));
                }
            }
            // Online without cabling: one card wired invisibly to a private, filtered uplink (see NetworkManager).
            case VmConfig.NET_DIRECT -> {
                if (wires.length > 0) {
                    cmd.addAll(List.of("-netdev", "dgram,id=net0,local.type=inet,local.host=127.0.0.1,local.port=" + wires[0].qemuPort()
                            + ",remote.type=inet,remote.host=127.0.0.1,remote.port=" + wires[0].modPort()));
                    cmd.addAll(List.of("-device", c.nicModel + ",netdev=net0,id=nic0,mac=" + macAddress(0)));
                } else {
                    cmd.addAll(List.of("-nic", "none"));
                }
            }
            default -> cmd.addAll(List.of("-nic", "none"));
        }
        if (c.sound) {
            String backend = switch (HostInfo.OS) {
                case WINDOWS -> "dsound";
                case MAC -> "coreaudio";
                case LINUX -> "pa";
            };
            cmd.addAll(List.of("-audiodev", backend + ",id=snd0", "-device", "ich9-intel-hda", "-device", "hda-output,audiodev=snd0"));
        }
        if (c.clipboardPort) {
            // Shared clipboard with the viewer. Needs spice-vdagent in the guest, which most Linux desktops ship.
            // Added after all other PCI devices so existing guests keep their device addresses.
            cmd.addAll(List.of("-chardev", "qemu-vdagent,id=vdagent,name=vdagent,clipboard=on,mouse=off",
                    "-device", "virtio-serial-pci,id=vdagent-serial",
                    "-device", "virtserialport,bus=vdagent-serial.0,chardev=vdagent,name=com.redhat.spice.0"));
        }
        // lock-key-sync off: the viewer sends raw scancodes (Caps Lock included). With it on, QEMU guesses
        // Caps Lock from the keysym's case and turns Shift+S into a lower-case s.
        cmd.addAll(List.of("-display", "none", "-vnc", "127.0.0.1:" + (vncPort - 5900) + ",lock-key-sync=off"));
        cmd.addAll(List.of("-qmp", "tcp:127.0.0.1:" + qmpPort + ",server=on,wait=off"));
        // COM1 / ttyS0: reachable through the console cable and used for the OpenWrt set-up.
        cmd.addAll(List.of("-monitor", "none", "-serial", "tcp:127.0.0.1:" + serialPort + ",server=on,wait=off", "-parallel", "none"));
        if (resume) {
            cmd.addAll(List.of("-loadvm", SNAPSHOT_TAG));
        }
        if (installerBoot) {
            cmd.addAll(List.of("-kernel", dir.resolve(AndroidSetup.KERNEL).toString(),
                    "-initrd", dir.resolve(AndroidSetup.INITRD).toString(), "-append", AndroidSetup.INSTALL_CMDLINE));
        }
        return cmd;
    }

    private String macAddress(int port) {
        return dev.hannes.virtual_mashine.net.DeviceMac.of(id, port);
    }

    /** The installer synced the disk before it said so: from now on the phone boots Android from its own disk. */
    private void finishAndroidSetup() {
        doPowerOff();
        config.provisioned = true;
        config.iso = "";
        saveConfig();
        AndroidSetup.cleanUpBoot(dir);
        doStart();
        if (state == State.RUNNING) {
            setMessage("virtual_mashine.msg.android_installed", "");
            manager.onVmChanged(this);
        }
    }

    public void downloadAndroid() {
        worker.execute(() -> {
            if (state != State.OFF || diskExists()) {
                setMessage("virtual_mashine.msg.android_needs_empty_disk", "");
                manager.onVmChanged(this);
                return;
            }
            try {
                String file = AndroidSetup.fetch(manager.globalIsoDir(), (done, total) -> {
                    setMessage("virtual_mashine.msg.downloading", (done / 1024 / 1024) + " / "
                            + (total > 0 ? Long.toString(total / 1024 / 1024) : "?") + " MB");
                    manager.onVmChanged(this);
                });
                config.iso = file;
                config.diskImage = "";
                config.bootFromCd = false;
                config.provision = AndroidSetup.PROVISION;
                config.provisioned = false;
                saveConfig();
                setMessage("virtual_mashine.msg.android_ready", "");
            } catch (IOException e) {
                Virtual_mashine.LOGGER.warn("Android download failed", e);
                setMessage("virtual_mashine.msg.download_failed", e.getMessage());
            }
            manager.onVmChanged(this);
        });
    }

    private void markProvisioned() {
        config.provisioned = true;
        saveConfig();
        setMessage("virtual_mashine.msg.provisioned", "");
        manager.onVmChanged(this);
    }

    private boolean isoMissing() {
        return !config.iso.isEmpty() && manager.resolveIso(config.iso) == null;
    }

    private static boolean detectFreezeSupport(QmpClient monitor) {
        try {
            JsonElement info = monitor.execute("query-migrate", null);
            if (info.isJsonObject()) {
                JsonElement blocked = info.getAsJsonObject().get("blocked-reasons");
                return blocked == null || !blocked.isJsonArray() || blocked.getAsJsonArray().isEmpty();
            }
        } catch (IOException e) {
            Virtual_mashine.LOGGER.debug("query-migrate failed", e);
        }
        return false;
    }

    private void ensureDisk() throws IOException {
        if (diskExists()) {
            return;
        }
        Path qemuImg = QemuLocator.findImageTool();
        if (qemuImg == null) {
            throw new IOException("qemu-img not found");
        }
        Path image = config.diskImage.isEmpty() ? null : manager.resolveIso(config.diskImage);
        if (image == null) {
            runTool(List.of(qemuImg.toString(), "create", "-f", "qcow2", diskPath().toString(), config.diskGb + "G"), 60);
            return;
        }
        // A ready-made system (OpenWrt, vJunos, cloud images, ...): copy it into this machine's own disk.
        setMessage("virtual_mashine.msg.importing", image.getFileName().toString());
        manager.onVmChanged(this);
        Path source = image;
        Path unpacked = null;
        if (image.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            unpacked = dir.resolve("image.tmp");
            try (InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(image), 1 << 16)) {
                Files.copy(in, unpacked, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.util.zip.ZipException trailingGarbage) {
                // OpenWrt pads its images after the gzip stream; everything before was written already.
                Virtual_mashine.LOGGER.debug("Ignoring data after the gzip stream of {}", image);
            }
            source = unpacked;
        }
        try {
            runTool(List.of(qemuImg.toString(), "convert", "-O", "qcow2", source.toString(), diskPath().toString()), 30 * 60);
            try {
                runTool(List.of(qemuImg.toString(), "resize", diskPath().toString(), config.diskGb + "G"), 60);
            } catch (IOException tooSmall) {
                // The image is already bigger than the configured size; keep it as it is.
            }
        } finally {
            if (unpacked != null) {
                Files.deleteIfExists(unpacked);
            }
        }
    }

    /** Deletes the disk so the next start begins from scratch (or from the selected image again). */
    public void resetDisk() {
        worker.execute(() -> {
            if (state != State.OFF) {
                return;
            }
            try {
                Files.deleteIfExists(diskPath());
                config.frozen = false;
                config.provisioned = false;
                saveConfig();
                setMessage("virtual_mashine.msg.disk_reset", "");
            } catch (IOException e) {
                setMessage("virtual_mashine.msg.command_failed", e.getMessage());
            }
            manager.onVmChanged(this);
        });
    }

    /** Fetches the current OpenWrt release and sets this machine up to install it on its next start. */
    public void downloadOpenWrt() {
        worker.execute(() -> {
            if (state != State.OFF || diskExists()) {
                setMessage("virtual_mashine.msg.openwrt_needs_empty_disk", "");
                manager.onVmChanged(this);
                return;
            }
            try {
                String file = OpenWrtDownload.fetch(manager.globalIsoDir(), (done, total) -> {
                    setMessage("virtual_mashine.msg.downloading", (done / 1024 / 1024) + " / " + Math.max(1, total / 1024 / 1024) + " MB");
                    manager.onVmChanged(this);
                });
                config.diskImage = file;
                config.provision = deviceType() == DeviceType.MANAGED_SWITCH ? OpenWrtProvisioner.SWITCH : OpenWrtProvisioner.ROUTER;
                config.provisioned = false;
                config.iso = "";
                saveConfig();
                setMessage("virtual_mashine.msg.openwrt_ready", file);
            } catch (IOException e) {
                Virtual_mashine.LOGGER.warn("OpenWrt download failed", e);
                setMessage("virtual_mashine.msg.download_failed", e.getMessage());
            }
            manager.onVmChanged(this);
        });
    }

    private void runTool(List<String> command, int timeoutSeconds) throws IOException {
        Process tool = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output;
        try (InputStream stream = tool.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!tool.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                tool.destroyForcibly();
                throw new IOException(command.get(1) + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (tool.exitValue() != 0) {
            throw new IOException(output.trim());
        }
    }

    /** A QEMU left over from a crashed game would still hold the disk lock. */
    private void killStaleProcess() {
        Path pidFile = dir.resolve("qemu.pid");
        try {
            if (!Files.exists(pidFile)) {
                return;
            }
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            Optional<ProcessHandle> handle = ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .filter(h -> h.info().command().map(c -> c.toLowerCase(Locale.ROOT).contains("qemu")).orElse(false));
            if (handle.isPresent()) {
                Virtual_mashine.LOGGER.info("Killing leftover QEMU process {} of PC {}", pid, id);
                handle.get().destroyForcibly();
                for (int i = 0; i < 50 && handle.get().isAlive(); i++) {
                    Thread.sleep(100);
                }
            }
            Files.deleteIfExists(pidFile);
        } catch (Exception e) {
            Virtual_mashine.LOGGER.debug("Could not check stale pid file", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void setState(State newState, String key, String detail) {
        state = newState;
        setMessage(key, detail);
        manager.onVmChanged(this);
    }

    private void setMessage(String key, @Nullable String detail) {
        messageKey = key;
        messageDetail = detail == null ? "" : detail;
    }

    private static String joinDetail(@Nullable String first, String second) {
        String a = first == null ? "" : first.trim();
        if (second.isBlank()) {
            return a;
        }
        return a.isEmpty() ? second : a + "\n" + second;
    }

    private String readLogTail() {
        try {
            if (!Files.exists(logPath())) {
                return "";
            }
            List<String> lines = Files.readAllLines(logPath(), StandardCharsets.UTF_8);
            List<String> tail = lines.subList(Math.max(0, lines.size() - 6), lines.size());
            String text = String.join("\n", tail).trim();
            return text.length() > 1500 ? text.substring(text.length() - 1500) : text;
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** QEMU option values separate keys with commas; a literal comma is written twice. */
    private static String escape(String value) {
        return value.replace(",", ",,");
    }

    private static int findFreeVncPort() throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        for (int port = 5900; port < 6900; port++) {
            if (RESERVED_VNC_PORTS.contains(port)) {
                continue;
            }
            try (ServerSocket ignored = new ServerSocket(port, 1, loopback)) {
                if (RESERVED_VNC_PORTS.add(port)) {
                    return port;
                }
            } catch (IOException taken) {
                // Try the next one.
            }
        }
        throw new IOException("No free VNC port between 5900 and 6899");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private Path diskPath() {
        return dir.resolve("disk.qcow2");
    }

    private Path logPath() {
        return dir.resolve("qemu.log");
    }

    private VmConfig loadConfig(DeviceType typeForNew) {
        Path file = dir.resolve("config.json");
        try {
            if (Files.exists(file)) {
                return VmConfig.fromJson(Files.readString(file, StandardCharsets.UTF_8)).sanitized();
            }
        } catch (IOException | RuntimeException e) {
            Virtual_mashine.LOGGER.warn("Could not read {}", file, e);
        }
        VmConfig fresh = new VmConfig();
        fresh.type = typeForNew.id;
        fresh.name = defaultName(typeForNew) + " " + id.toString().substring(0, 4).toUpperCase(Locale.ROOT);
        fresh.ramMb = Math.min(typeForNew.defaultRamMb, HostInfo.maxRamMb());
        fresh.cpus = Math.min(typeForNew.defaultCpus, HostInfo.cpuCount());
        fresh.netMode = VmConfig.NET_CABLE;
        fresh.autoStart = typeForNew.alwaysOnByDefault;
        fresh.sound = typeForNew == DeviceType.PC || typeForNew == DeviceType.LAPTOP || typeForNew == DeviceType.PHONE;
        if (typeForNew == DeviceType.PHONE) {
            fresh.diskGb = 16;
            fresh.typingLayout = "us"; // Android's on-screen and hardware keyboard default.
        } else if (typeForNew != DeviceType.PC && typeForNew != DeviceType.LAPTOP) {
            fresh.diskGb = 8;
        }
        if (typeForNew == DeviceType.DISPLAY && manager.resolveIso(DISPLAY_IMAGE) != null) {
            fresh.diskImage = DISPLAY_IMAGE; // The ready-made kiosk system, when it is in the ISO folder.
        }
        return fresh;
    }

    private static String defaultName(DeviceType type) {
        return switch (type) {
            case SERVER -> "Server";
            case ROUTER -> "Router";
            case MANAGED_SWITCH -> "Switch";
            case LAPTOP -> "Laptop";
            case PHONE -> "Handy";
            case DISPLAY -> "Display";
            default -> "PC";
        };
    }

    private void saveConfig() {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("config.json"), config.toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Virtual_mashine.LOGGER.warn("Could not save config of PC {}", id, e);
        }
    }
}
