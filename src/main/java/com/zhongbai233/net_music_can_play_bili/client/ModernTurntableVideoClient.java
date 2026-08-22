package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.ResolvedVideoStream;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import com.zhongbai233.net_music_can_play_bili.client.sync.VideoAudioReadinessPolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.VideoAudioPresenceRegistry;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoResolveAdmissionPolicy;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final VideoClientProperties.Turntable VIDEO_PROPERTIES = VideoClientProperties.turntable();
    private static final int DEFAULT_PREFERRED_QUALITY = VideoFeatureFlags.advancedInt("bili.video.turntable.quality",
            116);
    private static final int DEFAULT_FPS = VideoFeatureFlags.advancedInt("bili.video.turntable.default_fps", 60);
    private static final boolean PREFER_NATIVE = VideoFeatureFlags.advancedBoolean("bili.video.projector.native", true);
    private static final boolean LOG_SYNC_DECISIONS = VideoFeatureFlags.advancedBoolean(
            "bili.video.turntable.log_sync_decisions", false);
    private static final String DECODER_OVERRIDE = VideoFeatureFlags.advancedString("ncpb.video.ffmpeg.decoder", "")
            .trim();
    private static final ExecutorService VIDEO_RESOLVE_EXECUTOR = Executors.newFixedThreadPool(
            VIDEO_PROPERTIES.resolveThreads(), NetMusicThreadFactory.daemon("BiliVideoResolve"));

    private static final Set<PlaybackSessionId> ACTIVE_SESSION_IDS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<BlockPos, PlaybackSessionId> ACTIVE_SESSION_BY_TURNTABLE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, PlaybackSessionId> LATEST_SESSION_BY_TURNTABLE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Set<BlockPos>> CONTROL_CONSOLE_CONSUMERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Integer> CONTROL_CONSOLE_QUALITY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PlaybackSessionId, Integer> ACTIVE_QUALITY_CEILING_BY_SESSION =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PlaybackSessionId, ResolveGeneration> ACTIVE_REQUEST_BY_SESSION =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PlaybackSessionId, VideoResolveRequestOwner<BlockPos>>
            PENDING_REQUEST_BY_SESSION = new ConcurrentHashMap<>();
    private static final VideoAudioPresenceRegistry AUDIO_PRESENCE = new VideoAudioPresenceRegistry();
    private static final ConcurrentHashMap<PlaybackSessionId, String> LAST_DECISION_BY_SESSION =
            new ConcurrentHashMap<>();
    private static final AtomicReference<ResolveGeneration> REQUEST_SEQUENCE =
            new AtomicReference<>(ResolveGeneration.initial());
    private static final AtomicLong STALE_RESOLVE_DROPS = new AtomicLong();
    private static final AtomicLong NO_CONSUMER_RESOLVE_DROPS = new AtomicLong();

    private ModernTurntableVideoClient() {
    }

    public static void forgetSession(String sessionId) {
        PlaybackSessionId key = sessionKey(sessionId);
        if (key == null) {
            return;
        }
        String normalizedSessionId = key.value();
        VideoBillboardPreview.clearPendingLoading(normalizedSessionId);
        ACTIVE_SESSION_IDS.remove(key);
        ACTIVE_QUALITY_CEILING_BY_SESSION.remove(key);
        ACTIVE_REQUEST_BY_SESSION.remove(key);
        cancelPendingRequest(key);
        AUDIO_PRESENCE.forget(key);
        LAST_DECISION_BY_SESSION.remove(key);
        ACTIVE_SESSION_BY_TURNTABLE.entrySet().removeIf(entry -> key.equals(entry.getValue()));
        LATEST_SESSION_BY_TURNTABLE.entrySet().removeIf(entry -> key.equals(entry.getValue()));
    }

    /** 客户端断连/切世界时清理所有视频同步决策状态，避免旧世界 session 与 BlockPos 残留。 */
    public static void clear() {
        PENDING_REQUEST_BY_SESSION.forEach((sessionId, pending) -> {
            if (PENDING_REQUEST_BY_SESSION.remove(sessionId, pending)) {
                pending.cancel();
            }
        });
        ACTIVE_SESSION_IDS.clear();
        ACTIVE_SESSION_BY_TURNTABLE.clear();
        LATEST_SESSION_BY_TURNTABLE.clear();
        ACTIVE_QUALITY_CEILING_BY_SESSION.clear();
        ACTIVE_REQUEST_BY_SESSION.clear();
        AUDIO_PRESENCE.clear();
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
        PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata();
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
        PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata();
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
        PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata();
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

    /** 发布客户端对当前媒体 session 的权威音频能力判定。 */
    public static void publishAudioPresence(String sessionId, ClientMediaPreparer.AudioPresence presence) {
        if (sessionId == null || sessionId.isBlank() || presence == null
                || presence == ClientMediaPreparer.AudioPresence.UNKNOWN) {
            return;
        }
        AUDIO_PRESENCE.publish(sessionId, presence);
    }

    private static void syncFromPlayback(String rawUrl, BlockPos turntablePos, PlaybackSync.Metadata sync,
            List<VideoProjectorBlockEntity> explicitProjectors) {
        if (!VIDEO_PROPERTIES.enabled() || sync == null || !sync.hasSession()) {
            return;
        }
        PlaybackSessionId playbackSessionId = sync.playbackSessionId().orElse(null);
        if (playbackSessionId == null) {
            return;
        }
        String cleanRawUrl = PlaybackSync.strip(rawUrl);
        BiliApiClient.VideoSelection selection = BiliVideoStreamResolver.selectionOrNull(cleanRawUrl);
        if (selection == null) {
            LOGGER.debug("现代唱片机视频同步跳过: 无法识别 B站视频 URL: rawUrl={}", cleanRawUrl);
            return;
        }
        String sessionId = playbackSessionId.value();
        BlockPos immutableTurntablePos = turntablePos != null ? turntablePos.immutable() : null;
        if (immutableTurntablePos != null) {
            LATEST_SESSION_BY_TURNTABLE.put(immutableTurntablePos, playbackSessionId);
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
                ACTIVE_SESSION_IDS.add(playbackSessionId);
                rememberActiveSession(immutableTurntablePos, sessionId);
                logDecision(sessionId, "reuse-simulated-projector", turntablePos, sync.elapsedMillis(), 0, 0, 0L,
                        VideoBillboardPreview.hasTerminalFailure(sessionId)
                            ? "failed session is retained on a BER-backed simulated projector"
                                : "running session is retained for a BER-backed simulated projector");
                return;
            }
            logDecision(sessionId, "stop-no-projector", turntablePos, sync.elapsedMillis(), 0, 0, 0L,
                    "no linked video projector");
                LOGGER.debug("现代唱片机视频同步跳过: 没有实时视频消费者: session={} turntable={}", sessionId,
                    turntablePos);
            VideoBillboardPreview.stopIfSession(sessionId);
            forgetSession(sessionId);
            return;
        }
        List<BlockPos> projectorPositions = projectors.stream()
                .map(projector -> projector.getBlockPos().immutable())
            .toList();
        List<BlockPos> consumerPositions = new ArrayList<>(projectorPositions);
        consumerPositions.addAll(consoleConsumers);
        ClientMediaPreparer.AudioPresence audioPresence = AUDIO_PRESENCE.presence(playbackSessionId);
        if (!audioGateAllowsVideo(turntablePos, sessionId, audioPresence)) {
            // 这里只是 admission 尚未满足，不是 session 结束。stopIfSession/forgetSession 会删除
            // 音频能力状态，使刚发布的 PRESENT 下一帧又退回 UNKNOWN，形成永久等待循环。
            // 也不要停止已运行的同 session 视频：音频恢复/输出切换时 timeline 可能短暂不可见。
            VideoBillboardPreview.beginPendingLoading(sessionId, consumerPositions);
            logDecision(sessionId, "wait-audio-ready", turntablePos, sync.elapsedMillis(), 0, 0, 0L,
                    "video waits until matching audio stream is ready");
            return;
        }
        long elapsedMillis = Math.max(0L, sync.elapsedMillis());
        int qualityCeiling = qualityCeiling(projectors, consoleConsumers);
        if (VideoBillboardPreview.hasTerminalFailure(sessionId)) {
            VideoBillboardPreview.updateSessionProjectors(sessionId, consumerPositions);
            ACTIVE_SESSION_IDS.add(playbackSessionId);
            rememberActiveSession(immutableTurntablePos, sessionId);
            logDecision(sessionId, "hold-network-failure", turntablePos, elapsedMillis, qualityCeiling,
                    projectorPositions.size(), 0L,
                    "same session is held at the error placeholder until a new session or explicit retry");
            return;
        }
        PlaybackSessionId existingForTurntableKey = immutableTurntablePos != null
                ? ACTIVE_SESSION_BY_TURNTABLE.get(immutableTurntablePos)
                : null;
        if (existingForTurntableKey != null
                && VideoBillboardPreview.isSessionRunning(existingForTurntableKey.value())) {
            String existingForTurntable = existingForTurntableKey.value();
            if (existingForTurntableKey.equals(playbackSessionId)) {
                VideoBillboardPreview.updateSessionProjectors(existingForTurntable, consumerPositions);
                if (VideoBillboardPreview.isSessionWaitingForFirstFrame(existingForTurntable)) {
                    ACTIVE_SESSION_IDS.add(playbackSessionId);
                    rememberActiveSession(immutableTurntablePos, sessionId);
                    logDecision(sessionId, "reuse-wait-first-frame", turntablePos, elapsedMillis, qualityCeiling,
                            projectorPositions.size(), 0L, "same session already decoding");
                    return;
                }
                if (isSessionRunningAtQualityCeiling(existingForTurntable, qualityCeiling)
                        && VideoBillboardPreview.canSessionChaseToOffset(existingForTurntable, elapsedMillis)) {
                    ACTIVE_SESSION_IDS.add(playbackSessionId);
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
                ACTIVE_SESSION_IDS.add(playbackSessionId);
                rememberActiveSession(immutableTurntablePos, sessionId);
                logDecision(sessionId, "reuse-wait-first-frame", turntablePos, elapsedMillis, qualityCeiling,
                        projectorPositions.size(), 0L, "running session has not produced first frame yet");
                return;
            }
            if (isSessionRunningAtQualityCeiling(sessionId, qualityCeiling)
                    && VideoBillboardPreview.canSessionChaseToOffset(sessionId, elapsedMillis)) {
                ACTIVE_SESSION_IDS.add(playbackSessionId);
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
        if (!ACTIVE_SESSION_IDS.add(playbackSessionId)) {
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
                VideoResolveRequestOwner<BlockPos> pending = PENDING_REQUEST_BY_SESSION.get(playbackSessionId);
                if (pending != null && pending.matches(elapsedMillis, qualityCeiling)) {
                    VideoBillboardPreview.beginPendingLoading(sessionId, consumerPositions);
                    rememberActiveSession(immutableTurntablePos, sessionId);
                    logDecision(sessionId, "reuse-pending", turntablePos, elapsedMillis, qualityCeiling,
                            projectorPositions.size(), pending.requestGeneration().value(),
                            "stream resolve already in flight");
                    return;
                }
                logDecision(sessionId, "replace-pending", turntablePos, elapsedMillis, qualityCeiling,
                        projectorPositions.size(), pending != null ? pending.requestGeneration().value() : 0L,
                        pending != null ? "pending quality ceiling changed" : "active marker without renderer");
                markSessionRestarting(sessionId);
                ACTIVE_SESSION_IDS.add(playbackSessionId);
                rememberActiveSession(immutableTurntablePos, sessionId);
            } else {
                logDecision(sessionId, "restart-active-marker", turntablePos, elapsedMillis, qualityCeiling,
                        projectorPositions.size(), 0L, "active marker conflicts with renderer state");
                VideoBillboardPreview.stopIfSession(sessionId);
                markSessionRestarting(sessionId);
                ACTIVE_SESSION_IDS.add(playbackSessionId);
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
        ACTIVE_QUALITY_CEILING_BY_SESSION.put(playbackSessionId, qualityCeiling);
        long requestNanoTime = System.nanoTime();
        ResolveGeneration requestGeneration = REQUEST_SEQUENCE.updateAndGet(
                current -> Objects.requireNonNull(current, "current generation").next());
        ACTIVE_REQUEST_BY_SESSION.put(playbackSessionId, requestGeneration);
        VideoResolveRequestOwner<BlockPos> pendingRequest = new VideoResolveRequestOwner<>(qualityCeiling,
                requestGeneration, List.copyOf(consumerPositions));
        VideoResolveRequestOwner<BlockPos> replaced = PENDING_REQUEST_BY_SESSION.put(playbackSessionId, pendingRequest);
        if (replaced != null) {
            replaced.cancel();
        }
        if (!ModernTurntablePlaybackTracker.replaceResource(turntablePos, sessionId, "video-resolve",
            pendingRequest::cancel)) {
            PENDING_REQUEST_BY_SESSION.remove(playbackSessionId, pendingRequest);
            ACTIVE_REQUEST_BY_SESSION.remove(playbackSessionId, requestGeneration);
            ACTIVE_SESSION_IDS.remove(playbackSessionId);
            pendingRequest.cancel();
            return;
        }
        VideoBillboardPreview.beginPendingLoading(sessionId, consumerPositions);
        logDecision(sessionId, "schedule-resolve", turntablePos, elapsedMillis, qualityCeiling,
                projectorPositions.size(), requestGeneration.value(),
                "async B站 video stream resolve with quality ceiling");
        CancellableTaskFuture<Void> resolveTask = CancellableTaskFuture.submit(VIDEO_RESOLVE_EXECUTOR, () -> {
            startResolved(cleanRawUrl, selection, turntablePos, consumerPositions, qualityCeiling,
                sync, requestNanoTime, requestGeneration);
            return null;
        });
        pendingRequest.bind(resolveTask);
        resolveTask
                .orTimeout(45, TimeUnit.SECONDS)
            .whenComplete((ignored, error) -> {
                if (error == null || error instanceof CancellationException) {
                return;
                }
                if (isTimeout(error)) {
                pendingRequest.cancel();
                LOGGER.warn("现代唱片机视频同步启动超时: {}", cleanRawUrl, error);
                Minecraft.getInstance().execute(() -> clearTimedOutRequest(
                    sessionId, requestGeneration, turntablePos, consumerPositions, error));
                }
                });
    }

    private static void clearTimedOutRequest(String sessionId, ResolveGeneration requestGeneration,
            BlockPos turntablePos,
            List<BlockPos> capturedConsumers, Throwable error) {
        if (!isTimeout(error) || !isLatestRequestForTurntable(sessionId, requestGeneration, turntablePos)) {
            return;
        }
        PlaybackSessionId key = sessionKey(sessionId);
        if (key == null) {
            return;
        }
        ACTIVE_REQUEST_BY_SESSION.remove(key, requestGeneration);
        cancelPendingRequest(key, requestGeneration);
        ACTIVE_SESSION_IDS.remove(key);
        VideoBillboardPreview.clearPendingLoading(sessionId);
        LiveConsumers consumers = currentConsumers(turntablePos, sessionId, capturedConsumers);
        logDecision(sessionId, "resolve-timeout-cleared", turntablePos, 0L, 0,
                consumers.positions().size(), requestGeneration.value(),
                "timed out request markers cleared so an active consumer can retry");
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static void markSessionRestarting(String sessionId) {
        PlaybackSessionId key = sessionKey(sessionId);
        if (key == null) {
            return;
        }
        ACTIVE_SESSION_IDS.remove(key);
        ACTIVE_QUALITY_CEILING_BY_SESSION.remove(key);
        ACTIVE_REQUEST_BY_SESSION.remove(key);
        cancelPendingRequest(key);
    }

    private static void cancelPendingRequest(PlaybackSessionId key) {
        VideoResolveRequestOwner<BlockPos> pending = PENDING_REQUEST_BY_SESSION.remove(key);
        if (pending != null) {
            pending.cancel();
        }
    }

    private static void cancelPendingRequest(String sessionId, ResolveGeneration requestGeneration) {
        PlaybackSessionId key = sessionKey(sessionId);
        if (key == null) {
            return;
        }
        cancelPendingRequest(key, requestGeneration);
    }

    private static void cancelPendingRequest(PlaybackSessionId key, ResolveGeneration requestGeneration) {
        PENDING_REQUEST_BY_SESSION.computeIfPresent(key, (ignored, pending) -> {
            if (!pending.requestGeneration().equals(requestGeneration)) {
                return pending;
            }
            pending.cancel();
            return null;
        });
    }

    private static boolean isLatestRequest(String sessionId, ResolveGeneration requestGeneration) {
        PlaybackSessionId key = sessionKey(sessionId);
        ResolveGeneration active = key != null ? ACTIVE_REQUEST_BY_SESSION.get(key) : null;
        return requestGeneration != null && requestGeneration.equals(active);
    }

    private static boolean isLatestRequestForTurntable(String sessionId, ResolveGeneration requestGeneration,
            BlockPos turntablePos) {
        if (!isLatestRequest(sessionId, requestGeneration)) {
            return false;
        }
        if (turntablePos == null) {
            return true;
        }
        PlaybackSessionId activeSession = ACTIVE_SESSION_BY_TURNTABLE.get(turntablePos);
        PlaybackSessionId key = sessionKey(sessionId);
        return key != null && key.equals(activeSession);
    }

    private static void invalidateIfNoLiveConsumer(BlockPos turntablePos) {
        if (turntablePos == null) {
            return;
        }
        PlaybackSessionId key = ACTIVE_SESSION_BY_TURNTABLE.get(turntablePos);
        if (key == null) {
            key = LATEST_SESSION_BY_TURNTABLE.get(turntablePos);
        }
        if (key == null) {
            return;
        }
        String sessionId = key.value();
        VideoResolveRequestOwner<BlockPos> pending = PENDING_REQUEST_BY_SESSION.get(key);
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

    static boolean audioGateAllowsVideo(BlockPos turntablePos, String sessionId,
            ClientMediaPreparer.AudioPresence presence) {
        boolean validSession = turntablePos != null && sessionKey(sessionId) != null;
        return validSession && VideoAudioReadinessPolicy.allowsVideo(presence,
                presence == ClientMediaPreparer.AudioPresence.PRESENT && isAudioReady(turntablePos, sessionId));
    }

    private static void rememberActiveSession(BlockPos turntablePos, String sessionId) {
        PlaybackSessionId key = sessionKey(sessionId);
        if (turntablePos != null && key != null) {
            ACTIVE_SESSION_BY_TURNTABLE.put(turntablePos, key);
            LATEST_SESSION_BY_TURNTABLE.put(turntablePos, key);
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
        PlaybackSessionId key = sessionKey(sessionId);
        Integer activeQualityCeiling = key != null ? ACTIVE_QUALITY_CEILING_BY_SESSION.get(key) : null;
        return activeQualityCeiling != null && activeQualityCeiling == requestedQualityCeiling;
    }

    private static void startResolved(String cleanRawUrl, BiliApiClient.VideoSelection selection, BlockPos turntablePos,
            List<BlockPos> projectorPositions,
            int qualityCeiling, PlaybackSync.Metadata sync, long requestNanoTime,
            ResolveGeneration requestGeneration) {
        PlaybackSessionId playbackSessionId = sync.playbackSessionId().orElse(null);
        if (playbackSessionId == null) {
            return;
        }
        try {
            ResolvedVideoStream stream = BiliVideoStreamResolver.resolve(cleanRawUrl, qualityCeiling, DEFAULT_FPS);
            int sourceWidth = stream.sourceWidth();
            int sourceHeight = stream.sourceHeight();
            int fps = stream.fps();
            Minecraft.getInstance().execute(() -> {
                LiveConsumers consumers = currentConsumers(turntablePos, sync.sessionId(), projectorPositions);
                VideoResolveAdmissionPolicy.Decision decision = resolveAdmission(sync.sessionId(), requestGeneration,
                    turntablePos, consumers);
                if (decision != VideoResolveAdmissionPolicy.Decision.START) {
                    dropResolvedResult(sync.sessionId(), requestGeneration, turntablePos, decision);
                    return;
                }
                // 这里记录的是“本次请求已满足的偏好档位”，不是 B 站实际返回的 qn。
                // 只有实时准入通过后才能发布，避免最后 consumer 退出后由后台线程复活状态。
                ACTIVE_QUALITY_CEILING_BY_SESSION.put(playbackSessionId, qualityCeiling);
                PlaybackSync.Metadata launchSync = currentPlaybackMetadata(turntablePos, sync);
                long elapsedMillis = normalizedElapsedMillis(launchSync);
                logDecision(sync.sessionId(), "resolved-start", turntablePos, elapsedMillis, qualityCeiling,
                        consumers.positions().size(), requestGeneration.value(),
                        "qualityCeiling=" + qualityCeiling + " actualQuality=" + stream.quality() + " title='"
                                + stream.title() + "' size=" + sourceWidth + "x" + sourceHeight + " fps=" + fps
                                + " launchTimelineRefreshed=" + (launchSync != sync));
                VideoBillboardPreview.startSyncedCandidates(stream.candidates(), sourceWidth,
                        sourceHeight, fps, launchSync.sessionId(), elapsedMillis,
                        launchSync.totalMillis(),
                        consumers.positions(),
                        turntablePos,
                        PREFER_NATIVE, DECODER_OVERRIDE.isBlank() ? null : DECODER_OVERRIDE);
                cancelPendingRequest(sync.sessionId(), requestGeneration);
                ACTIVE_REQUEST_BY_SESSION.remove(playbackSessionId, requestGeneration);
            });
        } catch (Exception e) {
            Minecraft.getInstance().execute(() -> {
                LiveConsumers consumers = currentConsumers(turntablePos, sync.sessionId(), projectorPositions);
                VideoResolveAdmissionPolicy.Decision decision = resolveAdmission(sync.sessionId(), requestGeneration,
                        turntablePos, consumers);
                if (decision == VideoResolveAdmissionPolicy.Decision.START) {
                    cancelPendingRequest(sync.sessionId(), requestGeneration);
                    ACTIVE_REQUEST_BY_SESSION.remove(playbackSessionId, requestGeneration);
                    VideoBillboardPreview.markPendingFailure(sync.sessionId(), consumers.positions());
                } else {
                    dropResolvedResult(sync.sessionId(), requestGeneration, turntablePos, decision);
                }
            });
            throw new IllegalStateException("resolve B站 video stream failed", e);
        }
    }

    private static VideoResolveAdmissionPolicy.Decision resolveAdmission(String sessionId,
            ResolveGeneration requestGeneration, BlockPos turntablePos, LiveConsumers consumers) {
        boolean latest = isLatestRequestForTurntable(sessionId, requestGeneration, turntablePos);
        Minecraft minecraft = Minecraft.getInstance();
        BlockEntity blockEntity = minecraft.level != null && turntablePos != null
                ? minecraft.level.getBlockEntity(turntablePos) : null;
        if (!(blockEntity instanceof ModernTurntableBlockEntity turntable) || !turntable.isPlaying()) {
            return VideoResolveAdmissionPolicy.decide(latest, false, false, consumers.hasAny());
        }
        PlaybackSync.Metadata current = turntable.getPlaybackSyncMetadata();
        boolean sameSession = current.hasSession() && sessionId.equals(current.sessionId());
        return VideoResolveAdmissionPolicy.decide(latest, sameSession, true, consumers.hasAny());
    }

    private static void dropResolvedResult(String sessionId, ResolveGeneration requestGeneration,
            BlockPos turntablePos, VideoResolveAdmissionPolicy.Decision decision) {
        if (decision == VideoResolveAdmissionPolicy.Decision.DROP_NO_CONSUMER) {
            NO_CONSUMER_RESOLVE_DROPS.incrementAndGet();
        } else {
            STALE_RESOLVE_DROPS.incrementAndGet();
        }
        if (isLatestRequest(sessionId, requestGeneration)) {
            markSessionRestarting(sessionId);
            VideoBillboardPreview.clearPendingLoading(sessionId);
        }
        logDecision(sessionId, "drop-resolve-" + decision.name().toLowerCase(java.util.Locale.ROOT), turntablePos,
                0L, 0, 0, requestGeneration.value(), "resolved result failed live admission");
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
                    + " pendingFailure=" + resources.pendingFailure()
                    + " closeZombies=" + resources.activeCloseZombies()
                    + " lateCloseConvergences=" + resources.lateCloseConvergences(),
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
        PlaybackSync.Metadata current = turntable.getPlaybackSyncMetadata();
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
        PlaybackSessionId key = sessionKey(sessionId);
        if (key == null) {
            return;
        }
        String fingerprint = action + '|' + quality + '|' + projectorCount + '|' + requestId + '|' + reason;
        String previous = LAST_DECISION_BY_SESSION.put(key, fingerprint);
        if (fingerprint.equals(previous)) {
            return;
        }
        LOGGER.debug(
                "现代唱片机视频同步决策: action={} session={} request={} turntable={} elapsed={}ms qualityCeiling={} projectors={} reason={}",
                action, sessionId, requestId, turntablePos, Math.max(0L, elapsedMillis), quality, projectorCount,
                reason);
    }

    private static PlaybackSessionId sessionKey(String sessionId) {
        return PlaybackSessionId.parse(sessionId).orElse(null);
    }

    private record LiveConsumers(List<BlockPos> positions, boolean holographic, boolean gui) {
        private boolean hasAny() {
            return !positions.isEmpty() || holographic || gui;
        }
    }

}
