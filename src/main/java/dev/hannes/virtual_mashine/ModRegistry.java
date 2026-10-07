package dev.hannes.virtual_mashine;

import com.mojang.serialization.Codec;
import dev.hannes.virtual_mashine.block.DeviceBlock;
import dev.hannes.virtual_mashine.block.DeviceBlockEntity;
import dev.hannes.virtual_mashine.device.CableType;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.item.CableItem;
import dev.hannes.virtual_mashine.item.NetworkTesterItem;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public final class ModRegistry {
    /** Which machine a device block (or its dropped item) belongs to. */
    public static final DataComponentType<UUID> PC_ID = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Virtual_mashine.id("pc_id"),
            DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build()
    );

    /** Jacket colour of a cable item (RGB). */
    public static final DataComponentType<Integer> CABLE_COLOR = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Virtual_mashine.id("cable_color"),
            DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.INT).build()
    );

    public static final Map<DeviceType, Block> DEVICE_BLOCKS = new EnumMap<>(DeviceType.class);
    public static final Map<DeviceType, Item> DEVICE_ITEMS = new EnumMap<>(DeviceType.class);
    public static final Map<CableType, Item> CABLES = new EnumMap<>(CableType.class);

    public static final Block PC_BLOCK = device(DeviceType.PC, Shapes.or(Block.box(0, 0, 0, 16, 5, 16), Block.box(2, 5, 2, 14, 15, 14)), MapColor.SAND, 7);
    public static final Block SERVER_BLOCK = device(DeviceType.SERVER, Block.box(2, 0, 2, 14, 15, 14), MapColor.COLOR_GRAY, 3);
    public static final Block ROUTER_BLOCK = device(DeviceType.ROUTER, Block.box(2, 0, 2, 14, 3, 14), MapColor.COLOR_BLACK, 3);
    public static final Block MANAGED_SWITCH_BLOCK = device(DeviceType.MANAGED_SWITCH, Block.box(0, 0, 0, 16, 4, 16), MapColor.COLOR_BLUE, 3);
    public static final Block SWITCH_BLOCK = device(DeviceType.SWITCH, Block.box(0, 0, 0, 16, 4, 16), MapColor.METAL, 3);
    public static final Block MODEM_BLOCK = device(DeviceType.MODEM, Block.box(4, 0, 4, 12, 7, 12), MapColor.SNOW, 3);
    public static final Block LAPTOP_BLOCK = device(DeviceType.LAPTOP, Block.box(2, 0, 2, 14, 9, 14), MapColor.COLOR_GRAY, 5);
    public static final Block ACCESS_POINT_BLOCK = device(DeviceType.ACCESS_POINT, Block.box(4, 0, 4, 12, 3, 12), MapColor.SNOW, 3);

    public static final Item PC_ITEM = DEVICE_ITEMS.get(DeviceType.PC);

    static {
        for (CableType type : CableType.values()) {
            CABLES.put(type, item(type.id + "_cable", properties -> new CableItem(type, properties)));
        }
    }

    public static final Item NETWORK_TESTER = item("network_tester", NetworkTesterItem::new);

    /** Registry id stays "pc" so PCs from earlier versions keep their machine. */
    public static final BlockEntityType<DeviceBlockEntity> DEVICE_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Virtual_mashine.id("pc"),
            FabricBlockEntityTypeBuilder.create(DeviceBlockEntity::new, DEVICE_BLOCKS.values().toArray(new Block[0])).build()
    );


    private ModRegistry() {
    }

    /** One creative tab per category, like the vanilla ones. */
    public static void register() {
        tab("devices", DEVICE_ITEMS.get(DeviceType.PC), DEVICE_ITEMS.get(DeviceType.PC), DEVICE_ITEMS.get(DeviceType.LAPTOP),
                DEVICE_ITEMS.get(DeviceType.SERVER));
        tab("network_devices", DEVICE_ITEMS.get(DeviceType.SWITCH), DEVICE_ITEMS.get(DeviceType.SWITCH),
                DEVICE_ITEMS.get(DeviceType.MANAGED_SWITCH), DEVICE_ITEMS.get(DeviceType.ROUTER), DEVICE_ITEMS.get(DeviceType.ACCESS_POINT));
        tab("internet", DEVICE_ITEMS.get(DeviceType.MODEM), DEVICE_ITEMS.get(DeviceType.MODEM));
        java.util.List<Item> cablesAndTools = new java.util.ArrayList<>(CABLES.values());
        cablesAndTools.add(NETWORK_TESTER);
        tab("cables", CABLES.get(CableType.CAT6), cablesAndTools.toArray(new Item[0]));
    }

    private static void tab(String name, Item icon, Item... items) {
        ResourceKey<CreativeModeTab> key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Virtual_mashine.id(name));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key, FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.virtual_mashine." + name))
                .icon(() -> new ItemStack(icon))
                .displayItems((parameters, output) -> {
                    for (Item item : items) {
                        output.accept(item);
                    }
                })
                .build());
    }

    private static Block device(DeviceType type, VoxelShape shape, MapColor color, int light) {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, Virtual_mashine.id(type.id));
        Block block = Blocks.register(blockKey, properties -> new DeviceBlock(type, shape, properties),
                BlockBehaviour.Properties.of()
                        .mapColor(color)
                        .strength(2.0F, 6.0F)
                        .sound(SoundType.METAL)
                        .requiresCorrectToolForDrops()
                        .noOcclusion()
                        .lightLevel(state -> state.getValue(DeviceBlock.LIT) ? light : 0));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Virtual_mashine.id(type.id));
        Item item = Registry.register(BuiltInRegistries.ITEM, itemKey,
                new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
        DEVICE_BLOCKS.put(type, block);
        DEVICE_ITEMS.put(type, item);
        return block;
    }

    private static Item item(String name, Function<Item.Properties, Item> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Virtual_mashine.id(name));
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
    }
}
