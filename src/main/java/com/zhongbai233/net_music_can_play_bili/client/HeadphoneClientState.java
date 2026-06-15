package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** 客户端耳机路由状态。 */
public final class HeadphoneClientState {
    private static final double TURNTABLE_RANGE_SQUARED = 64.0D * 64.0D;

    private HeadphoneClientState() {
    }

    public static boolean equipped() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        return HeadphoneAbility.has(minecraft.player.getItemBySlot(EquipmentSlot.HEAD));
    }

    public static boolean handlesTurntable(BlockPos turntablePos) {
        if (!equipped() || turntablePos == null) {
            return false;
        }
        ItemStack stack = equippedStack();
        if (stack.isEmpty()) {
            return false;
        }
        BlockPos linked = AudioLinkData.readHeadphoneTurntable(stack);
        return turntablePos.equals(linked) && withinDecodeRange(turntablePos);
    }

    public static boolean suppressesTurntable(BlockPos turntablePos) {
        return equipped() && !handlesTurntable(turntablePos);
    }

    public static boolean linkedTurntableOutOfRange(BlockPos turntablePos) {
        if (!equipped() || turntablePos == null) {
            return false;
        }
        ItemStack stack = equippedStack();
        if (stack.isEmpty()) {
            return false;
        }
        BlockPos linked = AudioLinkData.readHeadphoneTurntable(stack);
        return turntablePos.equals(linked) && !withinDecodeRange(turntablePos);
    }

    public static boolean handlesMp4(UUID deviceId) {
        if (!equipped() || deviceId == null) {
            return false;
        }
        ItemStack stack = equippedStack();
        return !stack.isEmpty() && deviceId.equals(AudioLinkData.readHeadphoneMp4(stack));
    }

    private static boolean withinDecodeRange(BlockPos turntablePos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        return minecraft.player.distanceToSqr(turntablePos.getCenter()) <= TURNTABLE_RANGE_SQUARED;
    }

    private static ItemStack equippedStack() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        return minecraft.player.getItemBySlot(EquipmentSlot.HEAD);
    }
}
