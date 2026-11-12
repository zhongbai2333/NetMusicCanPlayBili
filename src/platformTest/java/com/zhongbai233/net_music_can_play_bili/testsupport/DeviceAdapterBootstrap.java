package com.zhongbai233.net_music_can_play_bili.testsupport;

import com.zhongbai233.net_music_can_play_bili.block.*;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.init.*;
import com.zhongbai233.net_music_can_play_bili.item.MediaManagementToolItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.GameData;
import java.util.Set;

/** Additional real item/block registrations for adapter tests; no mod constructor or FML event pipeline. */
public final class DeviceAdapterBootstrap {
    private static boolean initialized;
    private DeviceAdapterBootstrap() {}
    public static synchronized void initialize() {
        if (initialized) return;
        PlatformBootstrap.initialize();
        GameData.unfreezeData();
        Block lyric = Registry.register(BuiltInRegistries.BLOCK, ModBlocks.LYRIC_PROJECTOR.getId(),
                new LyricProjectorBlock(ModBlocks.LYRIC_PROJECTOR.getId()));
        Block video = Registry.register(BuiltInRegistries.BLOCK, ModBlocks.VIDEO_PROJECTOR.getId(),
                new VideoProjectorBlock(ModBlocks.VIDEO_PROJECTOR.getId()));
        Block speaker = Registry.register(BuiltInRegistries.BLOCK, ModBlocks.SPEAKER.getId(),
                new SpeakerBlock(ModBlocks.SPEAKER.getId()));
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModBlockEntities.SPEAKER.getId(),
                new BlockEntityType<>(SpeakerBlockEntity::new, Set.of(speaker)));
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModBlockEntities.VIDEO_PROJECTOR.getId(),
                new BlockEntityType<>(VideoProjectorBlockEntity::new, Set.of(video)));
        blockItem(ModItems.LYRIC_PROJECTOR.getId(), lyric);
        blockItem(ModItems.VIDEO_PROJECTOR.getId(), video);
        blockItem(ModItems.SPEAKER.getId(), speaker);
        blockItem(ModItems.CONTROL_CONSOLE.getId(), ModBlocks.CONTROL_CONSOLE.get());
        blockItem(ModItems.MODERN_TURNTABLE.getId(), ModBlocks.MODERN_TURNTABLE.get());
        blockItem(ModItems.LIVE_STREAMER.getId(), ModBlocks.LIVE_STREAMER.get());
        Identifier toolId = ModItems.MEDIA_MANAGEMENT_TOOL.getId();
        Registry.register(BuiltInRegistries.ITEM, toolId, new MediaManagementToolItem(properties(toolId)));
        GameData.freezeData();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())
                .forEach(components -> components.apply());
        initialized = true;
    }
    private static Item.Properties properties(Identifier id) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
    }
    private static void blockItem(Identifier id, Block block) {
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, properties(id)));
    }
}
