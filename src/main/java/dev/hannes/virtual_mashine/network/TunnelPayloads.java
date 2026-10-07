package dev.hannes.virtual_mashine.network;

import dev.hannes.virtual_mashine.Virtual_mashine;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Carries a PC's VNC connection through the Minecraft connection, for players whose game does not
 * run on the machine that hosts the virtual machines (e.g. friends joining via Essential or LAN).
 */
public final class TunnelPayloads {
    /** Client to server limit is 32767 bytes per custom payload; leave room for the header. */
    public static final int MAX_CLIENT_CHUNK = 30_000;
    /** Server to client allows up to 1 MiB per custom payload. */
    public static final int MAX_SERVER_CHUNK = 256 * 1024;

    private TunnelPayloads() {
    }

    /** Client to server: connect tunnel {@code tunnelId} (chosen by the client) to the PC's screen. */
    public record Open(int tunnelId, UUID pcId) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(Virtual_mashine.id("tunnel_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    buf.writeVarInt(payload.tunnelId);
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.pcId);
                },
                buf -> new Open(buf.readVarInt(), UUIDUtil.STREAM_CODEC.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Both directions: raw VNC bytes of one tunnel. */
    public record Data(int tunnelId, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Data> TYPE = new Type<>(Virtual_mashine.id("tunnel_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    buf.writeVarInt(payload.tunnelId);
                    buf.writeByteArray(payload.bytes);
                },
                buf -> new Data(buf.readVarInt(), buf.readByteArray(MAX_SERVER_CHUNK)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Both directions: the tunnel is gone (screen closed, PC switched off, player left). */
    public record Close(int tunnelId) implements CustomPacketPayload {
        public static final Type<Close> TYPE = new Type<>(Virtual_mashine.id("tunnel_close"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Close> CODEC = StreamCodec.ofMember(
                (payload, buf) -> buf.writeVarInt(payload.tunnelId),
                buf -> new Close(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
