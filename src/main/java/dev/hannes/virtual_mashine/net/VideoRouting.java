package dev.hannes.virtual_mashine.net;

import dev.hannes.virtual_mashine.device.DeviceType;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Where every computer's picture goes: follows the HDMI cables from a computer's sockets, through
 * HDMI switches, to the monitors, and gives each monitor one screen (head) of the graphics card.
 * <p>
 * Every HDMI socket of a computer is a screen of its own. An HDMI switch either hands every output a
 * screen of its own (like a DisplayPort MST hub: the guest can extend or mirror them) or shows the
 * same picture on all outputs (a splitter). Screens are numbered in plugging order: socket by socket,
 * output by output.
 */
public final class VideoRouting {
    /** What one monitor shows: screen {@code head} of computer {@code source}. */
    public record Feed(UUID source, int head) {
    }

    public static final VideoRouting EMPTY = new VideoRouting(Map.of(), Map.of());

    /** HDMI switches inside switches inside switches stop somewhere; this also ends cabling loops. */
    private static final int MAX_DEPTH = 4;

    private final Map<UUID, Feed> feeds;
    /** Per computer: wanted pixel size of each screen, null where no monitor hangs. */
    private final Map<UUID, int[][]> screens;

    private VideoRouting(Map<UUID, Feed> feeds, Map<UUID, int[][]> screens) {
        this.feeds = feeds;
        this.screens = screens;
    }

    /** The picture a monitor gets over HDMI, or null if no computer feeds it. */
    public @Nullable Feed feed(UUID monitor) {
        return feeds.get(monitor);
    }

    /** Monitors showing screen {@code head} of {@code source}. */
    public List<UUID> monitorsOf(UUID source, int head) {
        return feeds.entrySet().stream()
                .filter(entry -> entry.getValue().source().equals(source) && entry.getValue().head() == head)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Wanted size {width, height} of each screen of a computer; an entry is null where no monitor hangs. */
    public int[][] screens(UUID source) {
        int[][] sizes = screens.get(source);
        return sizes != null ? sizes : new int[DeviceType.MAX_SCREENS][];
    }

    /**
     * @param cables       every cable of the world
     * @param mirrored     HDMI switches that show one picture on all outputs
     * @param monitorBlocks size of a monitor in blocks {width, height}
     */
    public static VideoRouting compute(List<Cable> cables, Predicate<UUID> mirrored, Function<UUID, int[]> monitorBlocks) {
        Map<PortRef, Cable.End> peers = new HashMap<>();
        Map<UUID, DeviceType> sources = new HashMap<>();
        for (Cable cable : cables) {
            if (!cable.cableType().isHdmi()) {
                continue;
            }
            peers.put(cable.a.ref(), cable.b);
            peers.put(cable.b.ref(), cable.a);
            for (Cable.End end : List.of(cable.a, cable.b)) {
                if (end.type().hasVideoOutputs() && end.type().isHdmiPort(end.port)) {
                    sources.put(end.device, end.type());
                }
            }
        }
        if (sources.isEmpty()) {
            return EMPTY;
        }
        Map<UUID, Feed> feeds = new HashMap<>();
        Map<UUID, int[][]> screens = new HashMap<>();
        for (Map.Entry<UUID, DeviceType> source : sources.entrySet()) {
            Walk walk = new Walk(source.getKey(), source.getValue().firstHdmiScreen(), peers, mirrored, monitorBlocks, feeds);
            DeviceType type = source.getValue();
            for (int i = 0; i < type.hdmiPorts(); i++) {
                Cable.End peer = peers.get(new PortRef(source.getKey(), type.hdmiPort(i)));
                if (peer != null) {
                    walk.follow(peer, new Screen(), 0);
                }
            }
            screens.put(source.getKey(), walk.sizes);
        }
        return new VideoRouting(feeds, screens);
    }

    /** Pixels for a monitor of this many blocks: its shape, as large as fits into 1920 x 1200, in steps of 8. */
    public static int[] resolution(int widthBlocks, int heightBlocks) {
        int w = Math.max(1, widthBlocks);
        int h = Math.max(1, heightBlocks);
        double perBlock = Math.min(1920.0 / w, 1200.0 / h);
        return new int[]{(int) (perBlock * w) / 8 * 8, (int) (perBlock * h) / 8 * 8};
    }

    /** A screen number handed out when the first monitor needs it, so empty switch outputs use none. */
    private static final class Screen {
        int head = -2;
    }

    private static final class Walk {
        final UUID source;
        final Map<PortRef, Cable.End> peers;
        final Predicate<UUID> mirrored;
        final Function<UUID, int[]> monitorBlocks;
        final Map<UUID, Feed> feeds;
        final int[][] sizes = new int[DeviceType.MAX_SCREENS][];
        int next;

        Walk(UUID source, int first, Map<PortRef, Cable.End> peers, Predicate<UUID> mirrored,
             Function<UUID, int[]> monitorBlocks, Map<UUID, Feed> feeds) {
            this.source = source;
            this.next = first;
            this.peers = peers;
            this.mirrored = mirrored;
            this.monitorBlocks = monitorBlocks;
            this.feeds = feeds;
        }

        /** -1 when the graphics card has no screen left. */
        int head(Screen screen) {
            if (screen.head == -2) {
                screen.head = next < DeviceType.MAX_SCREENS ? next++ : -1;
            }
            return screen.head;
        }

        /** @param input the socket the picture arrives at */
        void follow(Cable.End input, Screen screen, int depth) {
            DeviceType type = input.type();
            if (depth > MAX_DEPTH || !type.isHdmiPort(input.port) || type.isHdmiOutput(input.port)) {
                return; // Output to output (or a loop) carries no picture.
            }
            if (type == DeviceType.DISPLAY) {
                int head = head(screen);
                if (head >= 0) {
                    feeds.put(input.device, new Feed(source, head));
                    if (sizes[head] == null) {
                        int[] blocks = monitorBlocks.apply(input.device);
                        sizes[head] = resolution(blocks[0], blocks[1]);
                    }
                }
                return;
            }
            if (type != DeviceType.HDMI_SWITCH) {
                return;
            }
            boolean mirror = mirrored.test(input.device);
            boolean first = true;
            for (int out = 1; out < type.hdmiPorts(); out++) {
                Cable.End peer = peers.get(new PortRef(input.device, type.hdmiPort(out)));
                if (peer == null) {
                    continue;
                }
                // Splitter: one picture everywhere. Hub: the first output keeps the incoming screen, the others get new ones.
                follow(peer, mirror || first ? screen : new Screen(), depth + 1);
                first = false;
            }
        }
    }
}
