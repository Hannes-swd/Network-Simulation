package dev.hannes.virtual_mashine.block;

import com.mojang.serialization.MapCodec;
import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.item.CableItem;
import dev.hannes.virtual_mashine.item.NetworkTesterItem;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Every network device block. Right-click: the machine's screen (computers) or its port panel;
 * right-click with a cable or while sneaking: the port panel to plug and unplug cables.
 */
public class DeviceBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Power LED: the device runs. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    private final DeviceType type;
    private final VoxelShape shape;

    public DeviceBlock(DeviceType type, VoxelShape shape, BlockBehaviour.Properties properties) {
        super(properties);
        this.type = type;
        this.shape = shape;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    public DeviceType deviceType() {
        return type;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeviceBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide() ? null : createTickerHelper(blockEntityType, ModRegistry.DEVICE_BLOCK_ENTITY, DeviceBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hitResult) {
        boolean tester = stack.getItem() instanceof NetworkTesterItem;
        if (!(stack.getItem() instanceof CableItem) && !tester) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            openPorts(serverPlayer, level, pos, state, tester);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof DeviceBlockEntity device) {
            VmManager manager = VmManager.get();
            if (manager == null) {
                return InteractionResult.SUCCESS;
            }
            if (type.isVm() && !player.isShiftKeyDown()) {
                manager.openFor(serverPlayer, device.getOrCreatePcId(), type);
            } else if (type == DeviceType.ACCESS_POINT && !player.isShiftKeyDown()) {
                manager.network().openApView(serverPlayer, device.getOrCreatePcId(), pos, state.getValue(FACING), "");
            } else {
                openPorts(serverPlayer, level, pos, state, false);
            }
        }
        return InteractionResult.SUCCESS;
    }

    private void openPorts(ServerPlayer player, Level level, BlockPos pos, BlockState state, boolean tester) {
        VmManager manager = VmManager.get();
        if (manager != null && level.getBlockEntity(pos) instanceof DeviceBlockEntity device) {
            manager.network().openPortView(player, device.getOrCreatePcId(), type, pos, state.getValue(FACING), tester);
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }
}
