package com.zhongbai233.net_music_can_play_bili.init;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.block.LyricProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.block.SpeakerBlock;
import com.zhongbai233.net_music_can_play_bili.block.VideoProjectorBlock;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(NetMusicCanPlayBili.MODID);

    public static final DeferredBlock<Block> MODERN_TURNTABLE = BLOCKS.register(
            "modern_turntable",
            ModernTurntableBlock::new);

    public static final DeferredBlock<Block> LYRIC_PROJECTOR = BLOCKS.register(
            "lyric_projector",
            LyricProjectorBlock::new);

    public static final DeferredBlock<Block> VIDEO_PROJECTOR = BLOCKS.register(
            "video_projector",
            VideoProjectorBlock::new);

    public static final DeferredBlock<Block> SPEAKER = BLOCKS.register(
            "speaker",
            SpeakerBlock::new);

    private ModBlocks() {
    }
}
