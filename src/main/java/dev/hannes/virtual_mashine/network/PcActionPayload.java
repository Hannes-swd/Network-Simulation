package dev.hannes.virtual_mashine.network;

import dev.hannes.virtual_mashine.Virtual_mashine;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Client to server: a button in the PC screen was pressed or a setting changed. */
public record PcActionPayload(UUID pcId, String action, String argument) implements CustomPacketPayload {
    public static final String START = "start";
    public static final String SHUTDOWN = "shutdown";
    public static final String POWER_OFF = "power_off";
    public static final String RESET = "reset";
    public static final String FREEZE = "freeze";
    public static final String DISCARD_STATE = "discard_state";
    /** Argument: the edited {@link dev.hannes.virtual_mashine.vm.VmConfig} as JSON. */
    public static final String CONFIG = "config";
    public static final String REFRESH = "refresh";
    /** Open the port panel of this device. */
    public static final String PORTS = "ports";
    /** Fetch OpenWrt and install it on the (empty) disk at the next start. */
    public static final String DOWNLOAD_OPENWRT = "download_openwrt";
    /** Delete the disk; the next start begins fresh. */
    public static final String RESET_DISK = "reset_disk";

    public static final Type<PcActionPayload> TYPE = new Type<>(Virtual_mashine.id("pc_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PcActionPayload> CODEC = StreamCodec.ofMember(PcActionPayload::write, PcActionPayload::read);

    public PcActionPayload(UUID pcId, String action) {
        this(pcId, action, "");
    }

    private static PcActionPayload read(RegistryFriendlyByteBuf buf) {
        return new PcActionPayload(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(64), buf.readUtf());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        UUIDUtil.STREAM_CODEC.encode(buf, pcId);
        buf.writeUtf(action, 64);
        buf.writeUtf(argument);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
