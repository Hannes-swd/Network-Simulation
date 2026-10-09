package dev.hannes.virtual_mashine.network;

import dev.hannes.virtual_mashine.Virtual_mashine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Payloads of the cabling system: the port panel, cable placement and the cables drawn in the world. */
public final class NetPayloads {
    private NetPayloads() {
    }

    /** What one socket shows in the port panel. */
    public record PortInfo(String name, int state, boolean link, boolean activity, String peer, String cableType, int color, int length,
                           boolean console, String mac, int speedMbit, long rxBytes, long txBytes) {
        /** Nothing plugged in. */
        public static final int FREE = 0;
        /** A cable is plugged in, the other end is in the player's hand. */
        public static final int HALF = 1;
        /** Cable connects this port to another device. */
        public static final int CONNECTED = 2;

        static void write(RegistryFriendlyByteBuf buf, PortInfo info) {
            buf.writeUtf(info.name, 64);
            buf.writeVarInt(info.state);
            buf.writeBoolean(info.link);
            buf.writeBoolean(info.activity);
            buf.writeUtf(info.peer, 256);
            buf.writeUtf(info.cableType, 32);
            buf.writeInt(info.color);
            buf.writeVarInt(info.length);
            buf.writeBoolean(info.console);
            buf.writeUtf(info.mac, 32);
            buf.writeVarInt(info.speedMbit);
            buf.writeVarLong(info.rxBytes);
            buf.writeVarLong(info.txBytes);
        }

        static PortInfo read(RegistryFriendlyByteBuf buf) {
            return new PortInfo(buf.readUtf(64), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readUtf(256),
                    buf.readUtf(32), buf.readInt(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(32), buf.readVarInt(),
                    buf.readVarLong(), buf.readVarLong());
        }
    }

    /** Server to client: open or refresh the port panel of a device. */
    public record PortView(UUID device, BlockPos pos, String deviceType, String deviceName, boolean powered, boolean open,
                           List<PortInfo> ports, String heldCable, String pending, String message, boolean tester) implements CustomPacketPayload {
        public static final Type<PortView> TYPE = new Type<>(Virtual_mashine.id("port_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortView> CODEC = StreamCodec.ofMember(PortView::write, PortView::read);

        private void write(RegistryFriendlyByteBuf buf) {
            UUIDUtil.STREAM_CODEC.encode(buf, device);
            buf.writeBlockPos(pos);
            buf.writeUtf(deviceType, 32);
            buf.writeUtf(deviceName, 64);
            buf.writeBoolean(powered);
            buf.writeBoolean(open);
            buf.writeVarInt(ports.size());
            for (PortInfo port : ports) {
                PortInfo.write(buf, port);
            }
            buf.writeUtf(heldCable, 32);
            buf.writeUtf(pending, 256);
            buf.writeUtf(message, 512);
            buf.writeBoolean(tester);
        }

        private static PortView read(RegistryFriendlyByteBuf buf) {
            UUID device = UUIDUtil.STREAM_CODEC.decode(buf);
            BlockPos pos = buf.readBlockPos();
            String deviceType = buf.readUtf(32);
            String deviceName = buf.readUtf(64);
            boolean powered = buf.readBoolean();
            boolean open = buf.readBoolean();
            int count = Math.min(buf.readVarInt(), 64);
            List<PortInfo> ports = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ports.add(PortInfo.read(buf));
            }
            return new PortView(device, pos, deviceType, deviceName, powered, open, ports, buf.readUtf(32), buf.readUtf(256), buf.readUtf(512),
                    buf.readBoolean());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: a socket in the port panel was clicked, or the panel wants fresh LEDs. */
    public record PortAction(UUID device, BlockPos pos, String action, int port) implements CustomPacketPayload {
        public static final String PLUG = "plug";
        public static final String UNPLUG = "unplug";
        public static final String REFRESH = "refresh";

        public static final Type<PortAction> TYPE = new Type<>(Virtual_mashine.id("port_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortAction> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.device);
                    buf.writeBlockPos(payload.pos);
                    buf.writeUtf(payload.action, 16);
                    buf.writeVarInt(payload.port);
                },
                buf -> new PortAction(UUIDUtil.STREAM_CODEC.decode(buf), buf.readBlockPos(), buf.readUtf(16), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A wireless network in range of a laptop. */
    public record WlanNetwork(String ssid, int dbm, boolean secured, boolean connected) {
    }

    /** Server to client: the WLAN menu of a laptop (networks in range, current connection). */
    public record WlanView(UUID device, BlockPos pos, String deviceName, List<WlanNetwork> networks, String connected,
                           String remembered, int mbit, boolean powered, String message) implements CustomPacketPayload {
        public static final Type<WlanView> TYPE = new Type<>(Virtual_mashine.id("wlan_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WlanView> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.device);
                    buf.writeBlockPos(payload.pos);
                    buf.writeUtf(payload.deviceName, 64);
                    buf.writeVarInt(payload.networks.size());
                    for (WlanNetwork network : payload.networks) {
                        buf.writeUtf(network.ssid(), 64);
                        buf.writeInt(network.dbm());
                        buf.writeBoolean(network.secured());
                        buf.writeBoolean(network.connected());
                    }
                    buf.writeUtf(payload.connected, 64);
                    buf.writeUtf(payload.remembered, 64);
                    buf.writeVarInt(payload.mbit);
                    buf.writeBoolean(payload.powered);
                    buf.writeUtf(payload.message, 256);
                },
                buf -> {
                    UUID device = UUIDUtil.STREAM_CODEC.decode(buf);
                    BlockPos pos = buf.readBlockPos();
                    String name = buf.readUtf(64);
                    int count = Math.min(buf.readVarInt(), 256);
                    List<WlanNetwork> networks = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        networks.add(new WlanNetwork(buf.readUtf(64), buf.readInt(), buf.readBoolean(), buf.readBoolean()));
                    }
                    return new WlanView(device, pos, name, networks, buf.readUtf(64), buf.readUtf(64), buf.readVarInt(),
                            buf.readBoolean(), buf.readUtf(256));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: join or forget a WLAN, or refresh the list. */
    public record WlanAction(UUID device, String action, String ssid, String password) implements CustomPacketPayload {
        public static final String CONNECT = "connect";
        public static final String FORGET = "forget";
        public static final String SCAN = "scan";

        public static final Type<WlanAction> TYPE = new Type<>(Virtual_mashine.id("wlan_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WlanAction> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.device);
                    buf.writeUtf(payload.action, 16);
                    buf.writeUtf(payload.ssid, 64);
                    buf.writeUtf(payload.password, 128);
                },
                buf -> new WlanAction(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(16), buf.readUtf(64), buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: an access point's settings and its clients. */
    public record ApView(UUID device, BlockPos pos, String deviceName, String ssid, String password, List<String> clients,
                         String message) implements CustomPacketPayload {
        public static final Type<ApView> TYPE = new Type<>(Virtual_mashine.id("ap_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ApView> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.device);
                    buf.writeBlockPos(payload.pos);
                    buf.writeUtf(payload.deviceName, 64);
                    buf.writeUtf(payload.ssid, 64);
                    buf.writeUtf(payload.password, 128);
                    buf.writeVarInt(payload.clients.size());
                    for (String client : payload.clients) {
                        buf.writeUtf(client, 128);
                    }
                    buf.writeUtf(payload.message, 256);
                },
                buf -> {
                    UUID device = UUIDUtil.STREAM_CODEC.decode(buf);
                    BlockPos pos = buf.readBlockPos();
                    String name = buf.readUtf(64);
                    String ssid = buf.readUtf(64);
                    String password = buf.readUtf(128);
                    int count = Math.min(buf.readVarInt(), 64);
                    List<String> clients = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        clients.add(buf.readUtf(128));
                    }
                    return new ApView(device, pos, name, ssid, password, clients, buf.readUtf(256));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: save new access point settings (or just refresh). */
    public record ApAction(UUID device, boolean save, String ssid, String password) implements CustomPacketPayload {
        public static final Type<ApAction> TYPE = new Type<>(Virtual_mashine.id("ap_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ApAction> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.device);
                    buf.writeBoolean(payload.save);
                    buf.writeUtf(payload.ssid, 64);
                    buf.writeUtf(payload.password, 128);
                },
                buf -> new ApAction(UUIDUtil.STREAM_CODEC.decode(buf), buf.readBoolean(), buf.readUtf(64), buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: an I/O module's channels and network settings. */
    public record IoView(UUID device, BlockPos pos, String deviceName, String name, String mac, String address, boolean link,
                         boolean dhcp, String staticIp, String staticMask, String staticGateway, int outputMask, int[] levels,
                         int connections, String message) implements CustomPacketPayload {
        public static final Type<IoView> TYPE = new Type<>(Virtual_mashine.id("io_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, IoView> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.device);
                    buf.writeBlockPos(payload.pos);
                    buf.writeUtf(payload.deviceName, 64);
                    buf.writeUtf(payload.name, 64);
                    buf.writeUtf(payload.mac, 32);
                    buf.writeUtf(payload.address, 64);
                    buf.writeBoolean(payload.link);
                    buf.writeBoolean(payload.dhcp);
                    buf.writeUtf(payload.staticIp, 32);
                    buf.writeUtf(payload.staticMask, 32);
                    buf.writeUtf(payload.staticGateway, 32);
                    buf.writeVarInt(payload.outputMask);
                    for (int i = 0; i < 6; i++) {
                        buf.writeByte(payload.levels[i]);
                    }
                    buf.writeVarInt(payload.connections);
                    buf.writeUtf(payload.message, 128);
                },
                buf -> {
                    UUID device = UUIDUtil.STREAM_CODEC.decode(buf);
                    BlockPos pos = buf.readBlockPos();
                    String deviceName = buf.readUtf(64);
                    String name = buf.readUtf(64);
                    String mac = buf.readUtf(32);
                    String address = buf.readUtf(64);
                    boolean link = buf.readBoolean();
                    boolean dhcp = buf.readBoolean();
                    String ip = buf.readUtf(32);
                    String mask = buf.readUtf(32);
                    String gateway = buf.readUtf(32);
                    int outputs = buf.readVarInt();
                    int[] levels = new int[6];
                    for (int i = 0; i < 6; i++) {
                        levels[i] = buf.readByte();
                    }
                    return new IoView(device, pos, deviceName, name, mac, address, link, dhcp, ip, mask, gateway, outputs, levels,
                            buf.readVarInt(), buf.readUtf(128));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: change a channel or the network settings of an I/O module, or just refresh. */
    public record IoAction(UUID device, String action, int channel, int value, String name, boolean dhcp, String ip, String mask,
                           String gateway) implements CustomPacketPayload {
        public static final String REFRESH = "refresh";
        /** value 1 = output, 0 = input */
        public static final String MODE = "mode";
        public static final String LEVEL = "level";
        public static final String NETWORK = "network";

        public static IoAction simple(UUID device, String action, int channel, int value) {
            return new IoAction(device, action, channel, value, "", false, "", "", "");
        }

        public static final Type<IoAction> TYPE = new Type<>(Virtual_mashine.id("io_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, IoAction> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.device);
                    buf.writeUtf(payload.action, 16);
                    buf.writeVarInt(payload.channel);
                    buf.writeVarInt(payload.value);
                    buf.writeUtf(payload.name, 64);
                    buf.writeBoolean(payload.dhcp);
                    buf.writeUtf(payload.ip, 32);
                    buf.writeUtf(payload.mask, 32);
                    buf.writeUtf(payload.gateway, 32);
                },
                buf -> new IoAction(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(16), buf.readVarInt(), buf.readVarInt(),
                        buf.readUtf(64), buf.readBoolean(), buf.readUtf(32), buf.readUtf(32), buf.readUtf(32)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One cable as the client draws it. */
    public record CableVisual(double ax, double ay, double az, double bx, double by, double bz, int color) {
    }

    /** Server to client: all cables of the player's dimension (sent whenever one changes). */
    public record CableSync(List<CableVisual> cables) implements CustomPacketPayload {
        public static final Type<CableSync> TYPE = new Type<>(Virtual_mashine.id("cable_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CableSync> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    buf.writeVarInt(payload.cables.size());
                    for (CableVisual c : payload.cables) {
                        buf.writeDouble(c.ax());
                        buf.writeDouble(c.ay());
                        buf.writeDouble(c.az());
                        buf.writeDouble(c.bx());
                        buf.writeDouble(c.by());
                        buf.writeDouble(c.bz());
                        buf.writeInt(c.color());
                    }
                },
                buf -> {
                    int count = Math.min(buf.readVarInt(), 100_000);
                    List<CableVisual> cables = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        cables.add(new CableVisual(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readInt()));
                    }
                    return new CableSync(cables);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: one end of a cable is plugged in, the rest hangs from the player's hand. */
    public record HeldCable(boolean active, double x, double y, double z, int color) implements CustomPacketPayload {
        public static final Type<HeldCable> TYPE = new Type<>(Virtual_mashine.id("held_cable"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HeldCable> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    buf.writeBoolean(payload.active);
                    buf.writeDouble(payload.x);
                    buf.writeDouble(payload.y);
                    buf.writeDouble(payload.z);
                    buf.writeInt(payload.color);
                },
                buf -> new HeldCable(buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
