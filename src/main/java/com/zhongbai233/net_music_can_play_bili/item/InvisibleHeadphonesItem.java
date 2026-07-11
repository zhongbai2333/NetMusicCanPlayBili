package com.zhongbai233.net_music_can_play_bili.item;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;

import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;

import java.util.function.Consumer;
import java.util.UUID;

/** 隐形耳机，用于把唱片机/MP4 音频私有路由给佩戴者。 */
public class InvisibleHeadphonesItem extends Item {
    public InvisibleHeadphonesItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (head.isEmpty()) {
            player.setItemSlot(EquipmentSlot.HEAD, stack.copyWithCount(1));
            stack.shrink(1);
            if (player instanceof ServerPlayer serverPlayer) {
                AudioLinkIndex.updatePlayerHeadphones(serverPlayer);
            }
            player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.headphones.equipped"));
            return InteractionResult.SUCCESS;
        }
        player.sendSystemMessage(
                Component.translatable("message.net_music_can_play_bili.headphones.equip_slot_occupied"));
        return InteractionResult.PASS;
    }

    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        BlockPos turntable = AudioLinkData.readHeadphoneTurntable(stack);
        if (turntable != null) {
            tooltip.accept(Component.translatable("tooltip.net_music_can_play_bili.headphones.turntable",
                    turntable.getX(), turntable.getY(), turntable.getZ()).withStyle(ChatFormatting.GRAY));
        }
        UUID mp4 = AudioLinkData.readHeadphoneMediaDevice(stack);
        if (mp4 != null) {
            String shortId = mp4.toString();
            if (shortId.length() > 8) {
                shortId = shortId.substring(0, 8);
            }
            tooltip.accept(Component.translatable("tooltip.net_music_can_play_bili.headphones.media_device", shortId)
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
