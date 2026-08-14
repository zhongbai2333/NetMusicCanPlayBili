package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.PadDiagnosticsProperties;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.ResolvedVideoStream;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoFallbackReason;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoZombieCloseSupervisor;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaDeviceProfile;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaRenderState;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoPipelineConfig;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 手持 MP4 横屏界面使用的小型视频帧源。
 *
 * <p>
 * 这里刻意不复用 {@code VideoBillboardPreview} 的世界几何提交：稳定的唱片机/投影仪路径继续负责投影表面，
 * 本类只借用同一套 Bili 流解析器和原生 fMP4 解码器，为物品图形界面纹理提供一个很小的最新帧缓存。
 * </p>
 */
public final class MP4HandheldVideoClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HandheldVideoPipelineConfig CONFIG = HandheldVideoPipelineConfig.fromSystemProperties(
            "ncpb.mp4.video");
    private static final VideoClientProperties.Handheld VIDEO_PROPERTIES = VideoClientProperties.handheld();
    private static final boolean PAD_VIDEO_DEBUG_LOG = PadDiagnosticsProperties.videoDebugLogEnabled();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(VIDEO_PROPERTIES.maxThreads(),
            NetMusicThreadFactory.daemon("mp4-handheld-video"));
    private static final Map<PlaybackSourceId, HandheldDeviceVideoState> STATES = new ConcurrentHashMap<>();
    private static final Map<PlaybackSourceId, HandheldMediaDeviceProfile> PROFILES = new ConcurrentHashMap<>();
    private static final Map<PlaybackSourceId, HandheldReplacementGate> REPLACEMENT_GATES = new ConcurrentHashMap<>();
    private static final MP4HandheldMediaProfile MP4_PROFILE = MP4HandheldMediaProfile.INSTANCE;
    private static final long CANDIDATE_CLOSE_TIMEOUT_MILLIS = 3_000L;
    private static final AtomicLong HANDHELD_CLOSE_SEQUENCE = new AtomicLong();

    private MP4HandheldVideoClient() {
    }

    public static boolean update(UUID deviceId) {
        return update(deviceId, MP4_PROFILE);
    }

    public static boolean update(UUID deviceId, HandheldMediaDeviceProfile profile) {
        if (deviceId == null) {
            return false;
        }
        HandheldMediaDeviceProfile activeProfile = profile != null ? profile : MP4_PROFILE;
        PROFILES.put(PlaybackSourceId.of(deviceId), activeProfile);
        HandheldDeviceVideoState state = state(deviceId);
        if (!activeProfile.isDeviceAvailable(deviceId)) {
            stop(deviceId, "等待快捷栏");
            return false;
        }
        HandheldMediaRenderState renderState = activeProfile.renderState(deviceId);
        if (!renderState.videoDecodeEnabled()) {
            stop(deviceId, "等待横屏播放");
            return false;
        }
        if (!com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection.allowMediaStart()) {
            stop(deviceId, "视频内存保护冷却中");
            return false;
        }
        HandheldMediaPlayback playback = activeProfile.playback(deviceId);
        if (!playback.hasPlayableVideoSource()) {
            stop(deviceId, "等待播放同步");
            return false;
        }
        if (!VideoNativeDecoder.isNativeAvailable()) {
            stopForNativeUnavailable(state, playback);
            return false;
        }
        if (!activeProfile.canStartVideoDecode(deviceId, playback)) {
            waitForAudioStart(state);
            return false;
        }
        HandheldPlaybackKey key = new HandheldPlaybackKey(playback.playbackSessionId(), playback.rawUrl(),
                renderState.videoQualityCeiling(), renderState.allowAiSubtitle(),
                HandheldVideoFrameTimeline.shouldUseRgbaFallback() || HandheldVideoFrameTimeline.hasActiveRgbaConsumer(state));
        long intentGeneration;
        synchronized (state.lifecycleLock) {
            HandheldVideoSession session = state.activeSession;
            if (key.equals(state.activeKey) && session != null && !session.closed.get()) {
                return HandheldVideoFrameTimeline.pumpFrameForTimeline(state, session, HandheldVideoFrameTimeline.anchoredVisualMillis(deviceId, activeProfile, playback));
            }
            if (key.equals(state.resolvingKey)) {
                return false;
            }
            if (key.equals(state.activeKey) && (key.equals(state.failedKey) || key.equals(state.endedKey))) {
                return false;
            }
            stopLocked(state, "切换视频源");
            intentGeneration = state.intentGeneration;
            state.activeKey = key;
            state.resolvingKey = key;
            state.failedKey = HandheldPlaybackKey.EMPTY;
            state.endedKey = HandheldPlaybackKey.EMPTY;
            if (!BiliVideoStreamResolver.isStoredVideoSelection(playback.rawUrl())) {
                state.resolvingKey = HandheldPlaybackKey.EMPTY;
                state.failedKey = key;
                state.audioOnly = true;
                state.statusText = "纯音乐";
                state.sourceWidth = 0;
                state.sourceHeight = 0;
                HandheldVideoFrameTimeline.clearFrameQueue(state);
                return false;
            }
            state.audioOnly = false;
            state.statusText = "解析视频流...";
            state.sourceWidth = 0;
            state.sourceHeight = 0;
        }
        resolveAndStart(deviceId, state, playback, key, intentGeneration);
        return false;
    }

    public static void markVisible(UUID deviceId) {
        if (deviceId == null) {
            return;
        }
        HandheldDeviceVideoState state = state(deviceId);
        long nowNs = System.nanoTime();
        long offscreenSince = state.offscreenSinceNanoTime;
        state.lastVisibleNanoTime = nowNs;
        state.offscreenSinceNanoTime = 0L;
        if (offscreenSince > 0L) {
            HandheldOffscreenVideoPolicy.maybeRestartVisibleSession(deviceId, state, nowNs - offscreenSince);
        }
    }

    public static void requestRgbaOutput(UUID deviceId) {
        if (deviceId == null) {
            return;
        }
        HandheldDeviceVideoState state = state(deviceId);
        state.rgbaConsumerUntilNanoTime = System.nanoTime() + Math.max(0L, CONFIG.rgbaConsumerGraceNanos());
        markVisible(deviceId);
    }

    public static HandheldVideoFrame latestFrame(UUID deviceId) {
        HandheldDeviceVideoState state = stateOrNull(deviceId);
        return state != null ? state.latestFrame.get() : null;
    }

    public static HandheldVideoFrame acquireLatestFrame(UUID deviceId) {
        HandheldDeviceVideoState state = stateOrNull(deviceId);
        if (state == null) {
            return null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            HandheldVideoFrame frame = state.latestFrame.get();
            if (frame == null) {
                return null;
            }
            try {
                return frame.retain();
            } catch (IllegalStateException ignored) {
                // The producer replaced and closed this frame between get() and retain(); retry
                // the current slot.
            }
        }
        return null;
    }

    public static long frameSequence(UUID deviceId) {
        HandheldDeviceVideoState state = stateOrNull(deviceId);
        return state != null ? state.frameSequence.get() : -1L;
    }

    public static String statusText(UUID deviceId) {
        HandheldDeviceVideoState state = stateOrNull(deviceId);
        return state != null ? state.statusText : "等待设备 ID";
    }

    public static boolean audioOnly(UUID deviceId) {
        HandheldDeviceVideoState state = stateOrNull(deviceId);
        return state != null && state.audioOnly;
    }

    public static String currentResolutionLabel(UUID deviceId) {
        HandheldDeviceVideoState state = stateOrNull(deviceId);
        if (state == null) {
            return "";
        }
        HandheldVideoFrame frame = state.latestFrame.get();
        if (frame != null && frame.width() > 0 && frame.height() > 0) {
            return frame.width() + "x" + frame.height();
        }
        int width = state.sourceWidth;
        int height = state.sourceHeight;
        if (width > 0 && height > 0) {
            DecodeSize preview = HandheldVideoFrameTimeline.chooseDecodeSize(width, height);
            return preview.width() + "x" + preview.height();
        }
        return "";
    }

    public static String currentSubtitle(UUID deviceId) {
        HandheldDeviceVideoState state = stateOrNull(deviceId);
        if (state == null) {
            return "";
        }
        LyricRecord record = state.subtitleRecord;
        if (record == null) {
            return state.currentSubtitle != null ? state.currentSubtitle : "";
        }
        HandheldMediaDeviceProfile profile = profileFor(deviceId);
        HandheldMediaPlayback playback = profile.playback(deviceId);
        long visualMillis = HandheldVideoFrameTimeline.anchoredVisualMillis(deviceId, profile, playback);
        int tick = visualMillis >= 0L
                ? (int) Math.min(Integer.MAX_VALUE, visualMillis / 50L)
                : -1;
        String primary = currentLineAt(record.getLyrics(), tick);
        String secondary = currentLineAt(record.getTransLyrics(), tick);
        String mode = profile.subtitleMode(deviceId);
        if ("off".equals(mode)) {
            return "";
        }
        if ("primary".equals(mode)) {
            return !primary.isBlank() ? primary : secondary;
        }
        return !secondary.isBlank() ? secondary : primary;
    }

    public static void stop(String reason) {
        STATES.values().forEach(state -> stop(state, reason));
        MP4ItemScreenRenderer.releaseAllVideoLayers();
    }

    public static void stop(UUID deviceId, String reason) {
        HandheldMediaDeviceProfile profile = profileFor(deviceId);
        HandheldDeviceVideoState state = stateOrNull(deviceId);
        if (state != null) {
            stop(state, reason);
        }
        if (profile == MP4_PROFILE) {
            MP4ItemScreenRenderer.releaseVideoLayers(deviceId);
        } else {
            com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer
                    .releaseVideoLayers(deviceId);
        }
    }

    private static void stop(HandheldDeviceVideoState state, String reason) {
        synchronized (state.lifecycleLock) {
            stopLocked(state, reason);
        }
    }

    private static void stopLocked(HandheldDeviceVideoState state, String reason) {
        state.intentGeneration++;
        cancelResolveTaskLocked(state);
        state.activeKey = HandheldPlaybackKey.EMPTY;
        state.resolvingKey = HandheldPlaybackKey.EMPTY;
        state.failedKey = HandheldPlaybackKey.EMPTY;
        state.endedKey = HandheldPlaybackKey.EMPTY;
        HandheldVideoSession session = state.activeSession;
        state.activeSession = null;
        if (session != null) {
            session.close();
        }
        if (reason != null && !reason.isBlank()) {
            state.statusText = reason;
        }
        state.audioOnly = false;
        state.subtitleRecord = null;
        state.currentSubtitle = "";
        state.sourceWidth = 0;
        state.sourceHeight = 0;
        HandheldVideoFrameTimeline.clearFrameQueue(state);
        HandheldVideoFrame latest = state.latestFrame.getAndSet(null);
        if (latest != null) {
            latest.close();
            state.frameSequence.incrementAndGet();
        }
    }

    private static void stopForNativeUnavailable(HandheldDeviceVideoState state, HandheldMediaPlayback playback) {
        synchronized (state.lifecycleLock) {
            if ("原生视频不可用".equals(state.statusText)) {
                return;
            }
            stopLocked(state, "原生视频不可用");
            state.audioOnly = true;
            state.statusText = "原生视频不可用";
            LOGGER.warn("手持视频解码跳过：FFmpeg native 未加载，session={} raw='{}'",
                    playback != null ? playback.sessionId() : "unknown",
                    playback != null ? playback.rawUrl() : "unknown");
        }
    }

    private static void waitForAudioStart(HandheldDeviceVideoState state) {
        synchronized (state.lifecycleLock) {
            state.intentGeneration++;
            cancelResolveTaskLocked(state);
            state.statusText = "等待音频缓冲...";
            state.audioOnly = false;
            HandheldVideoSession session = state.activeSession;
            if (session != null) {
                session.close();
                state.activeSession = null;
            }
            state.activeKey = HandheldPlaybackKey.EMPTY;
            state.resolvingKey = HandheldPlaybackKey.EMPTY;
            state.failedKey = HandheldPlaybackKey.EMPTY;
            state.endedKey = HandheldPlaybackKey.EMPTY;
        }
        HandheldVideoFrameTimeline.clearFrameQueue(state);
        HandheldVideoFrame latest = state.latestFrame.getAndSet(null);
        if (latest != null) {
            latest.close();
            state.frameSequence.incrementAndGet();
        }
    }

    public static void clearAll() {
        STATES.values().forEach(state -> stop(state, "等待播放"));
        STATES.clear();
        PROFILES.clear();
        MP4ItemScreenRenderer.releaseAllVideoLayers();
    }

    public static void stopDevicesOutsideHotbar() {
        for (Map.Entry<PlaybackSourceId, HandheldDeviceVideoState> entry : STATES.entrySet()) {
            UUID deviceId = entry.getKey().value();
            HandheldMediaDeviceProfile profile = profileFor(deviceId);
            if (!profile.isDeviceAvailable(deviceId)) {
                stop(entry.getValue(), profile == MP4_PROFILE ? "等待快捷栏" : "等待设备");
                if (profile == MP4_PROFILE) {
                    MP4ItemScreenRenderer.releaseDeviceResources(deviceId);
                } else {
                    com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer
                            .releaseDeviceResources(deviceId);
                }
            }
        }
    }

    public static void tickHotbarVideoFrames() {
        tickHotbarHandheldVideoSessions();
        for (Map.Entry<PlaybackSourceId, HandheldDeviceVideoState> entry : STATES.entrySet()) {
            UUID deviceId = entry.getKey().value();
            HandheldMediaDeviceProfile profile = profileFor(deviceId);
            if (!profile.isDeviceAvailable(deviceId)) {
                continue;
            }
            HandheldDeviceVideoState state = entry.getValue();
            HandheldVideoSession session = state.activeSession;
            if (session == null || session.closed.get() || !session.key.equals(state.activeKey)) {
                continue;
            }
            HandheldMediaPlayback playback = profile.playback(deviceId);
            if (playback == null || !session.key.playbackSessionId().equals(playback.playbackSessionId())) {
                continue;
            }
            HandheldVideoFrameTimeline.pumpFrameForTimeline(state, session, HandheldVideoFrameTimeline.anchoredVisualMillis(deviceId, profile, playback));
        }
    }

    private static void tickHotbarHandheldVideoSessions() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        MP4DeviceStacks.forEachHotbarAndOffhand(minecraft.player, stack -> {
            tickStackHandheldVideoSession(stack);
            return false;
        });
    }

    private static void tickStackHandheldVideoSession(ItemStack stack) {
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            return;
        }
        MP4Item.State renderState = MP4Client.stateForHeldRender(stack);
        if (!renderState.videoDecodeEnabled()) {
            stop(deviceId, "等待横屏播放");
            return;
        }
        update(deviceId);
    }

    private static void resolveAndStart(UUID deviceId, HandheldDeviceVideoState state,
            HandheldMediaPlayback playback,
            HandheldPlaybackKey key, long intentGeneration) {
        CancellableTaskFuture<ResolvedVideoStream> resolveTask = CancellableTaskFuture.submit(EXECUTOR,
                () -> resolveStream(playback, key.quality()));
        synchronized (state.lifecycleLock) {
            if (!isCurrentIntent(deviceId, state, key, intentGeneration)) {
                resolveTask.cancel(true);
                return;
            }
            CancellableTaskFuture<ResolvedVideoStream> previous = state.resolveTask;
            state.resolveTask = resolveTask;
            if (previous != null && previous != resolveTask) {
                previous.cancel(true);
            }
        }
        resolveTask.whenComplete((stream, error) -> {
                    synchronized (state.lifecycleLock) {
                        if (state.resolveTask == resolveTask) {
                            state.resolveTask = null;
                        }
                        if (!isCurrentIntent(deviceId, state, key, intentGeneration)) {
                            return;
                        }
                        if (error != null) {
                            state.resolvingKey = HandheldPlaybackKey.EMPTY;
                            state.failedKey = key;
                            state.audioOnly = !BiliVideoStreamResolver.isStoredVideoSelection(playback.rawUrl());
                            state.statusText = state.audioOnly ? "纯音乐" : "视频解析失败";
                            LOGGER.warn("MP4 横屏视频流解析失败: session={} raw='{}' reason={}", playback.sessionId(),
                                    playback.rawUrl(), error.toString());
                            return;
                        }
                        state.audioOnly = false;
                        state.subtitleRecord = stream.subtitleRecord();
                        state.currentSubtitle = state.subtitleRecord != null ? "" : "无可用字幕";
                        state.sourceWidth = stream.sourceWidth();
                        state.sourceHeight = stream.sourceHeight();
                        logResolvedStreamIfPad(playback, stream);
                    }
                    HandheldMediaPlayback currentPlayback = profileFor(deviceId).playback(deviceId);
                    if (!isCurrentPlayback(currentPlayback, key)) {
                        synchronized (state.lifecycleLock) {
                            if (isCurrentIntent(deviceId, state, key, intentGeneration)) {
                                state.resolvingKey = HandheldPlaybackKey.EMPTY;
                            }
                        }
                        return;
                    }
                    startDecoder(deviceId, state, currentPlayback, key, stream, intentGeneration);
                });
    }

    private static boolean isCurrentIntent(UUID deviceId, HandheldDeviceVideoState state,
            HandheldPlaybackKey key, long intentGeneration) {
        return deviceId != null
                && state != null
                && STATES.get(PlaybackSourceId.of(deviceId)) == state
                && state.intentGeneration == intentGeneration
                && key.equals(state.activeKey)
                && key.equals(state.resolvingKey);
    }

    private static boolean isCurrentPlayback(HandheldMediaPlayback playback, HandheldPlaybackKey key) {
        return playback != null
                && key != null
                && key.playbackSessionId().equals(playback.playbackSessionId())
                && key.rawUrl().equals(playback.rawUrl())
                && playback.timeline() != null
                && playback.timeline().mediaMillis() >= 0L;
    }

    private static void logResolvedStreamIfPad(HandheldMediaPlayback playback, ResolvedVideoStream stream) {
        if (PAD_VIDEO_DEBUG_LOG && PadClientMediaSessionIds.isPadSession(playback.sessionId())) {
            LOGGER.info(
                    "Pad video stream resolved: session={} requestedRaw='{}' quality={} codec={} source={}x{} fps={} host={} title='{}'",
                    playback.sessionId(), playback.rawUrl(), stream.quality(), stream.codecId(), stream.sourceWidth(),
                    stream.sourceHeight(), stream.fps(), hostOf(stream.url()), stream.title());
        }
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private static ResolvedVideoStream resolveStream(HandheldMediaPlayback playback, int qualityCeiling) {
        try {
            return BiliVideoStreamResolver.resolveWithSubtitle(playback.rawUrl(), qualityCeiling, playback.title(),
                    playback.allowAiSubtitle());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void startDecoder(UUID deviceId, HandheldDeviceVideoState state,
            HandheldMediaPlayback playback, HandheldPlaybackKey key,
            ResolvedVideoStream stream, long intentGeneration) {
        if (!com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection.allowMediaStart()) {
            synchronized (state.lifecycleLock) {
                if (isCurrentIntent(deviceId, state, key, intentGeneration)) {
                    state.resolvingKey = HandheldPlaybackKey.EMPTY;
                    state.statusText = "视频内存保护冷却中";
                }
            }
            return;
        }
        long elapsedMillis = Math.max(0L, playback.timeline().mediaMillis());
        long totalMillis = Math.max(0L, playback.timeline().totalMillis());
        HandheldVideoSession session;
        synchronized (state.replacementGate) {
            synchronized (state.lifecycleLock) {
                if (!isCurrentIntent(deviceId, state, key, intentGeneration)) {
                    return;
                }

                HandheldReplacementGate.Signals previousSignals = state.replacementGate.snapshot();
                HandheldDecoderAdmissionPolicy.Decision admission = HandheldDecoderAdmissionPolicy.decide(
                        previousSignals.decodeExit(), previousSignals.nativeTermination());
                if (admission == HandheldDecoderAdmissionPolicy.Decision.FAIL_CLOSED) {
                    state.resolvingKey = HandheldPlaybackKey.EMPTY;
                    state.failedKey = key;
                    state.statusText = "旧视频解码器关闭失败";
                    LOGGER.error("手持视频旧 decoder 退出信号异常，拒绝打开新会话: session={}", key.sessionId());
                    return;
                }
                if (admission == HandheldDecoderAdmissionPolicy.Decision.WAIT) {
                    state.statusText = "等待旧视频解码器退出...";
                    waitForPreviousDecoderExit(deviceId, state, key, stream,
                            intentGeneration, previousSignals);
                    return;
                }

                session = new HandheldVideoSession(state, key, elapsedMillis, stream.candidates());
                state.activeSession = session;
                state.replacementGate.install(key.sessionId(), session.decodeExit, session.physicalTermination);
                state.resolvingKey = HandheldPlaybackKey.EMPTY;
                state.statusText = "视频缓冲中...";
            }
        }
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    decodeLoop(deviceId, state, session, stream, elapsedMillis, totalMillis);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    session.completeDecodeTaskExit();
                }
            }, EXECUTOR)
                    .whenComplete((ignored, error) -> completeDecoderTask(state, session, key, stream, error));
        } catch (RuntimeException schedulingFailure) {
            session.completeDecodeTaskExit();
            completeDecoderTask(state, session, key, stream, schedulingFailure);
        }
    }

    private static void waitForPreviousDecoderExit(UUID deviceId, HandheldDeviceVideoState state,
            HandheldPlaybackKey key, ResolvedVideoStream stream, long intentGeneration,
            HandheldReplacementGate.Signals previousSignals) {
        long now = System.nanoTime();
        long closeOperation = VideoCloseDiagnostics.global().begin(previousSignals.sessionId(), EnumSet.of(
                VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED,
                VideoCloseDiagnostics.Phase.NATIVE_TERMINATED), now);
        previousSignals.decodeExit().whenComplete((ignored, error) -> {
            if (error == null) {
                VideoCloseDiagnostics.global().complete(closeOperation,
                        VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED, System.nanoTime());
            }
        });
        previousSignals.nativeTermination().whenComplete((ignored, error) -> {
            if (error == null) {
                VideoCloseDiagnostics.global().complete(closeOperation,
                        VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, System.nanoTime());
            }
        });
        CompletableFuture<Void> convergence = HandheldDecoderAdmissionPolicy.convergence(
                previousSignals.decodeExit(), previousSignals.nativeTermination());
        HandheldReplacementWait waitDecision = new HandheldReplacementWait();
        convergence.orTimeout(CANDIDATE_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> resumeDecoderAfterPreviousExit(
                        deviceId, state, key, stream, intentGeneration,
                        previousSignals, closeOperation, waitDecision, error));
    }

    private static void resumeDecoderAfterPreviousExit(UUID deviceId, HandheldDeviceVideoState state,
            HandheldPlaybackKey key, ResolvedVideoStream stream, long intentGeneration,
            HandheldReplacementGate.Signals previousSignals, long closeOperation,
            HandheldReplacementWait waitDecision, Throwable convergenceError) {
        HandheldDecoderAdmissionPolicy.Decision finalDecision = HandheldDecoderAdmissionPolicy.decide(
                previousSignals.decodeExit(), previousSignals.nativeTermination());
        HandheldReplacementWait.Outcome outcome = waitDecision.complete(convergenceError, finalDecision);
        if (outcome == HandheldReplacementWait.Outcome.ALREADY_DECIDED) {
            return;
        }
        if (outcome == HandheldReplacementWait.Outcome.FAIL_CLOSED) {
            synchronized (state.lifecycleLock) {
                if (isCurrentIntent(deviceId, state, key, intentGeneration)) {
                    state.resolvingKey = HandheldPlaybackKey.EMPTY;
                    state.failedKey = key;
                    state.statusText = "旧视频解码器关闭失败";
                }
            }
            VideoZombieCloseSupervisor.global().track(previousSignals.sessionId(),
                    HANDHELD_CLOSE_SEQUENCE.incrementAndGet(), CompletableFuture.completedFuture(null),
                    previousSignals.nativeTermination(), previousSignals.decodeExit());
            LOGGER.error("手持视频旧 decoder 未正常收敛，拒绝恢复新会话: session={} decision={}",
                    key.sessionId(), finalDecision, convergenceError);
            return;
        }
        HandheldMediaPlayback currentPlayback = profileFor(deviceId).playback(deviceId);
        if (!isCurrentPlayback(currentPlayback, key)) {
            synchronized (state.lifecycleLock) {
                if (isCurrentIntent(deviceId, state, key, intentGeneration)) {
                    state.resolvingKey = HandheldPlaybackKey.EMPTY;
                }
            }
            return;
        }
        startDecoder(deviceId, state, currentPlayback, key, stream, intentGeneration);
    }

    private static void completeDecoderTask(HandheldDeviceVideoState state, HandheldVideoSession session,
            HandheldPlaybackKey key, ResolvedVideoStream stream, Throwable error) {
        if (containsOutOfMemory(error)) {
            com.zhongbai233.net_music_can_play_bili.client.ClientMediaLifecycleHandler
                    .tripMemoryProtection("handheld video decoder allocation failed");
        }
        synchronized (state.lifecycleLock) {
            if (state.activeSession == session) {
                state.activeSession = null;
            }
            if (error != null && !session.closed.get()) {
                state.failedKey = key;
                state.statusText = session.fallbackReason.isBlank() ? "视频播放失败"
                        : "视频播放失败 · " + VideoFallbackReason.userLabel(session.fallbackReason);
                LOGGER.warn("MP4 横屏视频解码失败: session={} stream={} quality={} reason={}", key.sessionId(),
                        stream.url(), stream.quality(), error.toString());
            }
        }
    }

    private static boolean containsOutOfMemory(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof OutOfMemoryError) {
                return true;
            }
        }
        return false;
    }

    private static void decodeLoop(UUID deviceId, HandheldDeviceVideoState state, HandheldVideoSession session,
            ResolvedVideoStream stream,
            long elapsedMillis, long totalMillis)
            throws IOException {
        IOException lastStartupFailure = null;
        boolean forceH264 = false;
        for (BiliVideoStreamResolver.VideoCandidate candidate : stream.candidates()) {
            if (session.closed.get() || !session.key.equals(state.activeKey)) {
                return;
            }
            if (forceH264 && candidate.codecId() != 7) {
                continue;
            }
            ResolvedVideoStream selected = stream.withCandidate(candidate);
            try {
                decodeCandidate(deviceId, state, session, selected, elapsedMillis, totalMillis);
                return;
            } catch (SustainedPerformanceFallbackException fallback) {
                forceH264 = true;
                session.lockPerformanceFallback(fallback.reason);
                HandheldVideoFrameTimeline.clearFrameQueue(state);
                lastStartupFailure = fallback;
                LOGGER.warn(
                        "MP4 横屏 AV1 持续性能回退并锁定 H.264: session={} reason={} quality={} backend={}",
                        session.key.sessionId(), fallback.reason, selected.quality(), session.actualBackend);
            } catch (StartupDecodeException failure) {
                lastStartupFailure = failure;
                if (selected.codecId() == 13) {
                    session.fallbackReason = VideoFallbackReason.classifyAv1StartupFailure(
                            failure, session.h264CandidateAvailable);
                }
                LOGGER.warn(
                        "MP4 横屏视频候选首帧失败，尝试下一候选: session={} quality={} codec={} source={}x{} reason={}",
                        session.key.sessionId(), selected.quality(), selected.codecId(), selected.sourceWidth(),
                        selected.sourceHeight(), failure.getMessage());
            }
        }
        throw lastStartupFailure != null ? lastStartupFailure : new IOException("没有可用的视频解码候选");
    }

    private static void decodeCandidate(UUID deviceId, HandheldDeviceVideoState state, HandheldVideoSession session,
            ResolvedVideoStream stream, long elapsedMillis, long totalMillis) throws IOException {
        LOGGER.debug("MP4 横屏视频启动: session={} quality={} source={}x{} fps={} offset={}ms title='{}'",
                session.key.sessionId(), stream.quality(), stream.sourceWidth(), stream.sourceHeight(), stream.fps(),
                elapsedMillis, stream.title());
        DecodeSize decodeSize = HandheldVideoFrameTimeline.chooseDecodeSize(stream.sourceWidth(), stream.sourceHeight());
        HandheldVideoFrameTimeline.maybeWarnHighResolution(decodeSize);
        LOGGER.debug("MP4 横屏视频解码尺寸: session={} source={}x{} target={}x{}", session.key.sessionId(),
                stream.sourceWidth(), stream.sourceHeight(), decodeSize.width(), decodeSize.height());
        Fmp4NativeVideoDecoder.OutputFormat outputFormat = session.key.rgbaFallback()
                ? Fmp4NativeVideoDecoder.OutputFormat.RGBA
                : Fmp4NativeVideoDecoder.OutputFormat.NV12;
        LOGGER.debug("MP4 横屏视频输出格式: session={} format={} irisShaderpackFallback={}",
                session.key.sessionId(), outputFormat, session.key.rgbaFallback());
        boolean firstFrameAccepted = false;
        Fmp4NativeVideoDecoder decoder = null;
        try {
            decoder = HandheldVideoDecoderFactory.open(session, stream, decodeSize,
                    outputFormat, elapsedMillis, totalMillis);
            if (!session.attachDecoder(decoder)) {
                return;
            }
            long displayedFrames = 0L;
            while (!session.closed.get() && session.key.equals(state.activeKey)) {
                if (!HandheldOffscreenVideoPolicy.waitWhileOffscreen(deviceId, state, session)) {
                    return;
                }
                boolean boundedAv1Probe = !firstFrameAccepted
                        && HandheldVideoDecoderFactory.requiresBoundedAv1FirstFrameProbe(stream);
                Fmp4NativeVideoDecoder.DecodedFrame decoded = boundedAv1Probe
                                ? decoder.getNextDecodedFrameWithAv1FirstFrameProbe()
                                : decoder.getNextDecodedFrame();
                if (decoded == null) {
                    if (firstFrameAccepted && session.performanceFallbackRequested.get()) {
                        throw new SustainedPerformanceFallbackException(session.pendingFallbackReason);
                    }
                    if (!firstFrameAccepted) {
                        throw new StartupDecodeException("候选在输出首帧前结束");
                    }
                    synchronized (state.lifecycleLock) {
                        if (state.activeSession == session && session.key.equals(state.activeKey)) {
                            state.endedKey = session.key;
                            state.statusText = "视频播放结束";
                        }
                    }
                    return;
                }
                int requiredBytes = HandheldVideoFrameTimeline.requiredFrameBytes(decoded.format(), decodeSize.width(), decodeSize.height());
                if (!HandheldVideoFrameTimeline.hasFrameBytes(decoded, requiredBytes)) {
                    try {
                        if (boundedAv1Probe) {
                            decoder.rejectAv1FirstFrameProbeFrame(decoded);
                        }
                    } finally {
                        decoded.close();
                    }
                    continue;
                }
                long framePtsNanos = HandheldVideoFrameTimeline.framePtsOrFallback(decoded.ptsNanos(), displayedFrames, stream.fps());
                if (!firstFrameAccepted && HandheldVideoFrameTimeline.shouldDropStaleStartupFrame(deviceId, state, session, framePtsNanos)) {
                    try {
                        if (boundedAv1Probe) {
                            decoder.rejectAv1FirstFrameProbeFrame(decoded);
                        }
                    } finally {
                        decoded.close();
                    }
                    displayedFrames++;
                    continue;
                }
                if (!HandheldVideoFrameTimeline.waitForDecodeLead(deviceId, state, session, framePtsNanos)) {
                    try {
                        if (boundedAv1Probe) {
                            decoder.rejectAv1FirstFrameProbeFrame(decoded);
                        }
                    } finally {
                        decoded.close();
                    }
                    return;
                }
                HandheldVideoFrame frame = HandheldVideoFrame.retain(decoded, requiredBytes, decodeSize.width(),
                        decodeSize.height(),
                        framePtsNanos);
                if (!HandheldVideoFrameTimeline.offerFrame(state, session, frame)) {
                    try {
                        if (boundedAv1Probe) {
                            decoder.rejectAv1FirstFrameProbeFrame(decoded);
                        }
                    } finally {
                        frame.close();
                    }
                    return;
                }
                if (!firstFrameAccepted) {
                    if (boundedAv1Probe) {
                        try {
                            decoder.commitAv1FirstFrameProbe(decoded);
                        } catch (IOException error) {
                            HandheldVideoFrameTimeline.clearFrameQueue(state);
                            throw error;
                        }
                    }
                    firstFrameAccepted = true;
                    session.startPerformanceObservation(stream, decoder, System.nanoTime());
                    session.performanceMonitor.recordDecodedFrame(
                            HandheldVideoDecoderFactory.preferredDecodeSampleNanos(decoded));
                    state.statusText = playingStatus(session, stream, decoder.actualHwaccel());
                    LOGGER.debug("MP4 横屏视频首帧已提交: session={} target={}x{} pts={}ms offset={}ms backend={}",
                            session.key.sessionId(), decodeSize.width(), decodeSize.height(),
                            framePtsNanos / 1_000_000L, elapsedMillis, decoder.actualHwaccel());
                } else {
                    session.performanceMonitor.recordDecodedFrame(
                            HandheldVideoDecoderFactory.preferredDecodeSampleNanos(decoded));
                }
                displayedFrames++;
                if (session.evaluatePerformance(state, System.nanoTime())) {
                    throw new SustainedPerformanceFallbackException(session.pendingFallbackReason);
                }
            }
        } catch (IOException failure) {
            if (firstFrameAccepted && session.performanceFallbackRequested.get()
                    && !(failure instanceof HandheldCandidateCloseTimeoutException)
                    && !(failure instanceof HandheldCandidateCloseFailureException)) {
                throw new SustainedPerformanceFallbackException(session.pendingFallbackReason, failure);
            }
            if (!firstFrameAccepted) {
                if (failure instanceof HandheldCandidateCloseTimeoutException
                        || failure instanceof HandheldCandidateCloseFailureException) {
                    throw failure;
                }
                throw failure instanceof StartupDecodeException startup
                        ? startup
                        : new StartupDecodeException(failure.getMessage(), failure);
            }
            throw failure;
        } catch (RuntimeException failure) {
            if (!firstFrameAccepted && !session.closed.get()) {
                throw new StartupDecodeException(failure.getMessage(), failure);
            }
            throw failure;
        } finally {
            try {
                if (decoder != null) {
                    HandheldVideoDecoderFactory.closeCandidate(decoder, firstFrameAccepted, stream, session,
                            session.performanceFallbackRequested.get());
                }
            } finally {
                session.detachDecoder(decoder);
            }
        }
    }

    private static String playingStatus(HandheldVideoSession session, ResolvedVideoStream stream, String backend) {
        return playingStatus(session, stream.quality(), stream.codecId(), backend);
    }

    static String playingStatus(HandheldVideoSession session, int actualQuality, int codecId, String backend) {
        String codec = codecId == 13 ? "AV1" : codecId == 7 ? "H.264" : "codec-" + codecId;
        String actual = backend == null || backend.isBlank() ? "unknown" : backend;
        String fallback = session.fallbackReason.isBlank() ? ""
                : " · 降级=" + VideoFallbackReason.userLabel(session.fallbackReason);
        return "视频播放中 · 请求Q" + session.key.quality() + " · 实际Q" + actualQuality
                + " " + codec + " · " + actual + fallback;
    }

    private static final class StartupDecodeException extends IOException {
        private StartupDecodeException(String message) {
            super(message);
        }

        private StartupDecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static void cancelResolveTaskLocked(HandheldDeviceVideoState state) {
        CancellableTaskFuture<ResolvedVideoStream> resolveTask = state.resolveTask;
        state.resolveTask = null;
        if (resolveTask != null) {
            resolveTask.cancel(true);
        }
    }

    private static HandheldDeviceVideoState state(UUID deviceId) {
        if (deviceId == null) {
            throw new IllegalArgumentException("MP4 video state requires a device id");
        }
        PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
        return STATES.computeIfAbsent(sourceId, ignored -> new HandheldDeviceVideoState(
                REPLACEMENT_GATES.computeIfAbsent(sourceId, key -> new HandheldReplacementGate())));
    }

    private static HandheldDeviceVideoState stateOrNull(UUID deviceId) {
        return deviceId != null ? STATES.get(PlaybackSourceId.of(deviceId)) : null;
    }

    public static boolean isDeviceInHotbar(UUID deviceId) {
        if (deviceId == null) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        return MP4DeviceStacks.forEachHotbarAndOffhand(minecraft.player,
                stack -> deviceId.equals(MP4Item.readDeviceId(stack)));
    }

    public static boolean isMp4DeviceProfile(UUID deviceId) {
        return profileFor(deviceId) == MP4_PROFILE;
    }

    static HandheldMediaDeviceProfile profileFor(UUID deviceId) {
        return profileFor(deviceId, null);
    }

    static HandheldMediaDeviceProfile profileFor(UUID deviceId, HandheldMediaDeviceProfile fallback) {
        HandheldMediaDeviceProfile profile = deviceId != null
                ? PROFILES.get(PlaybackSourceId.of(deviceId))
                : null;
        return profile != null ? profile : fallback != null ? fallback : MP4_PROFILE;
    }

    private static String currentLineAt(Int2ObjectSortedMap<String> lines, int tick) {
        if (lines == null || lines.isEmpty() || tick < 0) {
            return "";
        }
        int key = lines.firstIntKey();
        for (int candidate : lines.keySet().toIntArray()) {
            if (candidate > tick) {
                break;
            }
            key = candidate;
        }
        String line = lines.get(key);
        return line != null ? line : "";
    }

    public record DecodeSize(int width, int height) {
    }

}
