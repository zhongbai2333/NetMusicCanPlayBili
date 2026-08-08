package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.renderer.LyricProjectorRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ModernTurntableRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.SpeakerRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.VideoProjectorRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderState;
import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.CuriosHeadGearLayer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.HolographicPrivacyOverlay;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.YuvVideoRenderTypes;
import com.zhongbai233.net_music_can_play_bili.gui.MediaToolBindingScreen;
import com.zhongbai233.net_music_can_play_bili.gui.MediaToolReportScreen;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModMenus;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

public final class ModernTurntableClientEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ModernTurntableClientEvents() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModernTurntableClientEvents::registerRenderers);
        modEventBus.addListener(CuriosHeadGearLayer::register);
        modEventBus.addListener(ModernTurntableClientEvents::registerPictureInPictureRenderers);
        modEventBus.addListener(ModernTurntableClientEvents::registerMenuScreens);
        modEventBus.addListener(HolographicGlassesKeyHandler::register);
        modEventBus.addListener(YuvVideoRenderTypes::registerPipelines);
        NeoForge.EVENT_BUS.addListener(ModernTurntableClientEvents::onClientLogout);
        NeoForge.EVENT_BUS.addListener(ModernTurntableClientEvents::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onMovementInput);
        NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onMouseButton);
        NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onInteraction);
        NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onMouseScroll);
        NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onRenderGui);
        NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onSubmitGeometry);
        NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onLogout);
        NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onClone);
    }

    private static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (Minecraft.getInstance().screen instanceof HolographicScreenConfigTestScreen screen) {
            double deltaSeconds = Math.clamp(event.getPartialTick().getRealtimeDeltaTicks() / 20.0D,
                    0.0D, 0.1D);
            screen.advanceCameraFrame(deltaSeconds);
        }
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        HolographicPrivacyOverlay.release();
    }

    private static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MEDIA_TOOL_BINDING.get(), MediaToolBindingScreen::new);
        event.register(ModMenus.MEDIA_TOOL_REPORT.get(), MediaToolReportScreen::new);
    }

    private static void registerPictureInPictureRenderers(RegisterPictureInPictureRenderersEvent event) {
        event.register(HolographicPreviewPipRenderState.class, HolographicPreviewPipRenderer::new);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.MODERN_TURNTABLE.get(), ModernTurntableRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.LYRIC_PROJECTOR.get(), LyricProjectorRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.VIDEO_PROJECTOR.get(), VideoProjectorRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.SPEAKER.get(), SpeakerRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.CONTROL_CONSOLE.get(), ControlConsoleRenderer::new);
        warmupClientResources();
    }

    private static void warmupClientResources() {
        Minecraft.getInstance().execute(() -> {
            try {
                MP4ItemScreenRenderer.warmup();
                PadItemScreenRenderer.warmup();
                LOGGER.debug("MP4/Pad handheld GUI resources warmed up");
            } catch (Exception e) {
                LOGGER.warn("MP4/Pad handheld GUI resource warmup failed; falling back to lazy initialization", e);
            }
        });
    }
}