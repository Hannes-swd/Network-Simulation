package dev.hannes.virtual_mashine.net;

import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.network.MediaPayloads;
import dev.hannes.virtual_mashine.vm.MediaLink;
import dev.hannes.virtual_mashine.vm.SpiceMicrophone;
import dev.hannes.virtual_mashine.vm.VirtualMachine;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything the machines hear, say and see in the world:
 * <ul>
 * <li>their sound goes to the players near them (positional) or with their screen open (directly),</li>
 * <li>the voices of players at a microphone go to the sound card of the computer it is plugged into,</li>
 * <li>so do the game's sounds around a microphone set to hear them, mixed by the game of a player nearby,</li>
 * <li>a camera is filmed by the game of a player near it, and the pictures go to its computer.</li>
 * </ul>
 * Sound arrives on the machines' own threads; who hears what is decided on the server thread a few
 * times a second and published as an immutable snapshot.
 */
public final class MediaHub {
    /** Machines are heard up to this far away (blocks). */
    static final double HEAR_RANGE = 24;
    /** A microphone picks up voices up to this far away. */
    static final double MIC_RANGE = 8;
    /** A player this close to a microphone set to game sounds records them with their game. */
    static final double GAME_SOUND_RANGE = 32;
    /** A player this close to a camera films it with their game. */
    static final double CAMERA_RANGE = 64;

    private record Hearer(ServerPlayer player, int mode, float x, float y, float z) {
    }

    private record MicTarget(UUID mic, UUID host, float gain, String name) {
    }

    private static final class Assembly {
        int frame = -1;
        byte[][] parts;
        int received;
    }

