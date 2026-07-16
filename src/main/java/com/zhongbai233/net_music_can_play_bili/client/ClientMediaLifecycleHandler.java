package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(value = Dist.CLIENT)
public final class ClientMediaLifecycleHandler {
    private static final long TICK_FALLBACK_AFTER_NANOS = 250_000_000L;

    private static volatile long lastFrameUpdateNanos;
    private static volatile Level lastTrackedLevel;

    private ClientMediaLifecycleHandler() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cleanupClientPlayback();
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer
                .renderHeldOffscreenGuiFrameStart();
        com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer
                .renderHeldOffscreenGuiFrameStart();
        if (!ClientAudioOutputRegistry.isActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (updateFromCamera(mc)) {
            lastFrameUpdateNanos = System.nanoTime();
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryDiagnostics.tick();
        com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection
            .tick(ClientMediaLifecycleHandler::emergencyCleanupClientPlayback);
        // 切世界/单人存档重进时清掉旧 tracker 记录
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastTrackedLevel) {
            lastTrackedLevel = mc.level;
            if (lastTrackedLevel == null) {
                cleanupClientPlayback();
            }
        }

        com.zhongbai233.net_music_can_play_bili.client.MP4AutoResumeClient.tick();
        com.zhongbai233.net_music_can_play_bili.client.MP4Client.tickHeldDeviceIdPrefetch();
        com.zhongbai233.net_music_can_play_bili.client.PadClient.tickHeldDeviceSync();
        com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient.tickHotbarVideoFrames();
        com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient.stopDevicesOutsideHotbar();
        com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache.tick();
        com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer.tickHeldMapLayers();

        if (!ClientAudioOutputRegistry.isActive()) {
            return;
        }

        long now = System.nanoTime();
        long lastFrame = lastFrameUpdateNanos;
        if (lastFrame != 0L && now - lastFrame < TICK_FALLBACK_AFTER_NANOS) {
            return;
        }

        if (updateFromCamera(mc)) {
            return;
        }
        if (mc.player == null) {
            return;
        }

        updateListener(mc.player.getEyePosition());
    }

    private static boolean updateFromCamera(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) {
            return false;
        }
        updateListener(camera.position());
        return true;
    }

    private static void updateListener(Vec3 eye) {
        float[] listenerPos = new float[] {
                (float) eye.x,
                (float) eye.y,
                (float) eye.z
        };

        ClientAudioOutputRegistry.updatePositions(listenerPos);
    }

    private static void cleanupClientPlayback() {
        cleanupClientPlayback(false);
    }

    private static void emergencyCleanupClientPlayback() {
        cleanupClientPlayback(true);
    }

    private static void cleanupClientPlayback(boolean emergency) {
        if (!emergency) {
            com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryDiagnostics
                    .report("before-cleanup");
        }
        com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview.stop();
        com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient.clear();
        com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntableTimeline.clear();
        com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry.clear();
        com.zhongbai233.net_music_can_play_bili.client.audio.SyncedStreamRecoveryRegistry.clear();
        com.zhongbai233.net_music_can_play_bili.media.stream.CdnHealthTracker.clear();
        com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker.stopAllSounds();
        com.zhongbai233.net_music_can_play_bili.client.MP4Client.clearCachedStates();
        com.zhongbai233.net_music_can_play_bili.client.PadClient.clearCachedDocuments();
        com.zhongbai233.net_music_can_play_bili.client.MP4ClientMediaSessions.clearAll();
        com.zhongbai233.net_music_can_play_bili.client.MP4FocusState.resetAll();
        com.zhongbai233.net_music_can_play_bili.client.PadFocusState.resetAll();
        com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer.releaseAll();
        com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer.releaseAll();
        com.zhongbai233.net_music_can_play_bili.client.MP4AutoResumeClient.reset();
        HttpAudioStreamHandler.closeModernStreams();
        ClientAudioOutputRegistry.cleanup();
        if (!emergency) {
            com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryDiagnostics
                    .report("after-cleanup");
        }
        lastTrackedLevel = null;
    }

    public static void tripMemoryProtection(String reason) {
        com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection
            .tripOnAllocationFailure(reason, ClientMediaLifecycleHandler::emergencyCleanupClientPlayback);
    }
}
