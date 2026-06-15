package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.renderer.LyricProjectorRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ModernTurntableRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.SpeakerRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.VideoProjectorRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.YuvVideoRenderTypes;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.slf4j.Logger;

public final class ModernTurntableClientEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ModernTurntableClientEvents() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModernTurntableClientEvents::registerRenderers);
        modEventBus.addListener(YuvVideoRenderTypes::registerPipelines);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.MODERN_TURNTABLE.get(), ModernTurntableRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.LYRIC_PROJECTOR.get(), LyricProjectorRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.VIDEO_PROJECTOR.get(), VideoProjectorRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.SPEAKER.get(), SpeakerRenderer::new);
        warmupClientResources();
    }

    private static void warmupClientResources() {
        Minecraft.getInstance().execute(() -> {
            try {
                MP4ItemScreenRenderer.warmup();
                LOGGER.debug("MP4 handheld GUI resources warmed up");
            } catch (Exception e) {
                LOGGER.warn("MP4 handheld GUI resource warmup failed; falling back to lazy initialization", e);
            }
        });
    }
}