    private final MinecraftServer server;
    private final NetworkManager network;
    private final VmManager vms;
    private volatile Map<UUID, List<Hearer>> hearers = Map.of();
    /** Player → machine whose screen they have open. */
    private final Map<UUID, UUID> focus = new ConcurrentHashMap<>();
    private final Map<UUID, MicTarget> micTargets = new HashMap<>();
    /** Microphone hearing game sounds → the player whose game mixes them. */
    private final Map<UUID, UUID> gameSoundRenderers = new HashMap<>();
    /** Camera → the player whose game films it. */
    private final Map<UUID, UUID> cameraRenderers = new HashMap<>();
    private final Map<UUID, Assembly> assemblies = new HashMap<>();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "virtual_mashine-camera-decoder");
        thread.setDaemon(true);
        return thread;
    });
    /** Only one picture is converted at a time; a picture arriving meanwhile is dropped. */
    private final AtomicBoolean decoding = new AtomicBoolean();
    private int ticks;

    public MediaHub(MinecraftServer server, NetworkManager network, VmManager vms) {
        this.server = server;
        this.network = network;
        this.vms = vms;
    }

    // ---------------------------------------------------------------- server thread

    public void tick() {
        ticks++;
        if (ticks % 5 == 0) {
            updateHearers();
            updateMicrophones();
            updateGameSoundMics();
        }
        if (ticks % 10 == 0) {
            updateCameras();
        }
    }

    private void updateHearers() {
        Map<UUID, List<Hearer>> next = new HashMap<>();
        for (VirtualMachine vm : vms.runningMachines()) {
            if (!vm.hasSoundTap()) {
                continue;
            }
            NetworkManager.Placed placed = network.placed(vm.id());
            UUID carrier = vm.deviceType() == DeviceType.PHONE ? network.phoneCarrier(vm.id()) : null;
            List<Hearer> list = new ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (vm.id().equals(focus.get(player.getUUID()))) {
                    list.add(new Hearer(player, MediaPayloads.VmAudio.DIRECT_STEREO, 0, 0, 0));
                } else if (player.getUUID().equals(carrier)) {
                    list.add(new Hearer(player, MediaPayloads.VmAudio.DIRECT_MONO, 0, 0, 0));
                } else if (placed != null && carrier == null && placed.dimension().equals(NetworkManager.dimensionOf(player.level()))) {
                    Vec3 centre = Vec3.atCenterOf(placed.pos());
                    if (player.getEyePosition().distanceToSqr(centre) <= HEAR_RANGE * HEAR_RANGE) {
                        list.add(new Hearer(player, MediaPayloads.VmAudio.POSITIONAL, (float) centre.x, (float) centre.y, (float) centre.z));
                    }
                }
            }
            if (!list.isEmpty()) {
                next.put(vm.id(), List.copyOf(list));
            }
        }
        hearers = next;
    }

    /** The player's screen of a machine was opened or closed. */
    public void setFocus(ServerPlayer player, Optional<UUID> vm) {
        if (vm.isPresent() && vms.mayWatch(player, vm.get())) {
            focus.put(player.getUUID(), vm.get());
        } else {
            focus.remove(player.getUUID());
        }
        updateHearers();
    }

    private void updateMicrophones() {
        List<UUID> mics = network.devicesOfType(DeviceType.MICROPHONE);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MicTarget best = null;
            double bestDistance = Double.MAX_VALUE;
            for (UUID mic : mics) {
                NetworkManager.Placed placed = network.placed(mic);
                if (placed == null || !placed.dimension().equals(NetworkManager.dimensionOf(player.level())) || !network.usbEnabled(mic)
                        || !network.micMode(mic).hearsVoices()) {
                    continue;
                }
                double distance = player.getEyePosition().distanceTo(Vec3.atCenterOf(placed.pos()));
                if (distance > MIC_RANGE || distance >= bestDistance) {
                    continue;
                }
                UUID host = network.usbHost(mic);
                VirtualMachine vm = host == null ? null : vms.runningMachine(host);
                if (vm == null || vm.microphone() == null) {
                    continue;
                }
                // Full level up to 1.5 blocks, then quieter like a real microphone.
                float gain = (float) Math.max(0.2, Math.min(1.0, 1.0 - (distance - 1.5) / (MIC_RANGE - 1.5) * 0.8));
                best = new MicTarget(mic, host, gain, vm.config().name);
                bestDistance = distance;
            }
            MicTarget before = micTargets.get(player.getUUID());
            if (best == null) {
                micTargets.remove(player.getUUID());
            } else {
                micTargets.put(player.getUUID(), best);
            }
            boolean changed = (before == null) != (best == null)
                    || before != null && (!before.mic.equals(best.mic) || !before.name.equals(best.name));
            if (changed) {
                ServerPlayNetworking.send(player, new MediaPayloads.MicState(best != null, best == null ? "" : best.name));
            }
        }
    }

    public void micAudio(ServerPlayer player, MediaPayloads.MicAudio payload) {
        MicTarget target = micTargets.get(player.getUUID());
        if (target == null || payload.pcm().length < 2) {
            return;
        }
        VirtualMachine vm = vms.runningMachine(target.host);
        SpiceMicrophone mic = vm == null ? null : vm.microphone();
        if (mic == null) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload.pcm()).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[payload.pcm().length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buf.getShort();
        }
        mic.feed(player.getUUID(), samples, samples.length, target.gain);
    }

    /**
     * Microphones set to hear the game: the nearest player's game mixes the sounds playing around each
     * of them (it has the sound files and hears the world there) and sends them back.
     */
    private void updateGameSoundMics() {
        Map<UUID, UUID> wanted = new HashMap<>();
        for (UUID mic : network.devicesOfType(DeviceType.MICROPHONE)) {
            NetworkManager.Placed placed = network.placed(mic);
            if (placed == null || !network.usbEnabled(mic) || !network.micMode(mic).hearsGame() || micInput(mic) == null) {
                continue;
            }
            Vec3 centre = Vec3.atCenterOf(placed.pos());
            ServerPlayer current = gameSoundRenderers.containsKey(mic) ? server.getPlayerList().getPlayer(gameSoundRenderers.get(mic)) : null;
            if (current != null && inRange(current, placed, centre, GAME_SOUND_RANGE)) {
                wanted.put(mic, current.getUUID());
                continue;
            }
            ServerPlayer nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                double distance = player.distanceToSqr(centre);
                if (inRange(player, placed, centre, GAME_SOUND_RANGE) && distance < nearestDistance) {
                    nearest = player;
                    nearestDistance = distance;
                }
            }
            if (nearest != null) {
                wanted.put(mic, nearest.getUUID());
            }
        }
        for (Map.Entry<UUID, UUID> entry : gameSoundRenderers.entrySet()) {
            if (!Objects.equals(wanted.get(entry.getKey()), entry.getValue())) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getValue());
                if (player != null) {
                    ServerPlayNetworking.send(player, new MediaPayloads.MicGameJob(entry.getKey(), 0, 0, 0, false));
                }
            }
        }
        for (Map.Entry<UUID, UUID> entry : wanted.entrySet()) {
            if (!entry.getValue().equals(gameSoundRenderers.get(entry.getKey()))) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getValue());
                NetworkManager.Placed placed = network.placed(entry.getKey());
                if (player != null && placed != null) {
                    Vec3 centre = Vec3.atCenterOf(placed.pos());
                    ServerPlayNetworking.send(player, new MediaPayloads.MicGameJob(entry.getKey(), centre.x, centre.y, centre.z, true));
                }
            }
        }
        gameSoundRenderers.clear();
        gameSoundRenderers.putAll(wanted);
    }

    /** The line-in a microphone feeds: of the running computer it is plugged into (also through hubs). */
    private @Nullable SpiceMicrophone micInput(UUID mic) {
        UUID host = network.usbHost(mic);
        VirtualMachine vm = host == null ? null : vms.runningMachine(host);
        return vm == null ? null : vm.microphone();
    }

    /** The game's sounds at a microphone, from the player whose game mixes them. */
    public void gameAudio(ServerPlayer player, MediaPayloads.MicGameAudio payload) {
        if (!player.getUUID().equals(gameSoundRenderers.get(payload.mic())) || payload.pcm().length < 2
                || !network.usbEnabled(payload.mic()) || !network.micMode(payload.mic()).hearsGame()) {
            return;
        }
        SpiceMicrophone input = micInput(payload.mic());
        if (input == null) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload.pcm()).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[payload.pcm().length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buf.getShort();
        }
        input.feed(payload.mic(), samples, samples.length, 1.0F); // Its own source, mixed with the voices.
    }

    private static boolean inRange(ServerPlayer player, NetworkManager.Placed placed, Vec3 centre, double range) {
        return placed.dimension().equals(NetworkManager.dimensionOf(player.level())) && player.distanceToSqr(centre) <= range * range;
    }

    private void updateCameras() {
        Map<UUID, UUID> wanted = new HashMap<>();
        for (UUID camera : network.devicesOfType(DeviceType.CAMERA)) {
            NetworkManager.Placed placed = network.placed(camera);
            MediaLink link = cameraLink(camera);
            if (placed == null || link == null || !link.agentConnected()) {
                continue;
            }
            Vec3 centre = Vec3.atCenterOf(placed.pos());
            ServerPlayer current = cameraRenderers.containsKey(camera) ? server.getPlayerList().getPlayer(cameraRenderers.get(camera)) : null;
            if (current != null && inCameraRange(current, placed, centre)) {
                wanted.put(camera, current.getUUID());
                continue;
            }
            ServerPlayer nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                double distance = player.distanceToSqr(centre);
                if (inCameraRange(player, placed, centre) && distance < nearestDistance) {
                    nearest = player;
                    nearestDistance = distance;
                }
            }
            if (nearest != null) {
                wanted.put(camera, nearest.getUUID());
            }
        }
        for (Map.Entry<UUID, UUID> entry : cameraRenderers.entrySet()) {
            if (!Objects.equals(wanted.get(entry.getKey()), entry.getValue())) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getValue());
                NetworkManager.Placed placed = network.placed(entry.getKey());
                if (player != null) {
                    ServerPlayNetworking.send(player, new MediaPayloads.CameraJob(entry.getKey(),
                            placed == null ? player.blockPosition() : placed.pos(), placed == null ? player.getDirection() : placed.facing(), false));
                }
                assemblies.remove(entry.getKey());
            }
        }
        for (Map.Entry<UUID, UUID> entry : wanted.entrySet()) {
            if (!entry.getValue().equals(cameraRenderers.get(entry.getKey()))) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getValue());
                NetworkManager.Placed placed = network.placed(entry.getKey());
                if (player != null && placed != null) {
                    ServerPlayNetworking.send(player, new MediaPayloads.CameraJob(entry.getKey(), placed.pos(), placed.facing(), true));
                }
            }
        }
        cameraRenderers.clear();
        cameraRenderers.putAll(wanted);
    }

    private static boolean inCameraRange(ServerPlayer player, NetworkManager.Placed placed, Vec3 centre) {
        return placed.dimension().equals(NetworkManager.dimensionOf(player.level())) && player.distanceToSqr(centre) <= CAMERA_RANGE * CAMERA_RANGE;
    }

    /** The camera channel of the running computer this camera is plugged into, if the camera is on. */
    private @Nullable MediaLink cameraLink(UUID camera) {
        if (!network.usbEnabled(camera)) {
            return null;
        }
        UUID host = network.usbHost(camera);
        VirtualMachine vm = host == null ? null : vms.runningMachine(host);
        return vm == null ? null : vm.media();
    }

    public void cameraFrame(ServerPlayer player, MediaPayloads.CameraFrame payload) {
        if (!player.getUUID().equals(cameraRenderers.get(payload.camera()))
                || payload.count() < 1 || payload.count() > MediaPayloads.MAX_FRAME_CHUNKS
                || payload.index() < 0 || payload.index() >= payload.count()) {
            return;
        }
        Assembly assembly = assemblies.computeIfAbsent(payload.camera(), key -> new Assembly());
        if (assembly.frame != payload.frame() || assembly.parts == null || assembly.parts.length != payload.count()) {
            assembly.frame = payload.frame();
            assembly.parts = new byte[payload.count()][];
            assembly.received = 0;
        }
        if (assembly.parts[payload.index()] == null) {
            assembly.parts[payload.index()] = payload.data();
            assembly.received++;
        }
        if (assembly.received < assembly.parts.length) {
            return;
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        for (byte[] part : assembly.parts) {
            jpeg.writeBytes(part);
        }
        assembly.parts = null;
        MediaLink link = cameraLink(payload.camera());
        if (link == null || !decoding.compareAndSet(false, true)) {
            return;
        }
        byte[] data = jpeg.toByteArray();
        decoder.execute(() -> {
            try {
                link.sendFrame(toYuyv(data));
            } catch (IOException | RuntimeException e) {
                Virtual_mashine.LOGGER.debug("Camera picture could not be read", e);
            } finally {
                decoding.set(false);
            }
        });
    }

    /** JPEG to YUYV (BT.601, video range) in the size the guest's video device has. */
    static byte[] toYuyv(byte[] jpeg) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (image == null) {
            throw new IOException("Not a picture");
        }
        int w = MediaLink.WIDTH;
        int h = MediaLink.HEIGHT;
        if (image.getWidth() != w || image.getHeight() != h) {
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            scaled.getGraphics().drawImage(image.getScaledInstance(w, h, Image.SCALE_FAST), 0, 0, null);
            image = scaled;
        }
        int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
        byte[] out = new byte[w * h * 2];
        int o = 0;
        for (int i = 0; i < rgb.length; i += 2) {
            int p0 = rgb[i];
            int p1 = rgb[i + 1];
            int r0 = p0 >> 16 & 255, g0 = p0 >> 8 & 255, b0 = p0 & 255;
            int r1 = p1 >> 16 & 255, g1 = p1 >> 8 & 255, b1 = p1 & 255;
            int r = (r0 + r1) >> 1, g = (g0 + g1) >> 1, b = (b0 + b1) >> 1;
            out[o++] = (byte) (((66 * r0 + 129 * g0 + 25 * b0 + 128) >> 8) + 16);
            out[o++] = (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
            out[o++] = (byte) (((66 * r1 + 129 * g1 + 25 * b1 + 128) >> 8) + 16);
            out[o++] = (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
        }
        return out;
    }

    // ---------------------------------------------------------------- machine threads

    /** A machine played sound (44.1 kHz stereo): hand it to everybody who hears it. */
    public void audio(UUID vm, byte[] pcm) {
        List<Hearer> list = hearers.get(vm);
        if (list == null) {
            return;
        }
        byte[] mono = null;
        for (Hearer hearer : list) {
            byte[] data;
            if (hearer.mode == MediaPayloads.VmAudio.DIRECT_STEREO) {
                data = pcm;
            } else {
                if (mono == null) {
                    mono = downmix(pcm);
                }
                data = mono;
            }
            ServerPlayNetworking.send(hearer.player, new MediaPayloads.VmAudio(vm, hearer.mode, hearer.x, hearer.y, hearer.z, data));
        }
    }

    /** 44.1 kHz stereo to 22.05 kHz mono: half the rate is plenty for sound coming out of a box in the world. */
    private static byte[] downmix(byte[] stereo) {
        ByteBuffer in = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN);
        int pairs = stereo.length / 8;
        ByteBuffer out = ByteBuffer.allocate(pairs * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < pairs; i++) {
            int sum = in.getShort() + in.getShort() + in.getShort() + in.getShort();
            out.putShort((short) (sum / 4));
        }
        return out.array();
    }

    // ---------------------------------------------------------------- bookkeeping

    /** What a microphone or camera does right now, for the message when it is switched. */
    public Component usbStatus(UUID device, DeviceType type) {
        Component name = type == DeviceType.MICROPHONE && network.usbEnabled(device)
                ? Component.translatable("virtual_mashine.mic.named", Component.translatable(type.translationKey()),
                        Component.translatable(network.micMode(device).translationKey()))
                : Component.translatable(type.translationKey());
        if (!network.usbEnabled(device)) {
            return Component.translatable("virtual_mashine.usb.off", name);
        }
        UUID host = network.usbHost(device);
        if (host == null) {
            return Component.translatable("virtual_mashine.usb.unplugged", name);
        }
        String hostName = vms.nameOf(host, DeviceType.PC);
        VirtualMachine vm = vms.runningMachine(host);
        if (vm == null) {
            return Component.translatable("virtual_mashine.usb.host_off", name, hostName);
        }
        if (type == DeviceType.MICROPHONE && vm.microphone() == null || type == DeviceType.CAMERA && vm.media() == null) {
            return Component.translatable("virtual_mashine.usb.no_input", name, hostName);
        }
        if (type == DeviceType.CAMERA && !vm.media().agentConnected()) {
            return Component.translatable("virtual_mashine.usb.no_agent", name, hostName);
        }
        return Component.translatable("virtual_mashine.usb.ready", name, hostName);
    }

    public void onPlayerLeft(UUID player) {
        focus.remove(player);
        micTargets.remove(player);
        cameraRenderers.values().removeIf(player::equals);
        gameSoundRenderers.values().removeIf(player::equals);
    }

    public void close() {
        decoder.shutdownNow();
    }
}
