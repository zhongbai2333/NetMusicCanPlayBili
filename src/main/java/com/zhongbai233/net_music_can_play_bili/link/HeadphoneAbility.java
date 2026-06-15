package com.zhongbai233.net_music_can_play_bili.link;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.init.ModAttributes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;

/** 可作为耳机使用的装备的共用判定。 */
public final class HeadphoneAbility {
    public static final Identifier HEADPHONES_ID = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "headphones");
    public static final ResourceKey<Enchantment> HEADPHONES_KEY = ResourceKey.create(Registries.ENCHANTMENT,
            HEADPHONES_ID);
    public static final TagKey<Enchantment> HEADPHONES_ENCHANTMENT_TAG = TagKey.create(Registries.ENCHANTMENT,
            HEADPHONES_ID);

    private HeadphoneAbility() {
    }

    public static boolean has(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return isHeadEquipment(stack) && hasHeadphoneAttribute(stack);
    }

    private static boolean isHeadEquipment(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.HEAD;
    }

    private static boolean hasHeadphoneAttribute(ItemStack stack) {
        boolean[] found = { false };
        stack.forEachModifier(EquipmentSlotGroup.HEAD, (attribute, modifier, display) -> {
            if (attribute.is(ModAttributes.HEADPHONES.getKey()) && modifier.amount() > 0.0D) {
                found[0] = true;
            }
        });
        if (found[0]) {
            return true;
        }
        EnchantmentHelper.forEachModifier(stack, EquipmentSlotGroup.HEAD, (attribute, modifier) -> {
            if (attribute.is(ModAttributes.HEADPHONES.getKey()) && modifier.amount() > 0.0D) {
                found[0] = true;
            }
        });
        return found[0];
    }
}
