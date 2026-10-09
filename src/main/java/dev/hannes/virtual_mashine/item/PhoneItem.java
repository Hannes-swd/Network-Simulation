package dev.hannes.virtual_mashine.item;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * A smartphone running Android in its own VM. It is carried, not placed: its only network card is
 * WLAN, and the signal is measured from wherever the player holding it stands.
 */
public class PhoneItem extends Item {
    public PhoneItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            VmManager manager = VmManager.get();
            if (manager != null) {
                ItemStack stack = player.getItemInHand(hand);
                UUID id = stack.get(ModRegistry.PC_ID);
                if (id == null) {
                    id = UUID.randomUUID();
                    stack.set(ModRegistry.PC_ID, id);
                }
                manager.network().phoneCarried(id, serverPlayer);
                manager.openFor(serverPlayer, id, DeviceType.PHONE);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("item.virtual_mashine.phone.desc").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("item.virtual_mashine.phone.wlan").withStyle(ChatFormatting.DARK_GRAY));
    }
}
