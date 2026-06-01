package com.zhongbai233.net_music_can_play_bili.link;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import javax.annotation.Nullable;

/**
 * 方块链接的通用工具方法。
 * 支持两种存储方式：
 * <ul>
 * <li>物品 NBT（{@link DataComponents#CUSTOM_DATA}）：手持链接物品时创建链接</li>
 * <li>方块实体 NBT（{@link ValueOutput}/{@link ValueInput}）：方块放置后持久化</li>
 * </ul>
 */
public final class LinkHelper {
    /** 物品 CUSTOM_DATA 中存储链接目标的 key */
    public static final String LINK_X = "linked_x";
    public static final String LINK_Y = "linked_y";
    public static final String LINK_Z = "linked_z";

    private LinkHelper() {
    }

    // ──── 物品 NBT 操作 ────

    /** 将目标位置写入物品，并添加附魔光效 */
    public static void writeLinkToItem(ItemStack stack, BlockPos targetPos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(LINK_X, targetPos.getX());
        tag.putInt(LINK_Y, targetPos.getY());
        tag.putInt(LINK_Z, targetPos.getZ());
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                existing -> existing.update(existingTag -> existingTag.merge(tag)));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    /** 从物品读取链接目标位置，若未设置则返回 null */
    @Nullable
    public static BlockPos readLinkFromItem(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty())
            return null;
        CompoundTag tag = customData.copyTag();
        if (!tag.contains(LINK_X))
            return null;
        return new BlockPos(
                tag.getInt(LINK_X).orElse(0),
                tag.getInt(LINK_Y).orElse(0),
                tag.getInt(LINK_Z).orElse(0));
    }

    /** 清除物品上的链接数据和光效 */
    public static void clearLinkFromItem(ItemStack stack) {
        stack.remove(DataComponents.CUSTOM_DATA);
        stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    }

    // ──── 方块实体 NBT 操作 ────

    /** 将链接位置写入 BE 持久化数据 */
    public static void saveLinkToBE(ValueOutput output, @Nullable BlockPos pos,
            String hasKey, String xKey, String yKey, String zKey) {
        output.putBoolean(hasKey, pos != null);
        if (pos != null) {
            output.putInt(xKey, pos.getX());
            output.putInt(yKey, pos.getY());
            output.putInt(zKey, pos.getZ());
        }
    }

    /** 从 BE 持久化数据读取链接位置，若不存在则返回 null */
    @Nullable
    public static BlockPos loadLinkFromBE(ValueInput input,
            String hasKey, String xKey, String yKey, String zKey) {
        if (!input.getBooleanOr(hasKey, false))
            return null;
        return new BlockPos(
                input.getIntOr(xKey, 0),
                input.getIntOr(yKey, 0),
                input.getIntOr(zKey, 0));
    }
}
