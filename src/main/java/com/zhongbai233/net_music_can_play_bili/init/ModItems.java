package com.zhongbai233.net_music_can_play_bili.init;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
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
                    })
                    .build());

    private ModItems() {
    }
}
