package dev.hannes.virtual_mashine.network;

import dev.hannes.virtual_mashine.Virtual_mashine;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server to client: everything the PC screens show about one machine.
 * Sent when a PC is opened ({@code open = true}) and whenever its state changes.
 */
public record PcInfoPayload(
        UUID pcId,
        boolean open,
        String state,
        int vncPort,
        boolean canFreeze,
        boolean diskExists,
        String messageKey,
        String messageDetail,
        String configJson,
        /* VNC port of every screen of the graphics card (empty for a machine with one screen). */
        List<Integer> screenPorts,
        /* Bit per screen that shows something (a monitor hangs on it; screen 0 always). */
        int activeScreens,
        List<String> isos,
        List<String> images,
        String isoFolder,
        boolean qemuFound,
        int hostCpus,
        int hostMaxRamMb,
        int hostFreeRamMb
) implements CustomPacketPayload {
    public static final Type<PcInfoPayload> TYPE = new Type<>(Virtual_mashine.id("pc_info"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PcInfoPayload> CODEC = StreamCodec.ofMember(PcInfoPayload::write, PcInfoPayload::read);

    private static final int MAX_DETAIL = 4000;

    private static PcInfoPayload read(RegistryFriendlyByteBuf buf) {
        UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
        boolean open = buf.readBoolean();
        String state = buf.readUtf(32);
        int vncPort = buf.readVarInt();
        boolean canFreeze = buf.readBoolean();
        boolean diskExists = buf.readBoolean();
        String messageKey = buf.readUtf(256);
        String messageDetail = buf.readUtf(MAX_DETAIL);
        String configJson = buf.readUtf();
        int screenCount = Math.min(buf.readVarInt(), 16);
        List<Integer> screenPorts = new ArrayList<>(screenCount);
        for (int i = 0; i < screenCount; i++) {
            screenPorts.add(buf.readVarInt());
        }
        int activeScreens = buf.readVarInt();
        int isoCount = buf.readVarInt();
        List<String> isos = new ArrayList<>(Math.min(isoCount, 512));
        for (int i = 0; i < isoCount; i++) {
            isos.add(buf.readUtf(1024));
        }
        int imageCount = buf.readVarInt();
        List<String> images = new ArrayList<>(Math.min(imageCount, 512));
        for (int i = 0; i < imageCount; i++) {
            images.add(buf.readUtf(1024));
        }
        String isoFolder = buf.readUtf(4096);
        boolean qemuFound = buf.readBoolean();
        int hostCpus = buf.readVarInt();
        int hostMaxRamMb = buf.readVarInt();
        int hostFreeRamMb = buf.readInt();
        return new PcInfoPayload(id, open, state, vncPort, canFreeze, diskExists, messageKey, messageDetail, configJson,
                screenPorts, activeScreens, isos, images, isoFolder, qemuFound, hostCpus, hostMaxRamMb, hostFreeRamMb);
    }

    private void write(RegistryFriendlyByteBuf buf) {
        UUIDUtil.STREAM_CODEC.encode(buf, pcId);
        buf.writeBoolean(open);
        buf.writeUtf(state, 32);
        buf.writeVarInt(vncPort);
        buf.writeBoolean(canFreeze);
        buf.writeBoolean(diskExists);
        buf.writeUtf(messageKey, 256);
        buf.writeUtf(messageDetail.length() > MAX_DETAIL ? messageDetail.substring(messageDetail.length() - MAX_DETAIL) : messageDetail, MAX_DETAIL);
        buf.writeUtf(configJson);
        buf.writeVarInt(screenPorts.size());
        for (int port : screenPorts) {
            buf.writeVarInt(port);
        }
        buf.writeVarInt(activeScreens);
        List<String> sent = isos.size() > 512 ? isos.subList(0, 512) : isos;
        buf.writeVarInt(sent.size());
        for (String iso : sent) {
            buf.writeUtf(iso, 1024);
        }
        List<String> sentImages = images.size() > 512 ? images.subList(0, 512) : images;
        buf.writeVarInt(sentImages.size());
        for (String image : sentImages) {
            buf.writeUtf(image, 1024);
        }
        buf.writeUtf(isoFolder, 4096);
        buf.writeBoolean(qemuFound);
        buf.writeVarInt(hostCpus);
        buf.writeVarInt(hostMaxRamMb);
        buf.writeInt(hostFreeRamMb);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
