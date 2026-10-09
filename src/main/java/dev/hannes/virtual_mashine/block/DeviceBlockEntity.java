package dev.hannes.virtual_mashine.block;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.net.IoModule;
import net.minecraft.core.Direction;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Remembers which machine belongs to this device. The id travels with the dropped item (see the
 * block's loot table), so a device that is broken and placed again keeps its disk.
 */
public class DeviceBlockEntity extends BlockEntity {
    private @Nullable UUID pcId;
    /** Per port: bit set = link LED on. Synced to clients for the LEDs drawn on the housing. */
    private int linkLeds;
    /** Per port: bit set = traffic in the last moment (blinking amber LED). */
    private int activityLeds;
    /** I/O module: output levels and output mask as in {@link IoModule#packedOutputs()}, synced for the signals. */
    private int ioOutputs;
    /** Display: size in blocks and the VNC port of its running system (0 = off), synced for drawing. */
    private int displayWidth = 2;
    private int displayHeight = 1;
    private int displayPort;
    /** Microphone and camera: the switch on the device. */
    private boolean usbEnabled = true;

    public DeviceBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.DEVICE_BLOCK_ENTITY, pos, state);
    }

    public UUID getOrCreatePcId() {
        if (pcId == null) {
            pcId = UUID.randomUUID();
            setChanged();
        }
        return pcId;
    }

    public @Nullable UUID getPcId() {
        return pcId;
    }

    public int linkLeds() {
        return linkLeds;
    }

    public int activityLeds() {
        return activityLeds;
    }

    public int ioOutputs() {
        return ioOutputs;
    }

    public int displayWidth() {
        return displayWidth;
    }

    public int displayHeight() {
        return displayHeight;
    }

    public int displayPort() {
        return displayPort;
    }

    public boolean usbEnabled() {
        return usbEnabled;
    }

    public void setUsbEnabled(boolean enabled) {
        usbEnabled = enabled;
        setChanged();
    }

    /**
     * Every tick: reads the signal arriving at each input face and drives the output faces with
     * what the network set, so a Modbus write moves a piston in the next tick.
     */
    private static void ioTick(ServerLevel level, BlockPos pos, BlockState state, DeviceBlockEntity device, VmManager manager) {
        IoModule io = manager.network().ioModule(device.getOrCreatePcId());
        Direction facing = state.getValue(DeviceBlock.FACING);
        for (int ch = 0; ch < IoModule.CHANNELS; ch++) {
            Direction side = DeviceBlock.ioSide(facing, ch);
            io.setInput(ch, io.isOutput(ch) ? 0 : level.getSignal(pos.relative(side), side));
        }
        int packed = io.packedOutputs();
        if (packed != device.ioOutputs) {
            int before = device.ioOutputs;
            device.ioOutputs = packed;
            device.setChanged();
            level.updateNeighborsAt(pos, state.getBlock());
            for (int ch = 0; ch < IoModule.CHANNELS; ch++) {
                if (IoModule.packedLevel(before, ch) != IoModule.packedLevel(packed, ch)
                        || IoModule.packedIsOutput(before, ch) != IoModule.packedIsOutput(packed, ch)) {
                    // The block behind an output face is strongly powered: its neighbours must hear it too.
                    level.updateNeighborsAt(pos.relative(DeviceBlock.ioSide(facing, ch)), state.getBlock());
                }
            }
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DeviceBlockEntity device) {
        if (state.getBlock() instanceof DeviceBlock io && io.deviceType() == DeviceType.IO_MODULE
                && level instanceof ServerLevel serverLevel && VmManager.get() != null) {
            ioTick(serverLevel, pos, state, device, VmManager.get());
        }
        if (level.getGameTime() % 10 != 0 || !(state.getBlock() instanceof DeviceBlock block)
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        device.getOrCreatePcId();
        VmManager manager = VmManager.get();
        if (manager == null) {
            return;
        }
        if (level.getGameTime() % 20 == 0) {
            manager.network().deviceTick(device.pcId, block.deviceType(), serverLevel, pos, state.getValue(DeviceBlock.FACING));
            if (block.deviceType().isUsbDevice()) {
                manager.network().setUsbEnabled(device.pcId, device.usbEnabled);
            }
        }
        boolean powered = manager.network().isPowered(device.pcId, block.deviceType());
        if (state.getValue(DeviceBlock.LIT) != powered) {
            level.setBlock(pos, state.setValue(DeviceBlock.LIT, powered), Block.UPDATE_ALL);
        }
        if (block.deviceType() == DeviceType.DISPLAY) {
            int[] display = manager.displayState(device.pcId);
            if (display[0] != device.displayWidth || display[1] != device.displayHeight || display[2] != device.displayPort) {
                device.displayWidth = display[0];
                device.displayHeight = display[1];
                device.displayPort = display[2];
                device.setChanged();
                level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
            }
        }
        long leds = manager.network().ledState(device.pcId, block.deviceType());
        int link = (int) leds;
        int activity = (int) (leds >>> 32);
        if (link != device.linkLeds || activity != device.activityLeds) {
            device.linkLeds = link;
            device.activityLeds = activity;
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        VmManager manager = VmManager.get();
        if (pcId != null && manager != null && level instanceof ServerLevel serverLevel) {
            manager.onPcRemoved(pcId);
            manager.network().deviceRemoved(pcId, serverLevel, pos);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        pcId = input.read("pc_id", UUIDUtil.CODEC).orElse(null);
        linkLeds = input.getIntOr("link_leds", 0);
        activityLeds = input.getIntOr("activity_leds", 0);
        ioOutputs = input.getIntOr("io_outputs", 0);
        displayWidth = input.getIntOr("display_width", 2);
        displayHeight = input.getIntOr("display_height", 1);
        displayPort = input.getIntOr("display_port", 0);
        usbEnabled = input.getBooleanOr("usb_enabled", true);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("pc_id", UUIDUtil.CODEC, pcId);
        output.putInt("link_leds", linkLeds);
        output.putInt("activity_leds", activityLeds);
        output.putInt("io_outputs", ioOutputs);
        output.putInt("display_width", displayWidth);
        output.putInt("display_height", displayHeight);
        output.putInt("display_port", displayPort);
        output.putBoolean("usb_enabled", usbEnabled);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        UUID fromItem = components.get(ModRegistry.PC_ID);
        if (fromItem != null) {
            pcId = fromItem;
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (pcId != null) {
            components.set(ModRegistry.PC_ID, pcId);
        }
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("pc_id");
    }
}
