package dev.hannes.virtual_mashine.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.device.DeviceType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WLAN: access points with SSID and WPA2 password, laptops that remember a network and join the
 * strongest access point of it in range. Signal strength follows distance and the walls in
 * between; a weak signal means a slower link, a too weak one drops it.
 */
public final class WlanManager {
    /** Signal needed to join (dBm) and below which the link drops. */
    private static final double JOIN_DBM = -78;
    private static final double DROP_DBM = -85;
    private static final double VISIBLE_DBM = -90;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class ApConfig {
        public String ssid;
        public String password;
    }

    public static final class Profile {
        public String ssid;
        public String password;
    }

    private static final class Store {
        Map<UUID, ApConfig> accessPoints = new HashMap<>();
        Map<UUID, Profile> laptops = new HashMap<>();
    }

    /** One wireless network as a laptop sees it. */
    public record Network(String ssid, int dbm, boolean secured, boolean connected) {
    }

    private record Association(UUID ap, int slot, int mbit) {
    }

    private final MinecraftServer server;
    private final NetworkManager network;
    private final NetworkFabric fabric;
    private final Path file;
    private Store store = new Store();
    private final Map<UUID, Association> associations = new HashMap<>();

    WlanManager(MinecraftServer server, NetworkManager network, NetworkFabric fabric, Path root) {
        this.server = server;
        this.network = network;
        this.fabric = fabric;
        this.file = root.resolve("wlan.json");
        load();
    }

    // ---------------------------------------------------------------- access points

