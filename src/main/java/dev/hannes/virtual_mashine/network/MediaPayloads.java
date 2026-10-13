package dev.hannes.virtual_mashine.network;

import dev.hannes.virtual_mashine.Virtual_mashine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Optional;
import java.util.UUID;

/** Sound of the machines, the players' voices for microphones and the pictures of cameras. */
public final class MediaPayloads {
    /** 200 ms of voice at most per packet. */
    public static final int MAX_MIC_BYTES = 24_000 * 2 / 5;
    public static final int MAX_FRAME_CHUNK = 30_000;
    public static final int MAX_FRAME_CHUNKS = 16;

    private MediaPayloads() {
    }

    /**
     * Server to client: a piece of a machine's sound, 16-bit little-endian PCM.
     *
     * @param mode {@link #POSITIONAL} (mono 22.05 kHz, from x/y/z), {@link #DIRECT_STEREO} (44.1 kHz,
     *             the player has its screen open) or {@link #DIRECT_MONO} (22.05 kHz, the phone in the player's pocket)
     */
    public record VmAudio(UUID vm, int mode, float x, float y, float z, byte[] pcm) implements CustomPacketPayload {
        public static final int POSITIONAL = 0;
        public static final int DIRECT_STEREO = 1;
        public static final int DIRECT_MONO = 2;
        public static final Type<VmAudio> TYPE = new Type<>(Virtual_mashine.id("vm_audio"));
        public static final StreamCodec<RegistryFriendlyByteBuf, VmAudio> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.vm);
                    buf.writeByte(payload.mode);
                    buf.writeFloat(payload.x);
                    buf.writeFloat(payload.y);
                    buf.writeFloat(payload.z);
                    buf.writeByteArray(payload.pcm);
                },
                buf -> new VmAudio(UUIDUtil.STREAM_CODEC.decode(buf), buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                        buf.readByteArray(1 << 18)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: the player has this machine's screen open (its sound then plays directly), or none. */
    public record AudioFocus(Optional<UUID> vm) implements CustomPacketPayload {
        public static final Type<AudioFocus> TYPE = new Type<>(Virtual_mashine.id("audio_focus"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AudioFocus> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    buf.writeBoolean(payload.vm.isPresent());
                    payload.vm.ifPresent(id -> UUIDUtil.STREAM_CODEC.encode(buf, id));
                },
                buf -> new AudioFocus(buf.readBoolean() ? Optional.of(UUIDUtil.STREAM_CODEC.decode(buf)) : Optional.empty()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: the player stands at a microphone that is on (record and send the voice), or no longer. */
    public record MicState(boolean active, String target) implements CustomPacketPayload {
        public static final Type<MicState> TYPE = new Type<>(Virtual_mashine.id("mic_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MicState> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    buf.writeBoolean(payload.active);
                    buf.writeUtf(payload.target, 64);
                },
                buf -> new MicState(buf.readBoolean(), buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: the player's voice, 16-bit little-endian mono PCM at 24 kHz. */
    public record MicAudio(byte[] pcm) implements CustomPacketPayload {
        public static final Type<MicAudio> TYPE = new Type<>(Virtual_mashine.id("mic_audio"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MicAudio> CODEC = StreamCodec.ofMember(
                (payload, buf) -> buf.writeByteArray(payload.pcm),
                buf -> new MicAudio(buf.readByteArray(MAX_MIC_BYTES)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: film for this camera (it stands at pos, looking towards facing), or stop. */
    public record CameraJob(UUID camera, BlockPos pos, Direction facing, boolean active) implements CustomPacketPayload {
        public static final Type<CameraJob> TYPE = new Type<>(Virtual_mashine.id("camera_job"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CameraJob> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.camera);
                    buf.writeBlockPos(payload.pos);
                    buf.writeByte(payload.facing.get3DDataValue());
                    buf.writeBoolean(payload.active);
                },
                buf -> new CameraJob(UUIDUtil.STREAM_CODEC.decode(buf), buf.readBlockPos(), Direction.from3DDataValue(buf.readByte()),
                        buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: part {@code index} of {@code count} of one JPEG picture of a camera. */
    public record CameraFrame(UUID camera, int frame, int index, int count, byte[] data) implements CustomPacketPayload {
        public static final Type<CameraFrame> TYPE = new Type<>(Virtual_mashine.id("camera_frame"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CameraFrame> CODEC = StreamCodec.ofMember(
                (payload, buf) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, payload.camera);
                    buf.writeVarInt(payload.frame);
                    buf.writeByte(payload.index);
                    buf.writeByte(payload.count);
                    buf.writeByteArray(payload.data);
                },
                buf -> new CameraFrame(UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readByte(), buf.readByte(),
                        buf.readByteArray(MAX_FRAME_CHUNK)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
