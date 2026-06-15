package com.zhongbai233.net_music_can_play_bili.link;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 存储在音频路由物品上的共享持久绑定数据。
 * <p>
 * 本类统一管理耳机绑定的 CUSTOM_DATA 结构，避免物品交互、客户端路由和服务端索引直接依赖原始 NBT 键。
 * </p>
 */
public final class AudioLinkData {
    public static final double MP4_HEADPHONE_RANGE_SQUARED = 64.0D * 64.0D;

    private static final String HEADPHONE_TURNTABLE_X = "headphones_turntable_x";
    private static final String HEADPHONE_TURNTABLE_Y = "headphones_turntable_y";
    private static final String HEADPHONE_TURNTABLE_Z = "headphones_turntable_z";
    private static final String HEADPHONE_MP4 = "headphones_mp4";

    private AudioLinkData() {
    }

    public static void writeHeadphoneTurntable(ItemStack stack, BlockPos pos) {
        if (stack.isEmpty() || pos == null) {
            return;
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> {
            tag.putInt(HEADPHONE_TURNTABLE_X, pos.getX());
            tag.putInt(HEADPHONE_TURNTABLE_Y, pos.getY());
            tag.putInt(HEADPHONE_TURNTABLE_Z, pos.getZ());
        }));
    }

    @Nullable
    public static BlockPos readHeadphoneTurntable(ItemStack stack) {
        CompoundTag tag = customTag(stack);
        if (tag == null || !tag.contains(HEADPHONE_TURNTABLE_X) || !tag.contains(HEADPHONE_TURNTABLE_Y)
                || !tag.contains(HEADPHONE_TURNTABLE_Z)) {
            return null;
        }
        return new BlockPos(tag.getIntOr(HEADPHONE_TURNTABLE_X, 0), tag.getIntOr(HEADPHONE_TURNTABLE_Y, 0),
                tag.getIntOr(HEADPHONE_TURNTABLE_Z, 0));
    }

    public static void writeHeadphoneMp4(ItemStack stack, UUID deviceId) {
        if (stack.isEmpty() || deviceId == null) {
            return;
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                customData -> customData.update(tag -> tag.putString(HEADPHONE_MP4, deviceId.toString())));
    }

    @Nullable
    public static UUID readHeadphoneMp4(ItemStack stack) {
        CompoundTag tag = customTag(stack);
        String value = tag != null ? tag.getString(HEADPHONE_MP4).orElse("") : "";
        if (value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static void clearHeadphoneMp4(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                customData -> customData.update(tag -> tag.remove(HEADPHONE_MP4)));
    }

    public static boolean headphoneLinkedToMp4(ItemStack stack, UUID deviceId) {
        return deviceId != null && deviceId.equals(readHeadphoneMp4(stack));
    }

    @Nullable
    private static CompoundTag customTag(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData != null && !customData.isEmpty() ? customData.copyTag() : null;
    }
}
