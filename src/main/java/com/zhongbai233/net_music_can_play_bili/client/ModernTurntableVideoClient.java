package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.ResolvedVideoStream;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoResolveAdmissionPolicy;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
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
            System.getProperty("ncpb.video.turntable.enabled", "true"));
    private static final int DEFAULT_PREFERRED_QUALITY = VideoFeatureFlags.advancedInt("bili.video.turntable.quality",
            116);
    private static final int DEFAULT_FPS = VideoFeatureFlags.advancedInt("bili.video.turntable.default_fps", 60);
    private static final boolean PREFER_NATIVE = VideoFeatureFlags.advancedBoolean("bili.video.projector.native", true);
    private static final boolean LOG_SYNC_DECISIONS = VideoFeatureFlags.advancedBoolean(
            "bili.video.turntable.log_sync_decisions", false);
    private static final String DECODER_OVERRIDE = VideoFeatureFlags.advancedString("ncpb.video.ffmpeg.decoder", "")
            .trim();
    private static final int VIDEO_RESOLVE_THREADS = Math.max(1, Integer.getInteger(
            "bili.video.turntable.resolve_threads", 2));
    private static final ExecutorService VIDEO_RESOLVE_EXECUTOR = Executors.newFixedThreadPool(
            VIDEO_RESOLVE_THREADS, NetMusicThreadFactory.daemon("BiliVideoResolve"));

    private static final Set<String> ACTIVE_SESSION_IDS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<BlockPos, String> ACTIVE_SESSION_BY_TURNTABLE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, String> LATEST_SESSION_BY_TURNTABLE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Set<BlockPos>> CONTROL_CONSOLE_CONSUMERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Integer> CONTROL_CONSOLE_QUALITY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> ACTIVE_QUALITY_CEILING_BY_SESSION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> ACTIVE_REQUEST_BY_SESSION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PendingVideoRequest> PENDING_REQUEST_BY_SESSION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> LAST_DECISION_BY_SESSION = new ConcurrentHashMap<>();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final AtomicLong STALE_RESOLVE_DROPS = new AtomicLong();
    private static final AtomicLong NO_CONSUMER_RESOLVE_DROPS = new AtomicLong();

    private ModernTurntableVideoClient() {
    }

    public static void forgetSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        VideoBillboardPreview.clearPendingLoading(sessionId);
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
        CONTROL_CONSOLE_CONSUMERS.clear();
        CONTROL_CONSOLE_QUALITY.clear();
        STALE_RESOLVE_DROPS.set(0L);
        NO_CONSUMER_RESOLVE_DROPS.set(0L);
    }

    /** 注册中控台为虚拟投影面；视频解码器仍由绑定源共享。 */
    public static void registerControlConsoleConsumer(BlockPos turntablePos, BlockPos consolePos, int qualityCeiling) {
        if (turntablePos != null && consolePos != null) {
            CONTROL_CONSOLE_CONSUMERS.computeIfAbsent(turntablePos.immutable(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(consolePos.immutable());
            CONTROL_CONSOLE_QUALITY.put(consolePos.immutable(), qualityCeiling);
        }
    }

    public static void unregisterControlConsoleConsumer(BlockPos consolePos) {
        if (consolePos != null) {
            CONTROL_CONSOLE_QUALITY.remove(consolePos);
            List<BlockPos> affectedTurntables = new ArrayList<>();
            CONTROL_CONSOLE_CONSUMERS.forEach((turntablePos, consumers) -> {
                if (consumers.remove(consolePos)) {
                    affectedTurntables.add(turntablePos);
                }
            });
            CONTROL_CONSOLE_CONSUMERS.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            affectedTurntables.forEach(ModernTurntableVideoClient::invalidateIfNoLiveConsumer);
        }
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

    public static void syncFromTurntableForProjectorIfPossible(ModernTurntableBlockEntity turntable,
            VideoProjectorBlockEntity projector) {
        if (turntable == null || projector == null || turntable.getLevel() == null || !turntable.isPlaying()) {
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
        syncFromPlayback(rawUrl, turntable.getBlockPos(), sync, List.of(projector));
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
        if (turntableBe instanceof com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity) {
            // 直播机：停掉当前会话，让 LiveStreamerVideoClient 在下个周期按新参数重建。
            VideoBillboardPreview.stopIfProjector(projectorPos);
            return;
        }
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
        syncFromPlayback(rawUrl, turntablePos, sync, null);
    }

    private static void syncFromPlayback(String rawUrl, BlockPos turntablePos, PlaybackSync.Metadata sync,
            List<VideoProjectorBlockEntity> explicitProjectors) {
        if (!ENABLED || sync == null || !sync.hasSession()) {
            return;
        }
        String cleanRawUrl = PlaybackSync.strip(rawUrl);
        BiliApiClient.VideoSelection selection = BiliVideoStreamResolver.selectionOrNull(cleanRawUrl);
        if (selection == null) {
            return;
        }
        String sessionId = sync.sessionId();
        BlockPos immutableTurntablePos = turntablePos != null ? turntablePos.immutable() : null;
        if (immutableTurntablePos != null) {
            LATEST_SESSION_BY_TURNTABLE.put(immutableTurntablePos, sessionId);
        }
        List<VideoProjectorBlockEntity> projectors = explicitProjectors != null
                ? explicitProjectors
                : findLinkedVideoProjectors(turntablePos);
        List<BlockPos> consoleConsumers = CONTROL_CONSOLE_CONSUMERS.getOrDefault(
            turntablePos != null ? turntablePos : BlockPos.ZERO, Set.of()).stream().toList();
        boolean holographicConsumer = HolographicGlassesClient.handlesTurntable(turntablePos);
        if (projectors.isEmpty() && consoleConsumers.isEmpty() && !holographicConsumer) {
            // MinecartRevolution 的投影仪 BE 位于模拟 level，无法通过 Minecraft.level 扫描到。
            // BER 已经建立并维持同一唱片机会话时，周期全量同步不应把它误判成无 consumer。
            if (VideoBillboardPreview.hasSessionForTurntable(turntablePos, sessionId)
                    && (VideoBillboardPreview.isSessionRunning(sessionId)
                        || VideoBillboardPreview.hasTerminalFailure(sessionId))) {
                ACTIVE_SESSION_IDS.add(sessionId);
                rememberActiveSession(immutableTurntablePos, sessionId);
                logDecision(sessionId, "reuse-simulated-projector", turntablePos, sync.elapsedMillis(), 0, 0, 0L,
                        VideoBillboardPreview.hasTerminalFailure(sessionId)
                            ? "failed session is retained on a BER-backed simulated projector"
                                : "running session is retained for a BER-backed simulated projector");
                return;
            }
            logDecision(sessionId, "stop-no-projector", turntablePos, sync.elapsedMillis(), 0, 0, 0L,
                    "no linked video projector");
            VideoBillboardPreview.stopIfSession(sessionId);
            forgetSession(sessionId);
            return;
        }
        List<BlockPos> projectorPositions = projectors.stream()
                .map(projector -> projector.getBlockPos().immutable())
            .toList();
        List<BlockPos> consumerPositions = new ArrayList<>(projectorPositions);
        consumerPositions.addAll(consoleConsumers);
        if (!isAudioReady(turntablePos, sessionId)) {
            VideoBillboardPreview.stopIfSession(sessionId);
            forgetSession(sessionId);
            VideoBillboardPreview.beginPendingLoading(sessionId, consumerPositions);
            logDecision(sessionId, "wait-audio-ready", turntablePos, sync.elapsedMillis(), 0, 0, 0L,
                    "video waits until matching audio stream is ready");
            return;
        }
        long elapsedMillis = Math.max(0L, sync.elapsedMillis());
        int qualityCeiling = qualityCeiling(projectors, consoleConsumers);
        if (VideoBillboardPreview.hasTerminalFailure(sessionId)) {
            VideoBillboardPreview.updateSessionProjectors(sessionId, consumerPositions);
            ACTIVE_SESSION_IDS.add(sessionId);
            rememberActiveSession(immutableTurntablePos, sessionId);
            logDecision(sessionId, "hold-network-failure", turntablePos, elapsedMillis, qualityCeiling,
                    projectorPositions.size(), 0L,
                    "same session is held at the error placeholder until a new session or explicit retry");
            return;
        }
        String existingForTurntable = immutableTurntablePos != null
                ? ACTIVE_SESSION_BY_TURNTABLE.get(immutableTurntablePos)
                : null;
        if (existingForTurntable != null && VideoBillboardPreview.isSessionRunning(existingForTurntable)) {
            if (existingForTurntable.equals(sessionId)) {
                VideoBillboardPreview.updateSessionProjectors(existingForTurntable, consumerPositions);
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
            VideoBillboardPreview.updateSessionProjectors(sessionId, consumerPositions);
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
                    VideoBillboardPreview.beginPendingLoading(sessionId, consumerPositions);
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
        PENDING_REQUEST_BY_SESSION.put(sessionId, new PendingVideoRequest(elapsedMillis, qualityCeiling, requestId,
            List.copyOf(consumerPositions)));
        VideoBillboardPreview.beginPendingLoading(sessionId, consumerPositions);
        logDecision(sessionId, "schedule-resolve", turntablePos, elapsedMillis, qualityCeiling,
                projectorPositions.size(), requestId, "async B站 video stream resolve with quality ceiling");
        CompletableFuture
                .runAsync(() -> startResolved(cleanRawUrl, selection, turntablePos, consumerPositions, qualityCeiling,
                        sync, requestNanoTime, requestId), VIDEO_RESOLVE_EXECUTOR)
                .orTimeout(45, TimeUnit.SECONDS)
                .exceptionally(error -> {
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

    private static void invalidateIfNoLiveConsumer(BlockPos turntablePos) {
        if (turntablePos == null) {
            return;
        }
        String sessionId = ACTIVE_SESSION_BY_TURNTABLE.get(turntablePos);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = LATEST_SESSION_BY_TURNTABLE.get(turntablePos);
        }
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        PendingVideoRequest pending = PENDING_REQUEST_BY_SESSION.get(sessionId);
        List<BlockPos> captured = pending != null ? pending.consumerPositions() : List.of();
        LiveConsumers consumers = currentConsumers(turntablePos, sessionId, captured);
        if (consumers.hasAny()) {
            return;
        }
        markSessionRestarting(sessionId);
        VideoBillboardPreview.clearPendingLoading(sessionId);
        logDecision(sessionId, "invalidate-last-consumer", turntablePos, 0L, 0, 0, 0L,
                "last live video consumer detached");
    }

    private static LiveConsumers currentConsumers(BlockPos turntablePos, String sessionId,
            List<BlockPos> capturedPositions) {
        java.util.LinkedHashSet<BlockPos> positions = new java.util.LinkedHashSet<>();
        findLinkedVideoProjectors(turntablePos).stream()
                .map(projector -> projector.getBlockPos().immutable())
                .forEach(positions::add);
        positions.addAll(CONTROL_CONSOLE_CONSUMERS.getOrDefault(turntablePos, Set.of()));
        if (capturedPositions != null) {
            capturedPositions.stream().filter(VideoBillboardPreview::isProjectorRenderedByBer)
                    .forEach(pos -> positions.add(pos.immutable()));
        }
        boolean holographic = HolographicGlassesClient.handlesTurntable(turntablePos);
        boolean gui = VideoBillboardPreview.hasGuiConsumer(sessionId);
        return new LiveConsumers(List.copyOf(positions), holographic, gui);
    }

    private static boolean isAudioReady(BlockPos turntablePos, String sessionId) {
        if (turntablePos == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getAudioTimeline(turntablePos);
        String audioSessionId = timeline.audioSessionId();
        if (audioSessionId != null && !audioSessionId.isBlank() && !audioSessionId.equals(sessionId)) {
            return false;
        }
        return timeline.audibleMillis() >= 0L || timeline.fedMillis() >= 0L;
    }

    private static void rememberActiveSession(BlockPos turntablePos, String sessionId) {
        if (turntablePos != null && sessionId != null && !sessionId.isBlank()) {
            ACTIVE_SESSION_BY_TURNTABLE.put(turntablePos, sessionId);
            LATEST_SESSION_BY_TURNTABLE.put(turntablePos, sessionId);
        }
    }

        private static int qualityCeiling(List<VideoProjectorBlockEntity> projectors,
            List<BlockPos> consoleConsumers) {
        int projectorQuality = projectors.stream()
                .mapToInt(projector -> projector.getPreferredQuality() > 0
                        ? projector.getPreferredQuality()
                        : DEFAULT_PREFERRED_QUALITY)
                .max()
                .orElse(0);
        int consoleQuality = consoleConsumers.stream()
            .mapToInt(pos -> CONTROL_CONSOLE_QUALITY.getOrDefault(pos, DEFAULT_PREFERRED_QUALITY))
            .max()
                .orElse(0);
            int selected = Math.max(projectorQuality, consoleQuality);
            return selected > 0 ? selected : DEFAULT_PREFERRED_QUALITY;
    }

    private static boolean isSessionRunningAtQualityCeiling(String sessionId, int requestedQualityCeiling) {
        Integer activeQualityCeiling = ACTIVE_QUALITY_CEILING_BY_SESSION.get(sessionId);
        return activeQualityCeiling != null && activeQualityCeiling == requestedQualityCeiling;
    }

    private static void startResolved(String cleanRawUrl, BiliApiClient.VideoSelection selection, BlockPos turntablePos,
            List<BlockPos> projectorPositions,
            int qualityCeiling, PlaybackSync.Metadata sync, long requestNanoTime, long requestId) {
        try {
            ResolvedVideoStream stream = BiliVideoStreamResolver.resolve(cleanRawUrl, qualityCeiling, DEFAULT_FPS);
            int sourceWidth = stream.sourceWidth();
            int sourceHeight = stream.sourceHeight();
            int fps = stream.fps();
            Minecraft.getInstance().execute(() -> {
                LiveConsumers consumers = currentConsumers(turntablePos, sync.sessionId(), projectorPositions);
                VideoResolveAdmissionPolicy.Decision decision = resolveAdmission(sync.sessionId(), requestId,
                    turntablePos, consumers);
                if (decision != VideoResolveAdmissionPolicy.Decision.START) {
                    dropResolvedResult(sync.sessionId(), requestId, turntablePos, decision);
                    return;
                }
                // 这里记录的是“本次请求已满足的偏好档位”，不是 B 站实际返回的 qn。
                // 只有实时准入通过后才能发布，避免最后 consumer 退出后由后台线程复活状态。
                ACTIVE_QUALITY_CEILING_BY_SESSION.put(sync.sessionId(), qualityCeiling);
                PlaybackSync.Metadata launchSync = currentPlaybackMetadata(turntablePos, sync);
                long elapsedMillis = normalizedElapsedMillis(launchSync);
                logDecision(sync.sessionId(), "resolved-start", turntablePos, elapsedMillis, qualityCeiling,
                        consumers.positions().size(), requestId,
                        "qualityCeiling=" + qualityCeiling + " actualQuality=" + stream.quality() + " title='"
                                + stream.title() + "' size=" + sourceWidth + "x" + sourceHeight + " fps=" + fps
                                + " launchTimelineRefreshed=" + (launchSync != sync));
                VideoBillboardPreview.startSyncedCandidates(stream.candidates(), sourceWidth,
                        sourceHeight, fps, launchSync.sessionId(), elapsedMillis,
                        launchSync.totalMillis(),
                        consumers.positions(),
                        turntablePos,
                        PREFER_NATIVE, DECODER_OVERRIDE.isBlank() ? null : DECODER_OVERRIDE);
                PENDING_REQUEST_BY_SESSION.remove(sync.sessionId());
                    ACTIVE_REQUEST_BY_SESSION.remove(sync.sessionId(), requestId);
            });
        } catch (Exception e) {
            Minecraft.getInstance().execute(() -> {
                LiveConsumers consumers = currentConsumers(turntablePos, sync.sessionId(), projectorPositions);
                VideoResolveAdmissionPolicy.Decision decision = resolveAdmission(sync.sessionId(), requestId,
                        turntablePos, consumers);
                if (decision == VideoResolveAdmissionPolicy.Decision.START) {
                    PENDING_REQUEST_BY_SESSION.remove(sync.sessionId());
                    ACTIVE_REQUEST_BY_SESSION.remove(sync.sessionId(), requestId);
                    VideoBillboardPreview.markPendingFailure(sync.sessionId(), consumers.positions());
                } else {
                    dropResolvedResult(sync.sessionId(), requestId, turntablePos, decision);
                }
            });
            throw new IllegalStateException("resolve B站 video stream failed", e);
        }
    }

    private static VideoResolveAdmissionPolicy.Decision resolveAdmission(String sessionId, long requestId,
            BlockPos turntablePos, LiveConsumers consumers) {
        boolean latest = isLatestRequestForTurntable(sessionId, requestId, turntablePos);
        Minecraft minecraft = Minecraft.getInstance();
        BlockEntity blockEntity = minecraft.level != null && turntablePos != null
                ? minecraft.level.getBlockEntity(turntablePos) : null;
        if (!(blockEntity instanceof ModernTurntableBlockEntity turntable) || !turntable.isPlaying()) {
            return VideoResolveAdmissionPolicy.decide(latest, false, false, consumers.hasAny());
        }
        PlaybackSync.Metadata current = turntable.getPlaybackSyncMetadata(minecraft.level.getGameTime());
        boolean sameSession = current.hasSession() && sessionId.equals(current.sessionId());
        return VideoResolveAdmissionPolicy.decide(latest, sameSession, true, consumers.hasAny());
    }

    private static void dropResolvedResult(String sessionId, long requestId, BlockPos turntablePos,
            VideoResolveAdmissionPolicy.Decision decision) {
        if (decision == VideoResolveAdmissionPolicy.Decision.DROP_NO_CONSUMER) {
            NO_CONSUMER_RESOLVE_DROPS.incrementAndGet();
        } else {
            STALE_RESOLVE_DROPS.incrementAndGet();
        }
        if (isLatestRequest(sessionId, requestId)) {
            markSessionRestarting(sessionId);
            VideoBillboardPreview.clearPendingLoading(sessionId);
        }
        logDecision(sessionId, "drop-resolve-" + decision.name().toLowerCase(java.util.Locale.ROOT), turntablePos,
                0L, 0, 0, requestId, "resolved result failed live admission");
    }

    public static VideoLifecycleDiagnostics videoLifecycleDiagnostics() {
        int consoleConsumers = CONTROL_CONSOLE_CONSUMERS.values().stream()
            .mapToInt(consumers -> consumers.size()).sum();
        return new VideoLifecycleDiagnostics(ACTIVE_SESSION_IDS.size(), ACTIVE_REQUEST_BY_SESSION.size(),
                PENDING_REQUEST_BY_SESSION.size(), consoleConsumers, STALE_RESOLVE_DROPS.get(),
                NO_CONSUMER_RESOLVE_DROPS.get(), VideoBillboardPreview.resourceDiagnostics());
    }

            public static List<String> describeVideoLifecycle() {
            VideoLifecycleDiagnostics diagnostics = videoLifecycleDiagnostics();
            VideoBillboardPreview.ResourceDiagnostics resources = diagnostics.resources();
            return List.of(
                "video sessions=" + diagnostics.activeSessions()
                    + " requests=" + diagnostics.activeRequests()
                    + " pendingResolve=" + diagnostics.pendingRequests()
                    + " consoleConsumers=" + diagnostics.controlConsoleConsumers(),
                "video instances=" + resources.instances()
                    + " running=" + resources.runningInstances()
                    + " failed=" + resources.failedInstances()
                    + " pendingLoading=" + resources.pendingLoading()
                    + " pendingFailure=" + resources.pendingFailure(),
                "video refs projector=" + resources.projectorReferences()
                    + " ber=" + resources.berManagedProjectors()
                    + " gui=" + resources.guiConsumers(),
                "video resolveDrops stale=" + diagnostics.staleResolveDrops()
                    + " noConsumer=" + diagnostics.noConsumerResolveDrops(),
                com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics
                    .describeGlobal(),
                com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics.describeGlobal(
                    com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio
                        .pendingNativeDeleteBatches()));
            }

    public record VideoLifecycleDiagnostics(int activeSessions, int activeRequests, int pendingRequests,
            int controlConsoleConsumers, long staleResolveDrops, long noConsumerResolveDrops,
            VideoBillboardPreview.ResourceDiagnostics resources) {
    }

    private static long normalizedElapsedMillis(PlaybackSync.Metadata sync) {
        long base = Math.max(0L, sync.elapsedMillis());
        long total = Math.max(0L, sync.totalMillis());
        return normalizeMillis(base, total);
    }

    private static PlaybackSync.Metadata currentPlaybackMetadata(BlockPos turntablePos,
            PlaybackSync.Metadata fallback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (turntablePos == null || minecraft.level == null) {
            return fallback;
        }
        BlockEntity blockEntity = minecraft.level.getBlockEntity(turntablePos);
        if (!(blockEntity instanceof ModernTurntableBlockEntity turntable) || !turntable.isPlaying()) {
            return fallback;
        }
        PlaybackSync.Metadata current = turntable.getPlaybackSyncMetadata(minecraft.level.getGameTime());
        return current.hasSession() && fallback.sessionId().equals(current.sessionId()) ? current : fallback;
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
        LOGGER.debug(
                "现代唱片机视频同步决策: action={} session={} request={} turntable={} elapsed={}ms qualityCeiling={} projectors={} reason={}",
                action, sessionId, requestId, turntablePos, Math.max(0L, elapsedMillis), quality, projectorCount,
                reason);
    }

    private record PendingVideoRequest(long elapsedMillis, int qualityCeiling, long requestId,
            List<BlockPos> consumerPositions) {
        private boolean matches(long requestedElapsedMillis, int requestedQualityCeiling) {
            return qualityCeiling == requestedQualityCeiling;
        }
    }

    private record LiveConsumers(List<BlockPos> positions, boolean holographic, boolean gui) {
        private boolean hasAny() {
            return !positions.isEmpty() || holographic || gui;
        }
    }

}