package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
            116);
    private static final int DEFAULT_FPS = VideoFeatureFlags.advancedInt("bili.video.turntable.default_fps", 60);
    private static final boolean PREFER_NATIVE = VideoFeatureFlags.advancedBoolean("bili.video.projector.native", true);
    private static final boolean LOG_SYNC_DECISIONS = VideoFeatureFlags.advancedBoolean(
            "bili.video.turntable.log_sync_decisions", false);
    private static final String DECODER_OVERRIDE = VideoFeatureFlags.advancedString("bili.video.ffmpeg.decoder", "")
            .trim();
    private static final int VIDEO_RESOLVE_THREADS = Math.max(1, Integer.getInteger(
            "bili.video.turntable.resolve_threads", 2));
    private static final AtomicInteger VIDEO_RESOLVE_THREAD_ID = new AtomicInteger();
    private static final ExecutorService VIDEO_RESOLVE_EXECUTOR = Executors.newFixedThreadPool(
            VIDEO_RESOLVE_THREADS, runnable -> {
                Thread thread = new Thread(runnable,
                        "BiliVideoResolve-" + VIDEO_RESOLVE_THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    private static final Set<String> ACTIVE_SESSION_IDS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<BlockPos, String> ACTIVE_SESSION_BY_TURNTABLE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, String> LATEST_SESSION_BY_TURNTABLE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> ACTIVE_QUALITY_CEILING_BY_SESSION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> ACTIVE_REQUEST_BY_SESSION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PendingVideoRequest> PENDING_REQUEST_BY_SESSION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> LAST_DECISION_BY_SESSION = new ConcurrentHashMap<>();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();

    private ModernTurntableVideoClient() {
    }

    public static void forgetSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        ACTIVE_SESSION_IDS.remove(sessionId);
        ACTIVE_QUALITY_CEILING_BY_SESSION.remove(sessionId);
        ACTIVE_REQUEST_BY_SESSION.remove(sessionId);
        PENDING_REQUEST_BY_SESSION.remove(sessionId);
        LAST_DECISION_BY_SESSION.remove(sessionId);
        ACTIVE_SESSION_BY_TURNTABLE.entrySet().removeIf(entry -> sessionId.equals(entry.getValue()));
        LATEST_SESSION_BY_TURNTABLE.entrySet().removeIf(entry -> sessionId.equals(entry.getValue()));
    }

    /** 客户端断连/切世界时清理所有视频同步决策状态，避免旧世界 session 与 BlockPos 残留。 */
    public static void clear() {
        ACTIVE_SESSION_IDS.clear();
        ACTIVE_SESSION_BY_TURNTABLE.clear();
        LATEST_SESSION_BY_TURNTABLE.clear();
        ACTIVE_QUALITY_CEILING_BY_SESSION.clear();
        ACTIVE_REQUEST_BY_SESSION.clear();
        PENDING_REQUEST_BY_SESSION.clear();
        LAST_DECISION_BY_SESSION.clear();
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

    public static void refreshProjector(BlockPos projectorPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (projectorPos == null || minecraft.level == null) {
            return;
        }
        Runnable refresh = () -> refreshProjectorOnClientThread(projectorPos.immutable());
        if (minecraft.isSameThread()) {
            refresh.run();
        } else {
            minecraft.execute(refresh);
        }
    }

    private static void refreshProjectorOnClientThread(BlockPos projectorPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        BlockEntity projectorBe = minecraft.level.getBlockEntity(projectorPos);
        if (!(projectorBe instanceof VideoProjectorBlockEntity projector)) {
            VideoBillboardPreview.stopIfProjector(projectorPos);
            return;
        }
        BlockPos turntablePos = projector.getLinkedTurntablePos();
        if (turntablePos == null) {
            VideoBillboardPreview.stopIfProjector(projectorPos);
            return;
        }
        BlockEntity turntableBe = minecraft.level.getBlockEntity(turntablePos);
        if (!(turntableBe instanceof ModernTurntableBlockEntity turntable) || !turntable.isPlaying()) {
            VideoBillboardPreview.stopIfProjector(projectorPos);
            return;
        }
        PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata(turntable.getLevel().getGameTime());
        if (!sync.hasSession()) {
            VideoBillboardPreview.stopIfProjector(projectorPos);
            return;
        }
        VideoBillboardPreview.stopIfSession(sync.sessionId());
        syncFromPlayback(turntable.getRawUrl(), turntablePos, sync);
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
        BlockPos immutableTurntablePos = turntablePos != null ? turntablePos.immutable() : null;
        String currentSessionId = currentSessionForTurntable(immutableTurntablePos);
        if (currentSessionId != null && !sessionId.equals(currentSessionId)) {
            logDecision(sessionId, "drop-stale-session", turntablePos, sync.elapsedMillis(), 0, 0, 0L,
                    "currentSession=" + currentSessionId);
            return;
        }
        if (immutableTurntablePos != null) {
            LATEST_SESSION_BY_TURNTABLE.put(immutableTurntablePos, sessionId);
        }
        List<VideoProjectorBlockEntity> projectors = findLinkedVideoProjectors(turntablePos);
        if (projectors.isEmpty()) {
            logDecision(sessionId, "stop-no-projector", turntablePos, sync.elapsedMillis(), 0, 0, 0L,
                    "no linked video projector");
            VideoBillboardPreview.stopIfSession(sessionId);
            forgetSession(sessionId);
            return;
        }
        List<BlockPos> projectorPositions = projectors.stream()
                .map(projector -> projector.getBlockPos().immutable())
                .toList();
        long elapsedMillis = Math.max(0L, sync.elapsedMillis());
        int qualityCeiling = qualityCeiling(projectors);
        String existingForTurntable = immutableTurntablePos != null
                ? ACTIVE_SESSION_BY_TURNTABLE.get(immutableTurntablePos)
                : null;
        if (existingForTurntable != null && VideoBillboardPreview.isSessionRunning(existingForTurntable)) {
            if (existingForTurntable.equals(sessionId)) {
                VideoBillboardPreview.updateSessionProjectors(existingForTurntable, projectorPositions);
                if (VideoBillboardPreview.isSessionWaitingForFirstFrame(existingForTurntable)) {
                    ACTIVE_SESSION_IDS.add(sessionId);
                    rememberActiveSession(immutableTurntablePos, sessionId);
                    logDecision(sessionId, "reuse-wait-first-frame", turntablePos, elapsedMillis, qualityCeiling,
                            projectorPositions.size(), 0L, "same session already decoding");
                    return;
                }
                if (isSessionRunningAtQualityCeiling(existingForTurntable, qualityCeiling)
                        && VideoBillboardPreview.canSessionChaseToOffset(existingForTurntable, elapsedMillis)) {
                    ACTIVE_SESSION_IDS.add(sessionId);
                    rememberActiveSession(immutableTurntablePos, sessionId);
                    logDecision(sessionId, "reuse-chase", turntablePos, elapsedMillis, qualityCeiling,
                            projectorPositions.size(), 0L,
                            "same session will chase target inside decoder buffer/window");
                    return;
                }
                logDecision(sessionId, "restart-params-changed", turntablePos, elapsedMillis, qualityCeiling,
                        projectorPositions.size(), 0L,
                        "same session target is outside chase window or quality ceiling changed");
                VideoBillboardPreview.stopIfSession(sessionId);
                markSessionRestarting(sessionId);
            } else {
                logDecision(sessionId, "switch-session", turntablePos, elapsedMillis, qualityCeiling,
                        projectorPositions.size(), 0L, "oldSession=" + existingForTurntable);
                VideoBillboardPreview.stopIfSession(existingForTurntable);
                forgetSession(existingForTurntable);
            }
        }
        if (VideoBillboardPreview.isSessionRunning(sessionId)) {
            VideoBillboardPreview.updateSessionProjectors(sessionId, projectorPositions);
            if (VideoBillboardPreview.isSessionWaitingForFirstFrame(sessionId)) {
                ACTIVE_SESSION_IDS.add(sessionId);
                rememberActiveSession(immutableTurntablePos, sessionId);
                logDecision(sessionId, "reuse-wait-first-frame", turntablePos, elapsedMillis, qualityCeiling,
                        projectorPositions.size(), 0L, "running session has not produced first frame yet");
                return;
            }
            if (isSessionRunningAtQualityCeiling(sessionId, qualityCeiling)
                    && VideoBillboardPreview.canSessionChaseToOffset(sessionId, elapsedMillis)) {
                ACTIVE_SESSION_IDS.add(sessionId);
                rememberActiveSession(immutableTurntablePos, sessionId);
                logDecision(sessionId, "reuse-chase", turntablePos, elapsedMillis, qualityCeiling,
                        projectorPositions.size(), 0L,
                        "running session will chase target inside decoder buffer/window");
                return;
            }
            logDecision(sessionId, "restart-running", turntablePos, elapsedMillis, qualityCeiling,
                    projectorPositions.size(), 0L,
                    "running session target is outside chase window or quality ceiling changed");
            VideoBillboardPreview.stopIfSession(sessionId);
            markSessionRestarting(sessionId);
        }
        if (!ACTIVE_SESSION_IDS.add(sessionId)) {
            if (isSessionRunningAtQualityCeiling(sessionId, qualityCeiling)
                    && VideoBillboardPreview.canSessionChaseToOffset(sessionId, elapsedMillis)) {
                rememberActiveSession(immutableTurntablePos, sessionId);
                logDecision(sessionId, "reuse-chase", turntablePos, elapsedMillis, qualityCeiling,
                        projectorPositions.size(), 0L, "active marker session can chase target");
                return;
            }
            if (!VideoBillboardPreview.isSessionRunning(sessionId)) {
                // 这里通常表示异步 B 站视频流解析已经在路上，但渲染实例还没创建。
                // 不要为了每个同步包都 remove/add 并重新提交 CompletableFuture，否则拖动/续播时会
                // 把同一个 HTTP/2 连接刷爆成 "too many concurrent streams"。
                PendingVideoRequest pending = PENDING_REQUEST_BY_SESSION.get(sessionId);
                if (pending != null && pending.matches(elapsedMillis, qualityCeiling)) {
                    rememberActiveSession(immutableTurntablePos, sessionId);
                    logDecision(sessionId, "reuse-pending", turntablePos, elapsedMillis, qualityCeiling,
                            projectorPositions.size(), pending.requestId(), "stream resolve already in flight");
                    return;
                }
                logDecision(sessionId, "replace-pending", turntablePos, elapsedMillis, qualityCeiling,
                        projectorPositions.size(), pending != null ? pending.requestId() : 0L,
                        pending != null ? "pending quality ceiling changed" : "active marker without renderer");
                markSessionRestarting(sessionId);
                ACTIVE_SESSION_IDS.add(sessionId);
                rememberActiveSession(immutableTurntablePos, sessionId);
            } else {
                logDecision(sessionId, "restart-active-marker", turntablePos, elapsedMillis, qualityCeiling,
                        projectorPositions.size(), 0L, "active marker conflicts with renderer state");
                VideoBillboardPreview.stopIfSession(sessionId);
                markSessionRestarting(sessionId);
                ACTIVE_SESSION_IDS.add(sessionId);
            }
        }
        if (isSessionRunningAtQualityCeiling(sessionId, qualityCeiling)
                && VideoBillboardPreview.canSessionChaseToOffset(sessionId, elapsedMillis)) {
            rememberActiveSession(immutableTurntablePos, sessionId);
            logDecision(sessionId, "reuse-chase", turntablePos, elapsedMillis, qualityCeiling,
                    projectorPositions.size(), 0L, "session can chase final sync target");
            return;
        }
        rememberActiveSession(immutableTurntablePos, sessionId);
        ACTIVE_QUALITY_CEILING_BY_SESSION.put(sessionId, qualityCeiling);
        long requestNanoTime = System.nanoTime();
        long requestId = REQUEST_SEQUENCE.incrementAndGet();
        ACTIVE_REQUEST_BY_SESSION.put(sessionId, requestId);
        PENDING_REQUEST_BY_SESSION.put(sessionId, new PendingVideoRequest(elapsedMillis, qualityCeiling, requestId));
        logDecision(sessionId, "schedule-resolve", turntablePos, elapsedMillis, qualityCeiling,
                projectorPositions.size(), requestId, "async B站 video stream resolve with quality ceiling");
        CompletableFuture.runAsync(() -> startResolved(selection, turntablePos, projectorPositions, qualityCeiling,
                sync, requestNanoTime, requestId), VIDEO_RESOLVE_EXECUTOR)
                .orTimeout(45, TimeUnit.SECONDS)
                .exceptionally(error -> {
                    if (isLatestRequest(sessionId, requestId)) {
                        forgetSession(sessionId);
                    }
                    LOGGER.warn("现代化唱片机视频同步启动失败: {}", cleanRawUrl, error);
                    return null;
                });
    }

    private static void markSessionRestarting(String sessionId) {
        ACTIVE_SESSION_IDS.remove(sessionId);
        ACTIVE_QUALITY_CEILING_BY_SESSION.remove(sessionId);
        ACTIVE_REQUEST_BY_SESSION.remove(sessionId);
        PENDING_REQUEST_BY_SESSION.remove(sessionId);
    }

    private static boolean isLatestRequest(String sessionId, long requestId) {
        Long active = ACTIVE_REQUEST_BY_SESSION.get(sessionId);
        return active != null && active == requestId;
    }

    private static boolean isLatestRequestForTurntable(String sessionId, long requestId, BlockPos turntablePos) {
        if (!isLatestRequest(sessionId, requestId)) {
            return false;
        }
        if (turntablePos == null) {
            return true;
        }
        String activeSession = ACTIVE_SESSION_BY_TURNTABLE.get(turntablePos);
        return sessionId.equals(activeSession);
    }

    private static void rememberActiveSession(BlockPos turntablePos, String sessionId) {
        if (turntablePos != null && sessionId != null && !sessionId.isBlank()) {
            ACTIVE_SESSION_BY_TURNTABLE.put(turntablePos, sessionId);
            LATEST_SESSION_BY_TURNTABLE.put(turntablePos, sessionId);
        }
    }

    private static String currentSessionForTurntable(BlockPos turntablePos) {
        if (turntablePos == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            BlockEntity blockEntity = minecraft.level.getBlockEntity(turntablePos);
            if (blockEntity instanceof ModernTurntableBlockEntity turntable && turntable.isPlaying()) {
                PlaybackSync.Metadata current = turntable.getPlaybackSyncMetadata(turntable.getLevel().getGameTime());
                if (current.hasSession()) {
                    return current.sessionId();
                }
            }
        }
        return LATEST_SESSION_BY_TURNTABLE.get(turntablePos);
    }

    private static int qualityCeiling(List<VideoProjectorBlockEntity> projectors) {
        return projectors.stream()
                .mapToInt(projector -> projector.getPreferredQuality() > 0
                        ? projector.getPreferredQuality()
                        : DEFAULT_PREFERRED_QUALITY)
                .max()
                .orElse(DEFAULT_PREFERRED_QUALITY);
    }

    private static boolean isSessionRunningAtQualityCeiling(String sessionId, int requestedQualityCeiling) {
        Integer activeQualityCeiling = ACTIVE_QUALITY_CEILING_BY_SESSION.get(sessionId);
        return activeQualityCeiling != null && activeQualityCeiling >= requestedQualityCeiling;
    }

    private static void startResolved(BiliApiClient.VideoSelection selection, BlockPos turntablePos,
            List<BlockPos> projectorPositions,
            int qualityCeiling, PlaybackSync.Metadata sync, long requestNanoTime, long requestId) {
        try {
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
            BiliApiClient.VideoStream stream = BiliApiClient.getBestVideoStream(selection.videoId(), info.cid(),
                    qualityCeiling);
            if (!isLatestRequestForTurntable(sync.sessionId(), requestId, turntablePos)) {
                return;
            }
            int sourceWidth = Math.max(1, stream.width());
            int sourceHeight = Math.max(1, stream.height());
            int fps = Math.max(1, parseFrameRate(stream.frameRate()));
            long elapsedMillis = normalizedElapsedMillis(sync);
            logDecision(sync.sessionId(), "resolved-start", turntablePos, elapsedMillis, qualityCeiling,
                    projectorPositions.size(), requestId,
                    "qualityCeiling=" + qualityCeiling + " actualQuality=" + stream.quality() + " title='"
                            + info.displayTitle() + "' size=" + sourceWidth + "x" + sourceHeight + " fps=" + fps);
            // 这里记录的是“本次请求已满足的偏好档位”，不是 B 站实际返回的 qn。
            // 例如投影仪请求 127，但当前视频最高只有 116；后续同步包仍会继续请求 127。
            // 如果把 active/pending 写成 116，就会把“允许降级到 116”误判成 quality ceiling
            // changed，导致播放器反复重建。
            ACTIVE_QUALITY_CEILING_BY_SESSION.put(sync.sessionId(), qualityCeiling);
            PENDING_REQUEST_BY_SESSION.put(sync.sessionId(),
                    new PendingVideoRequest(elapsedMillis, qualityCeiling, requestId));
            Minecraft.getInstance().execute(() -> {
                if (!isLatestRequestForTurntable(sync.sessionId(), requestId, turntablePos)) {
                    return;
                }
                VideoBillboardPreview.startSynced(stream.baseUrl(), sourceWidth,
                        sourceHeight, fps, stream.codecId(), sync.sessionId(), elapsedMillis, sync.totalMillis(),
                        projectorPositions,
                        turntablePos,
                        PREFER_NATIVE, DECODER_OVERRIDE.isBlank() ? null : DECODER_OVERRIDE);
                PENDING_REQUEST_BY_SESSION.remove(sync.sessionId(),
                        new PendingVideoRequest(elapsedMillis, qualityCeiling, requestId));
            });
        } catch (Exception e) {
            if (isLatestRequest(sync.sessionId(), requestId)) {
                forgetSession(sync.sessionId());
            }
            throw new IllegalStateException("resolve B站 video stream failed", e);
        }
    }

    private static long normalizedElapsedMillis(PlaybackSync.Metadata sync) {
        long base = Math.max(0L, sync.elapsedMillis());
        long total = Math.max(0L, sync.totalMillis());
        return normalizeMillis(base, total);
    }

    private static long normalizeMillis(long value, long totalMillis) {
        long normalized = Math.max(0L, value);
        long total = Math.max(0L, totalMillis);
        return total > 0L ? Math.min(total, normalized) : normalized;
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
            return DEFAULT_FPS;
        }
    }

    private static void logDecision(String sessionId, String action, BlockPos turntablePos, long elapsedMillis,
            int quality, int projectorCount, long requestId, String reason) {
        if (!LOG_SYNC_DECISIONS) {
            return;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String fingerprint = action + '|' + quality + '|' + projectorCount + '|' + requestId + '|' + reason;
        String previous = LAST_DECISION_BY_SESSION.put(sessionId, fingerprint);
        if (fingerprint.equals(previous)) {
            return;
        }
        LOGGER.info(
                "现代唱片机视频同步决策: action={} session={} request={} turntable={} elapsed={}ms qualityCeiling={} projectors={} reason={}",
                action, sessionId, requestId, turntablePos, Math.max(0L, elapsedMillis), quality, projectorCount,
                reason);
    }

    private record PendingVideoRequest(long elapsedMillis, int qualityCeiling, long requestId) {
        private boolean matches(long requestedElapsedMillis, int requestedQualityCeiling) {
            return qualityCeiling == requestedQualityCeiling;
        }
    }

}