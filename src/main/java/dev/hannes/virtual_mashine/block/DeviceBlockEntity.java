package dev.hannes.virtual_mashine.block;

import dev.hannes.virtual_mashine.ModRegistry;
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

    public static void serverTick(Level level, BlockPos pos, BlockState state, DeviceBlockEntity device) {
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
        }
        boolean powered = manager.network().isPowered(device.pcId, block.deviceType());
        if (state.getValue(DeviceBlock.LIT) != powered) {
            level.setBlock(pos, state.setValue(DeviceBlock.LIT, powered), Block.UPDATE_ALL);
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
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("pc_id", UUIDUtil.CODEC, pcId);
        output.putInt("link_leds", linkLeds);
        output.putInt("activity_leds", activityLeds);
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
