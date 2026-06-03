package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 现代化唱片机的视频同步入口。
 *
 * <p>
 * 音频、歌词、音响/OpenAL relay 都由同一个 {@link PlaybackSync} session 驱动；
 * 这里只消费相同的 session/elapsed，把 B站 DASH 视频从同一时间线起播。
 * </p>
 */
public final class ModernTurntableVideoClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("bili.video.turntable.enabled", "true"));
    private static final int DEFAULT_PREFERRED_QUALITY = VideoFeatureFlags.advancedInt("bili.video.turntable.quality",
            127);
    private static final int DEFAULT_FPS = VideoFeatureFlags.advancedInt("bili.video.turntable.default_fps", 60);
    private static final boolean PREFER_NATIVE = VideoFeatureFlags.advancedBoolean("bili.video.projector.native", true);
    private static final String DECODER_OVERRIDE = VideoFeatureFlags.advancedString("bili.video.ffmpeg.decoder", "")
            .trim();

    private static final Set<String> ACTIVE_SESSION_IDS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<BlockPos, String> ACTIVE_SESSION_BY_TURNTABLE = new ConcurrentHashMap<>();

    private ModernTurntableVideoClient() {
    }

    public static void forgetSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        ACTIVE_SESSION_IDS.remove(sessionId);
        ACTIVE_SESSION_BY_TURNTABLE.entrySet().removeIf(entry -> sessionId.equals(entry.getValue()));
    }

    public static void syncFromTurntableIfPossible(ModernTurntableBlockEntity turntable) {
        if (turntable == null || turntable.getLevel() == null || !turntable.isPlaying()) {
            return;
        }
        String rawUrl = turntable.getRawUrl();
        if (rawUrl == null || rawUrl.isBlank()) {
            return;
        }
        PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata(turntable.getLevel().getGameTime());
        if (!sync.hasSession()) {
            return;
        }
        syncFromPlayback(rawUrl, turntable.getBlockPos(), sync);
    }

    public static void syncFromPlayback(String rawUrl, BlockPos turntablePos, PlaybackSync.Metadata sync) {
        if (!ENABLED || sync == null || !sync.hasSession()) {
            return;
        }
        String cleanRawUrl = PlaybackSync.strip(rawUrl);
        BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(cleanRawUrl);
        if (selection == null) {
            return;
        }
        String sessionId = sync.sessionId();
        List<VideoProjectorBlockEntity> projectors = findLinkedVideoProjectors(turntablePos);
        if (projectors.isEmpty()) {
            LOGGER.debug("现代化唱片机视频未启动：没有链接的视频投影仪 turntable={}", turntablePos);
            VideoBillboardPreview.stopIfSession(sessionId);
            forgetSession(sessionId);
            return;
        }
        List<BlockPos> projectorPositions = projectors.stream()
                .map(projector -> projector.getBlockPos().immutable())
                .toList();
        long elapsedMillis = Math.max(0L, sync.elapsedMillis());
        String existingForTurntable = ACTIVE_SESSION_BY_TURNTABLE.get(turntablePos);
        if (existingForTurntable != null && VideoBillboardPreview.isSessionRunning(existingForTurntable)) {
            if (existingForTurntable.equals(sessionId)) {
                VideoBillboardPreview.updateSessionProjectors(existingForTurntable, projectorPositions);
                if (VideoBillboardPreview.isSessionRunningAtOffset(existingForTurntable, elapsedMillis)) {
                    return;
                }
                LOGGER.info("现代化唱片机视频检测到拖动进度：session={} offset={}ms，重启视频同步",
                        sessionId, elapsedMillis);
                VideoBillboardPreview.stopIfSession(sessionId);
            } else {
                LOGGER.info("现代化唱片机视频切换会话：{} -> {}", existingForTurntable, sessionId);
                VideoBillboardPreview.stopIfSession(existingForTurntable);
                forgetSession(existingForTurntable);
            }
        }
        if (VideoBillboardPreview.isSessionRunning(sessionId)) {
            VideoBillboardPreview.updateSessionProjectors(sessionId, projectorPositions);
            if (VideoBillboardPreview.isSessionRunningAtOffset(sessionId, elapsedMillis)) {
                ACTIVE_SESSION_IDS.add(sessionId);
                ACTIVE_SESSION_BY_TURNTABLE.put(turntablePos.immutable(), sessionId);
                return;
            }
            LOGGER.info("现代化唱片机视频检测到同 session seek：session={} offset={}ms，重启视频同步",
                    sessionId, elapsedMillis);
            VideoBillboardPreview.stopIfSession(sessionId);
        }
        if (!ACTIVE_SESSION_IDS.add(sessionId)) {
            if (VideoBillboardPreview.isSessionRunningAtOffset(sessionId, elapsedMillis)) {
                ACTIVE_SESSION_BY_TURNTABLE.put(turntablePos.immutable(), sessionId);
                return;
            }
            if (!VideoBillboardPreview.isSessionRunning(sessionId)) {
                return;
            }
            LOGGER.info("现代化唱片机视频检测到待重启 session 仍在旧 offset：session={} offset={}ms，重启视频同步",
                    sessionId, elapsedMillis);
            VideoBillboardPreview.stopIfSession(sessionId);
            ACTIVE_SESSION_IDS.add(sessionId);
        }
        if (VideoBillboardPreview.isSessionRunningAtOffset(sessionId, elapsedMillis)) {
            ACTIVE_SESSION_BY_TURNTABLE.put(turntablePos.immutable(), sessionId);
            return;
        }
        ACTIVE_SESSION_BY_TURNTABLE.put(turntablePos.immutable(), sessionId);
        int preferredQuality = projectors.stream()
                .mapToInt(projector -> projector.getPreferredQuality() > 0
                        ? projector.getPreferredQuality()
                        : DEFAULT_PREFERRED_QUALITY)
                .max()
                .orElse(DEFAULT_PREFERRED_QUALITY);
        long requestNanoTime = System.nanoTime();
        CompletableFuture.runAsync(() -> startResolved(selection, turntablePos, projectorPositions, preferredQuality,
                sync, requestNanoTime))
                .orTimeout(45, TimeUnit.SECONDS)
                .exceptionally(error -> {
                    forgetSession(sessionId);
                    LOGGER.warn("现代化唱片机视频同步启动失败: {}", cleanRawUrl, error);
                    return null;
                });
    }

    private static void startResolved(BiliApiClient.VideoSelection selection, BlockPos turntablePos,
            List<BlockPos> projectorPositions,
            int preferredQuality, PlaybackSync.Metadata sync, long requestNanoTime) {
        try {
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
            BiliApiClient.VideoStream stream = BiliApiClient.getBestVideoStream(selection.videoId(), info.cid(),
                    preferredQuality);
            int sourceWidth = Math.max(1, stream.width());
            int sourceHeight = Math.max(1, stream.height());
            int fps = Math.max(1, parseFrameRate(stream.frameRate()));
            long elapsedMillis = adjustedElapsedMillis(sync, requestNanoTime);
            LOGGER.info(
                    "视频投影仪同步: '{}' tryQ{} -> actualQ{} {} -> {}x{} @ {}fps, elapsed={}ms, projectors={}, session={}",
                    info.displayTitle(), preferredQuality, stream.quality(), stream.displaySize(), sourceWidth,
                    sourceHeight, fps, elapsedMillis, projectorPositions, sync.sessionId());
            Minecraft.getInstance().execute(() -> VideoBillboardPreview.startSynced(stream.baseUrl(), sourceWidth,
                    sourceHeight, fps, stream.codecId(), sync.sessionId(), elapsedMillis, sync.totalMillis(),
                    projectorPositions,
                    turntablePos,
                    PREFER_NATIVE, DECODER_OVERRIDE.isBlank() ? null : DECODER_OVERRIDE));
        } catch (Exception e) {
            forgetSession(sync.sessionId());
            throw new IllegalStateException("resolve B站 video stream failed", e);
        }
    }

    private static long adjustedElapsedMillis(PlaybackSync.Metadata sync, long requestNanoTime) {
        long base = Math.max(0L, sync.elapsedMillis());
        long startupDelayMillis = Math.max(0L, (System.nanoTime() - requestNanoTime) / 1_000_000L);
        long total = Math.max(0L, sync.totalMillis());
        long adjusted = base + startupDelayMillis;
        return total > 0L ? Math.min(total, adjusted) : adjusted;
    }

    private static List<VideoProjectorBlockEntity> findLinkedVideoProjectors(BlockPos turntablePos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (turntablePos == null || minecraft.level == null) {
            return List.of();
        }
        List<VideoProjectorBlockEntity> projectors = new ArrayList<>();
        for (BlockPos sourcePos : ClientLinkRegistry.getSources(turntablePos)) {
            BlockEntity be = minecraft.level.getBlockEntity(sourcePos);
            if (be instanceof VideoProjectorBlockEntity projector
                    && turntablePos.equals(projector.getLinkedTurntablePos())) {
                projectors.add(projector);
            }
        }
        return projectors;
    }

    private static int parseFrameRate(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_FPS;
        }
        String normalized = raw.trim();
        try {
            if (normalized.contains("/")) {
                String[] parts = normalized.split("/", 2);
                double numerator = Double.parseDouble(parts[0].trim());
                double denominator = Double.parseDouble(parts[1].trim());
                return denominator > 0.0D ? Math.max(1, (int) Math.round(numerator / denominator)) : DEFAULT_FPS;
            }
            return Math.max(1, (int) Math.round(Double.parseDouble(normalized)));
        } catch (NumberFormatException e) {
            LOGGER.debug("无法解析视频帧率 '{}', 使用 {}fps", normalized.toLowerCase(Locale.ROOT), DEFAULT_FPS);
            return DEFAULT_FPS;
        }
    }
}