    public ApConfig accessPoint(UUID ap) {
        return store.accessPoints.computeIfAbsent(ap, id -> {
            ApConfig config = new ApConfig();
            config.ssid = "WLAN-" + id.toString().substring(0, 4).toUpperCase(Locale.ROOT);
            // Like the sticker on a real router: a random 16-digit key.
            SecureRandom random = new SecureRandom();
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                key.append(random.nextInt(10));
            }
            config.password = key.toString();
            save();
            return config;
        });
    }

    /** @return translation key of the result */
    public String configure(UUID ap, String ssid, String password) {
        String cleanSsid = ssid.trim();
        if (cleanSsid.isEmpty() || cleanSsid.length() > 32) {
            return "virtual_mashine.wlan.msg.bad_ssid";
        }
        if (!password.isEmpty() && (password.length() < 8 || password.length() > 63)) {
            return "virtual_mashine.wlan.msg.bad_password";
        }
        ApConfig config = accessPoint(ap);
        config.ssid = cleanSsid;
        config.password = password;
        save();
        // Clients with the old settings have to authenticate again.
        for (Map.Entry<UUID, Association> entry : List.copyOf(associations.entrySet())) {
            if (entry.getValue().ap.equals(ap)) {
                drop(entry.getKey());
            }
        }
        return "virtual_mashine.wlan.msg.saved";
    }

    /** Laptops currently on this access point, with their signal. */
    public List<String> clientsOf(UUID ap) {
        List<String> clients = new ArrayList<>();
        for (Map.Entry<UUID, Association> entry : associations.entrySet()) {
            if (entry.getValue().ap.equals(ap)) {
                NetworkManager.Placed laptop = network.placed(entry.getKey());
                NetworkManager.Placed point = network.placed(ap);
                int dbm = laptop == null || point == null ? -99 : (int) Math.round(signal(point, laptop));
                clients.add(network.nameOf(entry.getKey(), DeviceType.LAPTOP) + "  " + dbm + " dBm · " + entry.getValue().mbit + " Mbit/s");
            }
        }
        return clients;
    }

    // ---------------------------------------------------------------- laptops

    public List<Network> scan(UUID laptop) {
        NetworkManager.Placed here = network.placed(laptop);
        if (here == null) {
            return List.of();
        }
        Association current = associations.get(laptop);
        Map<String, Network> best = new HashMap<>();
        for (UUID ap : network.devicesOfType(DeviceType.ACCESS_POINT)) {
            NetworkManager.Placed point = network.placed(ap);
            if (point == null || !point.dimension().equals(here.dimension())) {
                continue;
            }
            double dbm = signal(point, here);
            if (dbm < VISIBLE_DBM) {
                continue;
            }
            ApConfig config = accessPoint(ap);
            boolean connected = current != null && current.ap.equals(ap);
            Network seen = new Network(config.ssid, (int) Math.round(dbm), !config.password.isEmpty(), connected);
            Network previous = best.get(config.ssid);
            if (previous == null || connected || (!previous.connected() && previous.dbm() < seen.dbm())) {
                best.put(config.ssid, seen);
            }
        }
        List<Network> networks = new ArrayList<>(best.values());
        networks.sort(Comparator.comparingInt(Network::dbm).reversed());
        return networks;
    }

    /** Remembers the network; the next tick joins it if it is in range and the password fits. */
    public String connect(UUID laptop, String ssid, String password) {
        boolean exists = false;
        for (UUID ap : network.devicesOfType(DeviceType.ACCESS_POINT)) {
            ApConfig config = accessPoint(ap);
            if (config.ssid.equals(ssid)) {
                exists = true;
                if (!config.password.equals(password)) {
                    return "virtual_mashine.wlan.msg.wrong_password";
                }
            }
        }
        if (!exists) {
            return "virtual_mashine.wlan.msg.not_found";
        }
        Profile profile = new Profile();
        profile.ssid = ssid;
        profile.password = password;
        store.laptops.put(laptop, profile);
        save();
        drop(laptop);
        tick();
        return associations.containsKey(laptop) ? "virtual_mashine.wlan.msg.connected" : "virtual_mashine.wlan.msg.saved_offline";
    }

    public void forget(UUID laptop) {
        store.laptops.remove(laptop);
        save();
        drop(laptop);
    }

    public @Nullable String connectedSsid(UUID laptop) {
        Association current = associations.get(laptop);
        return current == null ? null : accessPoint(current.ap).ssid;
    }

    public int linkSpeed(UUID laptop) {
        Association current = associations.get(laptop);
        return current == null ? 0 : current.mbit;
    }

    public @Nullable String rememberedSsid(UUID laptop) {
        Profile profile = store.laptops.get(laptop);
        return profile == null ? null : profile.ssid;
    }

    // ---------------------------------------------------------------- radio

    /** Once a second: keep, improve, drop or create associations. */
    void tick() {
        for (UUID laptop : network.devicesOfType(DeviceType.LAPTOP)) {
            NetworkManager.Placed here = network.placed(laptop);
            Profile profile = store.laptops.get(laptop);
            Association current = associations.get(laptop);
            if (here == null || profile == null || !network.isPowered(laptop, DeviceType.LAPTOP)) {
                if (current != null) {
                    drop(laptop);
                }
                continue;
            }
            UUID bestAp = null;
            double bestDbm = -999;
            for (UUID ap : network.devicesOfType(DeviceType.ACCESS_POINT)) {
                NetworkManager.Placed point = network.placed(ap);
                ApConfig config = accessPoint(ap);
                if (point == null || !point.dimension().equals(here.dimension()) || !config.ssid.equals(profile.ssid)
                        || !config.password.equals(profile.password)) {
                    continue;
                }
                double dbm = signal(point, here);
                if (dbm > bestDbm) {
                    bestDbm = dbm;
                    bestAp = ap;
                }
            }
            if (current != null) {
                NetworkManager.Placed point = network.placed(current.ap);
                ApConfig config = accessPoint(current.ap);
                double dbm = point == null ? -999 : signal(point, here);
                boolean stillValid = point != null && config.ssid.equals(profile.ssid) && config.password.equals(profile.password);
                // Roam only for a clearly better access point, like real clients do.
                boolean roam = bestAp != null && !bestAp.equals(current.ap) && bestDbm > dbm + 8;
                if (!stillValid || dbm < DROP_DBM || roam) {
                    drop(laptop);
                    current = null;
                } else if (rateFor(dbm) != current.mbit) {
                    associate(laptop, current.ap, current.slot, rateFor(dbm));
                }
            }
            if (current == null && bestAp != null && bestDbm >= JOIN_DBM) {
                int slot = freeSlot(bestAp);
                if (slot >= 0) {
                    associate(laptop, bestAp, slot, rateFor(bestDbm));
                }
            }
        }
    }

    private void associate(UUID laptop, UUID ap, int slot, int mbit) {
        PortRef card = new PortRef(laptop, 0);
        fabric.disconnect(card);
        fabric.connect(card, new PortRef(ap, 1 + slot), mbit);
        associations.put(laptop, new Association(ap, slot, mbit));
        network.refreshLinks();
    }

    private void drop(UUID laptop) {
        if (associations.remove(laptop) != null) {
            fabric.disconnect(new PortRef(laptop, 0));
            network.refreshLinks();
        }
    }

    void deviceRemoved(UUID id) {
        drop(id);
        for (Map.Entry<UUID, Association> entry : List.copyOf(associations.entrySet())) {
            if (entry.getValue().ap.equals(id)) {
                drop(entry.getKey());
            }
        }
    }

    private int freeSlot(UUID ap) {
        Set<Integer> used = new HashSet<>();
        for (Association association : associations.values()) {
            if (association.ap.equals(ap)) {
                used.add(association.slot);
            }
        }
        for (int slot = 0; slot < DeviceType.AP_CLIENT_SLOTS; slot++) {
            if (!used.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    /** Wi-Fi 5 style rates by signal strength. */
    private static int rateFor(double dbm) {
        if (dbm >= -55) {
            return 867;
        }
        if (dbm >= -65) {
            return 433;
        }
        if (dbm >= -72) {
            return 150;
        }
        if (dbm >= -80) {
            return 54;
        }
        return 6;
    }

    /** Received signal in dBm: free-space loss plus the walls along the straight line. */
    private double signal(NetworkManager.Placed from, NetworkManager.Placed to) {
        Vec3 a = Vec3.atCenterOf(from.pos());
        Vec3 b = Vec3.atCenterOf(to.pos());
        double distance = Math.max(1.0, a.distanceTo(b));
        double dbm = -30 - 20 * Math.log10(distance);
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(from.dimension())));
        if (level == null) {
            return dbm;
        }
        Set<BlockPos> counted = new HashSet<>();
        int steps = (int) Math.ceil(distance * 2);
        for (int i = 1; i < steps; i++) {
            BlockPos pos = BlockPos.containing(a.lerp(b, (double) i / steps));
            if (pos.equals(from.pos()) || pos.equals(to.pos()) || !counted.add(pos) || !level.isLoaded(pos)) {
                continue;
            }
            dbm -= attenuation(level, pos);
        }
        return dbm;
    }

    private static double attenuation(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return 0;
        }
        if (state.is(BlockTags.LEAVES)) {
            return 1;
        }
        if (!state.getFluidState().isEmpty()) {
            return 4;
        }
        if (state.is(BlockTags.IMPERMEABLE)) {
            return 2; // glass
        }
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_DOORS)) {
            return 5;
        }
        if (state.is(BlockTags.WOOL)) {
            return 3;
        }
        if (state.isSolidRender()) {
            return 12; // stone, concrete, ...
        }
        return 2;
    }

    // ---------------------------------------------------------------- storage

    private void load() {
        try {
            if (Files.exists(file)) {
                Store loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Store.class);
                if (loaded != null) {
                    store = loaded;
                    if (store.accessPoints == null) {
                        store.accessPoints = new HashMap<>();
                    }
                    if (store.laptops == null) {
                        store.laptops = new HashMap<>();
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
            Files.writeString(file, GSON.toJson(store), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Virtual_mashine.LOGGER.warn("Could not save {}", file, e);
        }
    }
}
