package com.zhongbai233.net_music_can_play_bili.init;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.item.InvisibleHeadphonesItem;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.MediaManagementToolItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(NetMusicCanPlayBili.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB,
            NetMusicCanPlayBili.MODID);

    public static final DeferredItem<BlockItem> MODERN_TURNTABLE = ITEMS.registerSimpleBlockItem("modern_turntable",
            ModBlocks.MODERN_TURNTABLE);

    public static final DeferredItem<BlockItem> LYRIC_PROJECTOR = ITEMS.registerSimpleBlockItem("lyric_projector",
            ModBlocks.LYRIC_PROJECTOR);

    public static final DeferredItem<BlockItem> VIDEO_PROJECTOR = ITEMS.registerSimpleBlockItem("video_projector",
            ModBlocks.VIDEO_PROJECTOR);

    public static final DeferredItem<BlockItem> SPEAKER = ITEMS.registerSimpleBlockItem("speaker",
            ModBlocks.SPEAKER);

    public static final DeferredItem<MP4Item> MP4 = ITEMS.registerItem("mp4",
            MP4Item::new,
            Item.Properties::new);

    public static final DeferredItem<PadItem> PAD = ITEMS.registerItem("pad",
            PadItem::new,
            Item.Properties::new);

    public static final DeferredItem<MediaManagementToolItem> MEDIA_MANAGEMENT_TOOL = ITEMS.registerItem(
            "media_management_tool",
            MediaManagementToolItem::new,
            properties -> properties.stacksTo(1));

    public static final DeferredItem<InvisibleHeadphonesItem> INVISIBLE_HEADPHONES = ITEMS.registerItem(
            "invisible_headphones",
            InvisibleHeadphonesItem::new,
            ModItems::headphoneItemProperties);

    public static final DeferredItem<InvisibleHeadphonesItem> CAT_HEADPHONES = ITEMS.registerItem(
            "cat_headphones",
            InvisibleHeadphonesItem::new,
            ModItems::headphoneItemProperties);

    public static final DeferredItem<HolographicGlassesItem> HOLOGRAPHIC_GLASSES = ITEMS.registerItem(
            "holographic_glasses",
            HolographicGlassesItem::new,
            ModItems::holographicGlassesItemProperties);

    private static Item.Properties headphoneItemProperties(Item.Properties properties) {
        return properties.attributes(headphoneAttributeModifiers())
                .component(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.HEAD)
                        .setEquipSound(SoundEvents.ARMOR_EQUIP_GENERIC)
                        .setSwappable(false)
                        .build());
    }

    private static Item.Properties holographicGlassesItemProperties(Item.Properties properties) {
        return properties.attributes(holographicGlassesAttributeModifiers())
                .component(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.HEAD)
                        .setEquipSound(SoundEvents.ARMOR_EQUIP_GENERIC)
                        .setSwappable(false)
                        .build());
    }

    private static ItemAttributeModifiers headphoneAttributeModifiers() {
        return ItemAttributeModifiers.builder()
                .add(ModAttributes.HEADPHONES,
                        new AttributeModifier(Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                                "headphones"), 1.0D, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.HEAD)
                .build();
    }

    private static ItemAttributeModifiers holographicGlassesAttributeModifiers() {
        return ItemAttributeModifiers.builder()
                .add(ModAttributes.HOLOGRAPHIC_GLASSES,
                        new AttributeModifier(Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                                "holographic_glasses"), 1.0D, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.HEAD)
                .build();
    }

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.net_music_can_play_bili"))
                    .icon(() -> new ItemStack(MODERN_TURNTABLE.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(MODERN_TURNTABLE.get());
                        output.accept(LYRIC_PROJECTOR.get());
                        output.accept(VIDEO_PROJECTOR.get());
                        output.accept(SPEAKER.get());
                        output.accept(MP4.get());
                        output.accept(PAD.get());
                        output.accept(MEDIA_MANAGEMENT_TOOL.get());
                        output.accept(INVISIBLE_HEADPHONES.get());
                        output.accept(CAT_HEADPHONES.get());
                        output.accept(HOLOGRAPHIC_GLASSES.get());
                        parameters.holders().lookupOrThrow(Registries.ENCHANTMENT)
                                .get(HeadphoneAbility.HEADPHONES_KEY)
                                .ifPresent(enchantment -> output.accept(EnchantmentHelper.createBook(
                                        new EnchantmentInstance(enchantment, 1))));
                        parameters.holders().lookupOrThrow(Registries.ENCHANTMENT)
                                .get(HolographicGlassesAbility.HOLOGRAPHIC_GLASSES_KEY)
                                .ifPresent(enchantment -> output.accept(EnchantmentHelper.createBook(
                                        new EnchantmentInstance(enchantment, 1))));
                    })
                    .build());

    private ModItems() {
    }
}
