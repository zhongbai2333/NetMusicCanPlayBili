package com.zhongbai233.net_music_can_play_bili.init;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister
            .create(Registries.BLOCK_ENTITY_TYPE, NetMusicCanPlayBili.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ModernTurntableBlockEntity>> MODERN_TURNTABLE = BLOCK_ENTITY_TYPES
            .register(
                    "modern_turntable",
                    () -> new BlockEntityType<>(
                            ModernTurntableBlockEntity::new,
                            Set.of(ModBlocks.MODERN_TURNTABLE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LyricProjectorBlockEntity>> LYRIC_PROJECTOR = BLOCK_ENTITY_TYPES
            .register(
                    "lyric_projector",
                    () -> new BlockEntityType<>(
                            LyricProjectorBlockEntity::new,
                            Set.of(ModBlocks.LYRIC_PROJECTOR.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SpeakerBlockEntity>> SPEAKER = BLOCK_ENTITY_TYPES
            .register(
                    "speaker",
                    () -> new BlockEntityType<>(
                            SpeakerBlockEntity::new,
                            Set.of(ModBlocks.SPEAKER.get())));

    private ModBlockEntities() {
    }
}
