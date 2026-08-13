package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.PadDiagnosticsProperties;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.ResolvedVideoStream;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoFallbackReason;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoPerformanceFallbackPolicy;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoPerformanceMonitor;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoZombieCloseSupervisor;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelineView;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaDeviceProfile;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaRenderState;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoPipelineConfig;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
            new Mp4VideoThreadFactory());
    private static final Map<PlaybackSourceId, DeviceVideoState> STATES = new ConcurrentHashMap<>();
    private static final Map<PlaybackSourceId, HandheldMediaDeviceProfile> PROFILES = new ConcurrentHashMap<>();
    private static final Map<PlaybackSourceId, HandheldReplacementGate> REPLACEMENT_GATES = new ConcurrentHashMap<>();
    private static final AtomicBoolean highResolutionWarningShown = new AtomicBoolean(false);
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
        DeviceVideoState state = state(deviceId);
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
        PlaybackKey key = new PlaybackKey(playback.playbackSessionId(), playback.rawUrl(),
                renderState.videoQualityCeiling(), renderState.allowAiSubtitle(),
                shouldUseRgbaFallback() || hasActiveRgbaConsumer(state));
        long intentGeneration;
        synchronized (state.lifecycleLock) {
            VideoSession session = state.activeSession;
            if (key.equals(state.activeKey) && session != null && !session.closed.get()) {
                return pumpFrameForTimeline(state, session, anchoredVisualMillis(deviceId, activeProfile, playback));
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
            state.failedKey = PlaybackKey.EMPTY;
            state.endedKey = PlaybackKey.EMPTY;
            if (!BiliVideoStreamResolver.isStoredVideoSelection(playback.rawUrl())) {
                state.resolvingKey = PlaybackKey.EMPTY;
                state.failedKey = key;
                state.audioOnly = true;
                state.statusText = "纯音乐";
                state.sourceWidth = 0;
                state.sourceHeight = 0;
                clearFrameQueue(state);
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
        DeviceVideoState state = state(deviceId);
        long nowNs = System.nanoTime();
        long offscreenSince = state.offscreenSinceNanoTime;
        state.lastVisibleNanoTime = nowNs;
        state.offscreenSinceNanoTime = 0L;
        if (offscreenSince > 0L) {
            maybeRestartVisibleSession(deviceId, state, nowNs - offscreenSince);
        }
    }

    public static void requestRgbaOutput(UUID deviceId) {
        if (deviceId == null) {
            return;
        }
        DeviceVideoState state = state(deviceId);
        state.rgbaConsumerUntilNanoTime = System.nanoTime() + Math.max(0L, CONFIG.rgbaConsumerGraceNanos());
        markVisible(deviceId);
    }

    public static HandheldVideoFrame latestFrame(UUID deviceId) {
        DeviceVideoState state = stateOrNull(deviceId);
        return state != null ? state.latestFrame.get() : null;
    }

    public static HandheldVideoFrame acquireLatestFrame(UUID deviceId) {
        DeviceVideoState state = stateOrNull(deviceId);
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
        DeviceVideoState state = stateOrNull(deviceId);
        return state != null ? state.frameSequence.get() : -1L;
    }

    public static String statusText(UUID deviceId) {
        DeviceVideoState state = stateOrNull(deviceId);
        return state != null ? state.statusText : "等待设备 ID";
    }

    public static boolean audioOnly(UUID deviceId) {
        DeviceVideoState state = stateOrNull(deviceId);
        return state != null && state.audioOnly;
    }

    public static String currentResolutionLabel(UUID deviceId) {
        DeviceVideoState state = stateOrNull(deviceId);
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
            DecodeSize preview = chooseDecodeSize(width, height);
            return preview.width() + "x" + preview.height();
        }
        return "";
    }

    public static String currentSubtitle(UUID deviceId) {
        DeviceVideoState state = stateOrNull(deviceId);
        if (state == null) {
            return "";
        }
        LyricRecord record = state.subtitleRecord;
        if (record == null) {
            return state.currentSubtitle != null ? state.currentSubtitle : "";
        }
        HandheldMediaDeviceProfile profile = profileFor(deviceId);
        HandheldMediaPlayback playback = profile.playback(deviceId);
        long visualMillis = anchoredVisualMillis(deviceId, profile, playback);
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
        DeviceVideoState state = stateOrNull(deviceId);
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

    private static void stop(DeviceVideoState state, String reason) {
        synchronized (state.lifecycleLock) {
            stopLocked(state, reason);
        }
    }

    private static void stopLocked(DeviceVideoState state, String reason) {
        state.intentGeneration++;
        cancelResolveTaskLocked(state);
        state.activeKey = PlaybackKey.EMPTY;
        state.resolvingKey = PlaybackKey.EMPTY;
        state.failedKey = PlaybackKey.EMPTY;
        state.endedKey = PlaybackKey.EMPTY;
        VideoSession session = state.activeSession;
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
        clearFrameQueue(state);
        HandheldVideoFrame latest = state.latestFrame.getAndSet(null);
        if (latest != null) {
            latest.close();
            state.frameSequence.incrementAndGet();
        }
    }

    private static void stopForNativeUnavailable(DeviceVideoState state, HandheldMediaPlayback playback) {
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

    private static void waitForAudioStart(DeviceVideoState state) {
        synchronized (state.lifecycleLock) {
            state.intentGeneration++;
            cancelResolveTaskLocked(state);
            state.statusText = "等待音频缓冲...";
            state.audioOnly = false;
            VideoSession session = state.activeSession;
            if (session != null) {
                session.close();
                state.activeSession = null;
            }
            state.activeKey = PlaybackKey.EMPTY;
            state.resolvingKey = PlaybackKey.EMPTY;
            state.failedKey = PlaybackKey.EMPTY;
            state.endedKey = PlaybackKey.EMPTY;
        }
        clearFrameQueue(state);
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
        for (Map.Entry<PlaybackSourceId, DeviceVideoState> entry : STATES.entrySet()) {
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
        tickHotbarVideoSessions();
        for (Map.Entry<PlaybackSourceId, DeviceVideoState> entry : STATES.entrySet()) {
            UUID deviceId = entry.getKey().value();
            HandheldMediaDeviceProfile profile = profileFor(deviceId);
            if (!profile.isDeviceAvailable(deviceId)) {
                continue;
            }
            DeviceVideoState state = entry.getValue();
            VideoSession session = state.activeSession;
            if (session == null || session.closed.get() || !session.key.equals(state.activeKey)) {
                continue;
            }
            HandheldMediaPlayback playback = profile.playback(deviceId);
            if (playback == null || !session.key.playbackSessionId().equals(playback.playbackSessionId())) {
                continue;
            }
            pumpFrameForTimeline(state, session, anchoredVisualMillis(deviceId, profile, playback));
        }
    }

    private static void tickHotbarVideoSessions() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        MP4DeviceStacks.forEachHotbarAndOffhand(minecraft.player, stack -> {
            tickStackVideoSession(stack);
            return false;
        });
    }

    private static void tickStackVideoSession(ItemStack stack) {
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

    private static void resolveAndStart(UUID deviceId, DeviceVideoState state,
            HandheldMediaPlayback playback,
            PlaybackKey key, long intentGeneration) {
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
                            state.resolvingKey = PlaybackKey.EMPTY;
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
                                state.resolvingKey = PlaybackKey.EMPTY;
                            }
                        }
                        return;
                    }
                    startDecoder(deviceId, state, currentPlayback, key, stream, intentGeneration);
                });
    }

    private static boolean isCurrentIntent(UUID deviceId, DeviceVideoState state,
            PlaybackKey key, long intentGeneration) {
        return deviceId != null
                && state != null
                && STATES.get(PlaybackSourceId.of(deviceId)) == state
                && state.intentGeneration == intentGeneration
                && key.equals(state.activeKey)
                && key.equals(state.resolvingKey);
    }

    private static boolean isCurrentPlayback(HandheldMediaPlayback playback, PlaybackKey key) {
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

    private static void startDecoder(UUID deviceId, DeviceVideoState state,
            HandheldMediaPlayback playback, PlaybackKey key,
            ResolvedVideoStream stream, long intentGeneration) {
        if (!com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection.allowMediaStart()) {
            synchronized (state.lifecycleLock) {
                if (isCurrentIntent(deviceId, state, key, intentGeneration)) {
                    state.resolvingKey = PlaybackKey.EMPTY;
                    state.statusText = "视频内存保护冷却中";
                }
            }
            return;
        }
        long elapsedMillis = Math.max(0L, playback.timeline().mediaMillis());
        long totalMillis = Math.max(0L, playback.timeline().totalMillis());
        VideoSession session;
        synchronized (state.replacementGate) {
            synchronized (state.lifecycleLock) {
                if (!isCurrentIntent(deviceId, state, key, intentGeneration)) {
                    return;
                }

                HandheldReplacementGate.Signals previousSignals = state.replacementGate.snapshot();
                HandheldDecoderAdmissionPolicy.Decision admission = HandheldDecoderAdmissionPolicy.decide(
                        previousSignals.decodeExit(), previousSignals.nativeTermination());
                if (admission == HandheldDecoderAdmissionPolicy.Decision.FAIL_CLOSED) {
                    state.resolvingKey = PlaybackKey.EMPTY;
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

                session = new VideoSession(state, key, elapsedMillis, stream.candidates());
                state.activeSession = session;
                state.replacementGate.install(key.sessionId(), session.decodeExit, session.physicalTermination);
                state.resolvingKey = PlaybackKey.EMPTY;
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

    private static void waitForPreviousDecoderExit(UUID deviceId, DeviceVideoState state,
            PlaybackKey key, ResolvedVideoStream stream, long intentGeneration,
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

    private static void resumeDecoderAfterPreviousExit(UUID deviceId, DeviceVideoState state,
            PlaybackKey key, ResolvedVideoStream stream, long intentGeneration,
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
                    state.resolvingKey = PlaybackKey.EMPTY;
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
                    state.resolvingKey = PlaybackKey.EMPTY;
                }
            }
            return;
        }
        startDecoder(deviceId, state, currentPlayback, key, stream, intentGeneration);
    }

    private static void completeDecoderTask(DeviceVideoState state, VideoSession session,
            PlaybackKey key, ResolvedVideoStream stream, Throwable error) {
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

    private static void decodeLoop(UUID deviceId, DeviceVideoState state, VideoSession session,
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
                clearFrameQueue(state);
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

    private static void decodeCandidate(UUID deviceId, DeviceVideoState state, VideoSession session,
            ResolvedVideoStream stream, long elapsedMillis, long totalMillis) throws IOException {
        LOGGER.debug("MP4 横屏视频启动: session={} quality={} source={}x{} fps={} offset={}ms title='{}'",
                session.key.sessionId(), stream.quality(), stream.sourceWidth(), stream.sourceHeight(), stream.fps(),
                elapsedMillis, stream.title());
        DecodeSize decodeSize = chooseDecodeSize(stream.sourceWidth(), stream.sourceHeight());
        maybeWarnHighResolution(decodeSize);
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
            decoder = openNativeDecoder(session, stream, decodeSize,
                    outputFormat, elapsedMillis, totalMillis);
            if (!session.attachDecoder(decoder)) {
                return;
            }
            long displayedFrames = 0L;
            while (!session.closed.get() && session.key.equals(state.activeKey)) {
                if (!waitWhileOffscreen(deviceId, state, session)) {
                    return;
                }
                boolean boundedAv1Probe = !firstFrameAccepted && requiresBoundedAv1FirstFrameProbe(stream);
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
                int requiredBytes = requiredFrameBytes(decoded.format(), decodeSize.width(), decodeSize.height());
                if (!hasFrameBytes(decoded, requiredBytes)) {
                    try {
                        if (boundedAv1Probe) {
                            decoder.rejectAv1FirstFrameProbeFrame(decoded);
                        }
                    } finally {
                        decoded.close();
                    }
                    continue;
                }
                long framePtsNanos = framePtsOrFallback(decoded.ptsNanos(), displayedFrames, stream.fps());
                if (!firstFrameAccepted && shouldDropStaleStartupFrame(deviceId, state, session, framePtsNanos)) {
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
                if (!waitForDecodeLead(deviceId, state, session, framePtsNanos)) {
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
                if (!offerFrame(state, session, frame)) {
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
                            clearFrameQueue(state);
                            throw error;
                        }
                    }
                    firstFrameAccepted = true;
                    session.startPerformanceObservation(stream, decoder, System.nanoTime());
                    session.performanceMonitor.recordDecodedFrame(preferredDecodeSampleNanos(decoded));
                    state.statusText = playingStatus(session, stream, decoder.actualHwaccel());
                    LOGGER.debug("MP4 横屏视频首帧已提交: session={} target={}x{} pts={}ms offset={}ms backend={}",
                            session.key.sessionId(), decodeSize.width(), decodeSize.height(),
                            framePtsNanos / 1_000_000L, elapsedMillis, decoder.actualHwaccel());
                } else {
                    session.performanceMonitor.recordDecodedFrame(preferredDecodeSampleNanos(decoded));
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
                    closeHandheldCandidate(decoder, firstFrameAccepted, stream, session,
                            session.performanceFallbackRequested.get());
                }
            } finally {
                session.detachDecoder(decoder);
            }
        }
    }

    private static boolean requiresBoundedAv1FirstFrameProbe(ResolvedVideoStream stream) {
        return stream.codecId() == 13
                && stream.decodeMode() == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED;
    }

    private static void closeHandheldCandidate(Fmp4NativeVideoDecoder decoder, boolean firstFrameAccepted,
            ResolvedVideoStream stream, VideoSession session, boolean requireConvergenceBarrier) throws IOException {
        if (firstFrameAccepted && !requireConvergenceBarrier) {
            decoder.close();
            return;
        }
        long closeStartedNanos = System.nanoTime();
        CompletableFuture<Void> nativeTermination = decoder.terminationFuture();
        long closeOperation = VideoCloseDiagnostics.global().begin(session.key.sessionId(), EnumSet.of(
                VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED,
                VideoCloseDiagnostics.Phase.NATIVE_TERMINATED), closeStartedNanos);
        nativeTermination.whenComplete((ignored, error) -> {
            if (error == null) {
                VideoCloseDiagnostics.global().complete(closeOperation,
                        VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, System.nanoTime());
            }
        });
        decoder.requestClose();
        RuntimeException closeFailure = null;
        try {
            decoder.close();
        } catch (RuntimeException error) {
            closeFailure = error;
        } finally {
            VideoCloseDiagnostics.global().complete(closeOperation,
                    VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED, System.nanoTime());
        }
        if (HandheldDecoderAdmissionPolicy.completedNormally(nativeTermination)) {
            if (closeFailure != null) {
                throw closeFailure;
            }
            return;
        }
        if (nativeTermination.isDone()) {
            trackHandheldZombie(session, closeOperation, nativeTermination);
            throw new HandheldCandidateCloseFailureException(stream, nativeTermination,
                    "native termination completed exceptionally");
        }
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(CANDIDATE_CLOSE_TIMEOUT_MILLIS)
                - Math.max(0L, System.nanoTime() - closeStartedNanos);
        if (remainingNanos <= 0L) {
            trackHandheldZombie(session, closeOperation, nativeTermination);
            throw new HandheldCandidateCloseTimeoutException(stream, nativeTermination);
        }
        try {
            nativeTermination.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            trackHandheldZombie(session, closeOperation, nativeTermination);
            throw new HandheldCandidateCloseTimeoutException(stream, nativeTermination);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            trackHandheldZombie(session, closeOperation, nativeTermination);
            throw new HandheldCandidateCloseFailureException(stream, nativeTermination,
                    "close barrier interrupted", error);
        } catch (java.util.concurrent.ExecutionException error) {
            trackHandheldZombie(session, closeOperation, nativeTermination);
            throw new HandheldCandidateCloseFailureException(stream, nativeTermination,
                    "native termination completed exceptionally", error.getCause());
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private static long preferredDecodeSampleNanos(Fmp4NativeVideoDecoder.DecodedFrame frame) {
        if (frame == null) {
            return 0L;
        }
        return frame.nativeGetNanos() >= 0L ? frame.nativeGetNanos()
                : Math.max(0L, frame.queueWaitNanos());
    }

    private static String playingStatus(VideoSession session, ResolvedVideoStream stream, String backend) {
        return playingStatus(session, stream.quality(), stream.codecId(), backend);
    }

    private static String playingStatus(VideoSession session, int actualQuality, int codecId, String backend) {
        String codec = codecId == 13 ? "AV1" : codecId == 7 ? "H.264" : "codec-" + codecId;
        String actual = backend == null || backend.isBlank() ? "unknown" : backend;
        String fallback = session.fallbackReason.isBlank() ? ""
                : " · 降级=" + VideoFallbackReason.userLabel(session.fallbackReason);
        return "视频播放中 · 请求Q" + session.key.quality() + " · 实际Q" + actualQuality
                + " " + codec + " · " + actual + fallback;
    }

    private static void trackHandheldZombie(VideoSession session, long closeOperation,
            CompletableFuture<Void> nativeTermination) {
        VideoZombieCloseSupervisor.global().track(session.key.sessionId(), closeOperation,
                CompletableFuture.completedFuture(null), nativeTermination, session.decodeExit);
    }

    private static final class HandheldCandidateCloseTimeoutException extends IOException {
        private final CompletableFuture<Void> nativeTermination;

        private HandheldCandidateCloseTimeoutException(ResolvedVideoStream stream,
                CompletableFuture<Void> nativeTermination) {
            super("旧手持视频候选 native worker 未在关闭预算内退出: quality=" + stream.quality()
                    + ", codec=" + stream.codecId());
            this.nativeTermination = nativeTermination;
        }
    }

    private static final class HandheldCandidateCloseFailureException extends IOException {
        private final CompletableFuture<Void> nativeTermination;

        private HandheldCandidateCloseFailureException(ResolvedVideoStream stream,
                CompletableFuture<Void> nativeTermination, String reason) {
            this(stream, nativeTermination, reason, null);
        }

        private HandheldCandidateCloseFailureException(ResolvedVideoStream stream,
                CompletableFuture<Void> nativeTermination, String reason, Throwable cause) {
            super("旧手持视频候选 native 关闭失败: quality=" + stream.quality()
                    + ", codec=" + stream.codecId() + ", reason=" + reason, cause);
            this.nativeTermination = nativeTermination;
        }
    }

    private static final class StartupDecodeException extends IOException {
        private StartupDecodeException(String message) {
            super(message);
        }

        private StartupDecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static Fmp4NativeVideoDecoder openNativeDecoder(VideoSession session, ResolvedVideoStream stream,
            DecodeSize decodeSize, Fmp4NativeVideoDecoder.OutputFormat outputFormat, long elapsedMillis,
            long totalMillis) throws IOException {
        String sessionId = session.key.sessionId();
        IOException last = null;
        for (String hwaccel : handheldHwaccelCandidates(sessionId, stream.decodeMode())) {
            Fmp4NativeVideoDecoder opened = null;
            try {
                if (PAD_VIDEO_DEBUG_LOG && PadClientMediaSessionIds.isPadSession(sessionId)) {
                    LOGGER.info(
                            "Pad video native decoder open: session={} hwaccel={} codec={} target={}x{} format={} offset={}ms",
                            sessionId, hwaccel, stream.codecId(), decodeSize.width(), decodeSize.height(),
                            outputFormat, elapsedMillis);
                }
                opened = new Fmp4NativeVideoDecoder(
                        stream.url(), stream.codecId(), decodeSize.width(),
                        decodeSize.height(), CONFIG.maxFrames(), true, outputFormat, hwaccel,
                        elapsedMillis, totalMillis, stream.fps());
                session.trackNative(opened.terminationFuture());
                if (stream.decodeMode() == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED
                        && !opened.isHardwareAccelerated()) {
                    String actualHwaccel = opened.actualHwaccel();
                    closeRejectedHandheldDecoder(session, stream, opened,
                            "rejected hardware backend did not terminate normally");
                    throw new IOException("候选要求硬件解码但 native backend 未启用硬解: requested="
                            + hwaccel + ", actual=" + actualHwaccel);
                }
                return opened;
            } catch (HandheldCandidateCloseFailureException failure) {
                throw failure;
            } catch (IOException e) {
                last = e;
                LOGGER.warn("MP4 横屏 native 解码器启动失败 hwaccel={}，尝试下一个候选: {}", hwaccel, e.toString());
            } catch (RuntimeException error) {
                if (opened != null) {
                    closeRejectedHandheldDecoder(session, stream, opened,
                            "decoder validation failed and close did not terminate normally");
                }
                throw error;
            }
        }
        throw last != null ? last : new IOException("Native handheld video decoder unavailable");
    }

    private static void closeRejectedHandheldDecoder(VideoSession session, ResolvedVideoStream stream,
            Fmp4NativeVideoDecoder decoder, String failureReason) throws HandheldCandidateCloseFailureException {
        CompletableFuture<Void> termination = decoder.terminationFuture();
        decoder.close();
        if (!HandheldDecoderAdmissionPolicy.completedNormally(termination)) {
            trackHandheldZombie(session, HANDHELD_CLOSE_SEQUENCE.incrementAndGet(), termination);
            throw new HandheldCandidateCloseFailureException(stream, termination, failureReason);
        }
    }

    private static String[] handheldHwaccelCandidates(String sessionId,
            BiliVideoStreamResolver.DecodeMode decodeMode) {
        if (decodeMode == BiliVideoStreamResolver.DecodeMode.SOFTWARE_ONLY) {
            return new String[] { "none" };
        }
        String requested = PadClientMediaSessionIds.isPadSession(sessionId)
                ? VIDEO_PROPERTIES.padNativeHwaccel()
                : VIDEO_PROPERTIES.nativeHwaccel();
        if (requested.isBlank()
                || "none".equalsIgnoreCase(requested)
                || "off".equalsIgnoreCase(requested)) {
            return new String[] { "none" };
        }
        if ("auto".equalsIgnoreCase(requested)) {
            String[] candidates = VideoFeatureFlags.requestedHwaccelCandidates();
            return decodeMode == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED
                    ? java.util.Arrays.stream(candidates).filter(value -> !"none".equalsIgnoreCase(value))
                            .toArray(String[]::new)
                    : candidates;
        }
        return decodeMode == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED
                ? new String[] { requested }
                : new String[] { requested, "none" };
    }

    private static boolean waitWhileOffscreen(UUID deviceId, DeviceVideoState state, VideoSession session) {
        if (!CONFIG.offscreenPauseDecode() || !isOffscreenPauseActive(state)) {
            return !session.closed.get() && session.key.equals(state.activeKey);
        }
        long pauseStartNs = System.nanoTime();
        session.performanceMonitor.pause(pauseStartNs);
        while (!session.closed.get() && session.key.equals(state.activeKey) && isOffscreenPauseActive(state)) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        long pausedNs = System.nanoTime() - pauseStartNs;
        session.performanceMonitor.resume(System.nanoTime());
        if (pausedNs > 0L) {
            LOGGER.debug("MP4 横屏视频离屏恢复取帧: device={}, session={}, paused={}ms", deviceId,
                    session.key.sessionId(), pausedNs / 1_000_000L);
        }
        return !session.closed.get() && session.key.equals(state.activeKey);
    }

    private static boolean isOffscreenPauseActive(DeviceVideoState state) {
        long lastVisible = state.lastVisibleNanoTime;
        if (lastVisible <= 0L) {
            lastVisible = state.lastVisibleNanoTime = System.nanoTime();
            return false;
        }
        long nowNs = System.nanoTime();
        boolean paused = nowNs - lastVisible > Math.max(0L, CONFIG.offscreenGraceNanos());
        if (paused && state.offscreenSinceNanoTime == 0L) {
            state.offscreenSinceNanoTime = nowNs;
        }
        return paused;
    }

    private static void maybeRestartVisibleSession(UUID deviceId, DeviceVideoState state, long offscreenDurationNs) {
        VideoSession session = state.activeSession;
        if (session == null || session.closed.get() || CONFIG.offscreenResumeRestartLagNanos() <= 0L) {
            return;
        }
        HandheldMediaDeviceProfile profile = profileFor(deviceId);
        HandheldMediaPlayback playback = profile.playback(deviceId);
        if (playback == null || !session.key.playbackSessionId().equals(playback.playbackSessionId())) {
            return;
        }
        long visualMillis = anchoredVisualMillis(deviceId, profile, playback);
        long latestMillis = latestFrameMillis(state, session);
        long lagNs = latestMillis >= 0L ? (visualMillis - latestMillis) * 1_000_000L : offscreenDurationNs;
        if (visualMillis < 0L || lagNs < CONFIG.offscreenResumeRestartLagNanos()) {
            return;
        }
        LOGGER.debug("MP4 横屏视频离屏恢复重定位: device={}, session={}, offscreen={}ms, visual={}ms, latest={}ms",
                deviceId, session.key.sessionId(), offscreenDurationNs / 1_000_000L, visualMillis, latestMillis);
        stopForVisibleResync(state, "视频重新同步...");
        update(deviceId);
    }

    private static void stopForVisibleResync(DeviceVideoState state, String reason) {
        synchronized (state.lifecycleLock) {
            state.intentGeneration++;
            cancelResolveTaskLocked(state);
            state.activeKey = PlaybackKey.EMPTY;
            state.resolvingKey = PlaybackKey.EMPTY;
            state.failedKey = PlaybackKey.EMPTY;
            state.endedKey = PlaybackKey.EMPTY;
            VideoSession session = state.activeSession;
            state.activeSession = null;
            if (session != null) {
                session.close();
            }
            if (reason != null && !reason.isBlank()) {
                state.statusText = reason;
            }
        }
        clearFrameQueue(state);
    }

    private static void cancelResolveTaskLocked(DeviceVideoState state) {
        CancellableTaskFuture<ResolvedVideoStream> resolveTask = state.resolveTask;
        state.resolveTask = null;
        if (resolveTask != null) {
            resolveTask.cancel(true);
        }
    }

    private static long latestFrameMillis(DeviceVideoState state, VideoSession session) {
        long latestPts = -1L;
        HandheldVideoFrame latest = state.latestFrame.get();
        if (latest != null) {
            latestPts = Math.max(latestPts, latest.ptsNanos());
        }
        synchronized (state.frameQueueLock) {
            for (HandheldVideoFrame frame : state.frameQueue) {
                latestPts = Math.max(latestPts, frame.ptsNanos());
            }
        }
        return latestPts >= 0L ? session.decoderStartOffsetMillis + latestPts / 1_000_000L : -1L;
    }

    private static boolean hasFrameBytes(Fmp4NativeVideoDecoder.DecodedFrame decoded, int requiredBytes) {
        ByteBuffer buffer = decoded.buffer();
        if (buffer != null) {
            return decoded.byteLength() >= requiredBytes && buffer.remaining() >= requiredBytes;
        }
        byte[] data = decoded.data();
        return data != null && data.length >= requiredBytes;
    }

    private static long framePtsOrFallback(long decodedPtsNanos, long frameIndex, int fps) {
        if (decodedPtsNanos >= 0L) {
            return decodedPtsNanos;
        }
        int safeFps = Math.max(1, fps);
        return Math.max(0L, Math.round(frameIndex * 1_000_000_000.0D / safeFps));
    }

    private static int requiredFrameBytes(Fmp4NativeVideoDecoder.DecodedFrame.Format format, int width, int height) {
        int pixels = Math.max(1, width) * Math.max(1, height);
        return switch (format) {
            case NV12 -> pixels + pixels / 2;
            case YUV420P -> pixels + pixels / 2;
            case RGBA -> pixels * 4;
        };
    }

    private static boolean shouldUseRgbaFallback() {
        return IrisShaderpackCompat.isShaderPackInUse();
    }

    private static boolean hasActiveRgbaConsumer(DeviceVideoState state) {
        return state != null && System.nanoTime() <= state.rgbaConsumerUntilNanoTime;
    }

    private static DecodeSize chooseDecodeSize(int sourceWidth, int sourceHeight) {
        int safeSourceWidth = Math.max(2, sourceWidth);
        int safeSourceHeight = Math.max(2, sourceHeight);
        int maxWidth = CONFIG.maxAllowedWidth();
        int maxHeight = CONFIG.maxAllowedHeight();
        double scale = Math.min(1.0D, Math.min(maxWidth / (double) safeSourceWidth,
                maxHeight / (double) safeSourceHeight));
        int width = evenAtLeastTwo((int) Math.round(safeSourceWidth * scale));
        int height = evenAtLeastTwo((int) Math.round(safeSourceHeight * scale));
        if (width > maxWidth) {
            width = evenAtLeastTwo(maxWidth);
            height = evenAtLeastTwo((int) Math.round(width * safeSourceHeight / (double) safeSourceWidth));
        }
        if (height > maxHeight) {
            height = evenAtLeastTwo(maxHeight);
            width = evenAtLeastTwo((int) Math.round(height * safeSourceWidth / (double) safeSourceHeight));
        }
        return new DecodeSize(width, height);
    }

    private static void maybeWarnHighResolution(DecodeSize decodeSize) {
        if (decodeSize.width() <= CONFIG.highResWarningWidth()
                && decodeSize.height() <= CONFIG.highResWarningHeight()) {
            return;
        }
        if (!highResolutionWarningShown.compareAndSet(false, true)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        minecraft.player.sendSystemMessage(Component.translatable(
                "message.net_music_can_play_bili.mp4.high_resolution_warning",
                decodeSize.width(), decodeSize.height()));
    }

    private static int evenAtLeastTwo(int value) {
        int safe = Math.max(2, value);
        return (safe & 1) == 0 ? safe : safe - 1;
    }

    private static boolean waitForDecodeLead(UUID deviceId, DeviceVideoState state, VideoSession session,
            long framePtsNanos) {
        long targetNanos = Math.max(0L, framePtsNanos);
        while (!session.closed.get() && session.key.equals(state.activeKey)) {
            HandheldMediaDeviceProfile profile = profileFor(deviceId);
            HandheldMediaPlayback playback = profile.playback(deviceId);
            if (playback == null || !session.key.playbackSessionId().equals(playback.playbackSessionId())) {
                return false;
            }
            long visualMillis = anchoredVisualMillis(deviceId, profile, playback);
            long visualNanos = sessionRelativeVisualNanos(session, visualMillis);
            long leadNanos = targetNanos - visualNanos;
            if (leadNanos <= CONFIG.maxDecodeLeadNanos()) {
                return true;
            }
            pumpFrameForTimeline(state, session, visualMillis);
            long sleepMillis = Math.min(CONFIG.frameWaitSliceMillis(),
                    Math.max(1L, (leadNanos - CONFIG.maxDecodeLeadNanos()) / 1_000_000L));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean shouldDropStaleStartupFrame(UUID deviceId, DeviceVideoState state, VideoSession session,
            long framePtsNanos) {
        if (CONFIG.startupDropLagNanos() <= 0L) {
            return false;
        }
        HandheldMediaDeviceProfile profile = profileFor(deviceId);
        HandheldMediaPlayback playback = profile.playback(deviceId);
        long visualNanos = sessionRelativeVisualNanos(session, anchoredVisualMillis(deviceId, profile, playback));
        boolean drop = visualNanos - Math.max(0L, framePtsNanos) > CONFIG.startupDropLagNanos();
        return drop && frameQueueEmpty(state);
    }

    private static boolean offerFrame(DeviceVideoState state, VideoSession session, HandheldVideoFrame frame) {
        synchronized (state.frameQueueLock) {
            while (!session.closed.get() && session.key.equals(state.activeKey)
                    && state.frameQueue.size() >= Math.max(1, CONFIG.frameQueueCapacity())) {
                try {
                    state.frameQueueLock.wait(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (session.closed.get() || !session.key.equals(state.activeKey)) {
                return false;
            }
            state.frameQueue.addLast(frame);
            state.frameQueueLock.notifyAll();
            return true;
        }
    }

    private static boolean frameQueueEmpty(DeviceVideoState state) {
        synchronized (state.frameQueueLock) {
            return state.frameQueue.isEmpty();
        }
    }

    private static void clearFrameQueue(DeviceVideoState state) {
        synchronized (state.frameQueueLock) {
            for (HandheldVideoFrame frame : state.frameQueue) {
                frame.close();
            }
            state.frameQueue.clear();
            state.frameQueueLock.notifyAll();
        }
    }

    private static long sessionRelativeVisualNanos(VideoSession session, long visualMillis) {
        long relativeMillis = Math.max(0L, visualMillis - session.decoderStartOffsetMillis);
        return relativeMillis * 1_000_000L;
    }

    private static long anchoredVisualMillis(UUID deviceId, HandheldMediaDeviceProfile profile,
            HandheldMediaPlayback playback) {
        if (playback == null || playback.timeline() == null) {
            return -1L;
        }
        return ClientMediaTimelineView.forHandheldOwner(deviceId, playback,
                profileFor(deviceId, profile).hasStartedSound(deviceId, playback.sessionId()),
                playback.timeline().visualMillis(),
                playback.timeline().totalMillis()).visualMillis();
    }

    private static boolean pumpFrameForTimeline(DeviceVideoState state, VideoSession session, long visualMillis) {
        long visualNanos = sessionRelativeVisualNanos(session, visualMillis);
        HandheldVideoFrame selected = null;
        long droppedFrames = 0L;
        synchronized (state.frameQueueLock) {
            while (!state.frameQueue.isEmpty()) {
                HandheldVideoFrame first = state.frameQueue.peekFirst();
                if (first.ptsNanos() > visualNanos + CONFIG.earlyToleranceNanos() && selected == null) {
                    break;
                }
                HandheldVideoFrame candidate = state.frameQueue.pollFirst();
                if (candidate.ptsNanos() <= visualNanos + CONFIG.earlyToleranceNanos()) {
                    if (selected != null) {
                        selected.close();
                        droppedFrames++;
                    }
                    selected = candidate;
                    continue;
                }
                state.frameQueue.addFirst(candidate);
                break;
            }
            while (state.frameQueue.size() > 1
                    && visualNanos - state.frameQueue.peekFirst().ptsNanos() > CONFIG.maxLateFrameNanos()) {
                if (selected != null) {
                    selected.close();
                    droppedFrames++;
                }
                selected = state.frameQueue.pollFirst();
            }
        }
        session.performanceMonitor.recordDroppedFrames(droppedFrames);
        if (selected != null) {
            HandheldVideoFrame previous = state.latestFrame.getAndSet(selected);
            if (previous != null) {
                previous.close();
            }
            state.frameSequence.incrementAndGet();
            synchronized (state.frameQueueLock) {
                state.frameQueueLock.notifyAll();
            }
            session.observeTimelineAndEvaluate(state, visualMillis);
            return true;
        }
        if (session.performanceMonitor.started() && frameQueueEmpty(state)) {
            session.performanceMonitor.recordStarvation();
        }
        session.observeTimelineAndEvaluate(state, visualMillis);
        return false;
    }

    private static DeviceVideoState state(UUID deviceId) {
        if (deviceId == null) {
            throw new IllegalArgumentException("MP4 video state requires a device id");
        }
        PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
        return STATES.computeIfAbsent(sourceId, ignored -> new DeviceVideoState(
                REPLACEMENT_GATES.computeIfAbsent(sourceId, key -> new HandheldReplacementGate())));
    }

    private static DeviceVideoState stateOrNull(UUID deviceId) {
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

    private static HandheldMediaDeviceProfile profileFor(UUID deviceId) {
        return profileFor(deviceId, null);
    }

    private static HandheldMediaDeviceProfile profileFor(UUID deviceId, HandheldMediaDeviceProfile fallback) {
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

    private record PlaybackKey(Optional<PlaybackSessionId> playbackSessionId, String rawUrl, int quality,
            boolean allowAiSubtitle,
            boolean rgbaFallback) {
        static final PlaybackKey EMPTY = new PlaybackKey(Optional.empty(), "", 0, false, false);

        private PlaybackKey {
            playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
        }

        String sessionId() {
            return playbackSessionId.map(PlaybackSessionId::value).orElse("");
        }
    }

    public record DecodeSize(int width, int height) {
    }

    private static final class VideoSession implements AutoCloseable {
        private final DeviceVideoState owner;
        private final PlaybackKey key;
        private final long decoderStartOffsetMillis;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<Fmp4NativeVideoDecoder> decoder = new AtomicReference<>();
        private final CompletableFuture<Void> decodeExit = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<Void>> latestNativeTermination = new AtomicReference<>(
                CompletableFuture.completedFuture(null));
        private final CompletableFuture<Void> physicalTermination = new CompletableFuture<>();
        private final VideoPerformanceMonitor performanceMonitor = new VideoPerformanceMonitor();
        private final boolean h264CandidateAvailable;
        private final AtomicBoolean performanceFallbackRequested = new AtomicBoolean(false);
        private volatile boolean performanceFallbackLocked;
        private volatile int activeCodecId;
        private volatile int actualQuality;
        private volatile String actualBackend = "unknown";
        private volatile String fallbackReason = "";
        private volatile String pendingFallbackReason = "performance";
        private volatile boolean performanceNoH264Notified;

        private VideoSession(DeviceVideoState owner, PlaybackKey key, long decoderStartOffsetMillis,
                java.util.List<BiliVideoStreamResolver.VideoCandidate> candidates) {
            this.owner = Objects.requireNonNull(owner);
            this.key = Objects.requireNonNull(key);
            this.decoderStartOffsetMillis = Math.max(0L, decoderStartOffsetMillis);
            this.h264CandidateAvailable = candidates != null
                    && candidates.stream().anyMatch(candidate -> candidate.codecId() == 7);
            if (h264CandidateAvailable && candidates.stream().noneMatch(candidate -> candidate.codecId() == 13)) {
                fallbackReason = VideoFallbackReason.NO_AV1_STREAM;
            }
        }

        private void startPerformanceObservation(ResolvedVideoStream stream,
                Fmp4NativeVideoDecoder decoder, long nowNanos) {
            activeCodecId = stream.codecId();
            actualQuality = stream.quality();
            actualBackend = decoder != null && decoder.actualHwaccel() != null
                    ? decoder.actualHwaccel() : "unknown";
            performanceMonitor.start(nowNanos, stream.fps(), actualBackend);
        }

        private boolean evaluatePerformance(DeviceVideoState state, long nowNanos) {
            performanceMonitor.sampleNativeResources(nowNanos);
            VideoPerformanceFallbackPolicy.Snapshot snapshot = performanceMonitor.snapshot(nowNanos);
            VideoPerformanceFallbackPolicy.Decision decision = VideoPerformanceFallbackPolicy.decide(
                    snapshot, activeCodecId == 13, h264CandidateAvailable, performanceFallbackLocked);
            if (decision == VideoPerformanceFallbackPolicy.Decision.KEEP_NO_H264
                    && !performanceNoH264Notified) {
                performanceNoH264Notified = true;
                fallbackReason = VideoFallbackReason.NO_H264_CANDIDATE;
                state.statusText = playingStatus(this, actualQuality, activeCodecId, actualBackend);
                LOGGER.warn("MP4 横屏 AV1 性能超预算但同次 playurl 无 H.264 候选: session={} backend={}",
                        key.sessionId(), actualBackend);
            }
            if (!decision.shouldFallback() || !performanceFallbackRequested.compareAndSet(false, true)) {
                return false;
            }
            pendingFallbackReason = decision.reason();
            state.statusText = "AV1 性能不足，切换 H.264...";
            Fmp4NativeVideoDecoder attached = decoder.get();
            if (attached != null) {
                attached.requestClose();
            }
            LOGGER.warn(
                    "MP4 横屏 AV1 性能预算触发: session={} reason={} backend={} actualFps={}/{} avg={}ms p95={}ms starvation={} dropped={} driftGrowth={}ms nativePeak={} surfaces={}",
                    key.sessionId(), pendingFallbackReason, snapshot.backend(),
                    String.format(java.util.Locale.ROOT, "%.2f", snapshot.actualDecodeFps()), snapshot.targetFps(),
                    String.format(java.util.Locale.ROOT, "%.2f", snapshot.averageDecodeMillis()),
                    String.format(java.util.Locale.ROOT, "%.2f", snapshot.p95DecodeMillis()),
                    snapshot.starvationCount(), snapshot.droppedFrames(), snapshot.syncDriftGrowthMillis(),
                    snapshot.nativeFrameBytesPeak(), snapshot.nativeSurfacePeak());
            return true;
        }

        private void observeTimelineAndEvaluate(DeviceVideoState state, long visualMillis) {
            long latestMillis = latestFrameMillis(state, this);
            if (visualMillis >= 0L && latestMillis >= 0L) {
                performanceMonitor.recordSyncDriftMillis(visualMillis - latestMillis);
            }
            evaluatePerformance(state, System.nanoTime());
        }

        private void lockPerformanceFallback(String reason) {
            performanceFallbackLocked = true;
            performanceFallbackRequested.set(false);
            fallbackReason = reason == null || reason.isBlank() ? "performance" : reason;
        }

        private boolean attachDecoder(Fmp4NativeVideoDecoder value) {
            Objects.requireNonNull(value);
            synchronized (owner.lifecycleLock) {
                if (closed.get() || owner.activeSession != this || !key.equals(owner.activeKey)) {
                    value.requestClose();
                    return false;
                }
                decoder.set(value);
                return true;
            }
        }

        private void trackNative(CompletableFuture<Void> nativeTermination) {
            latestNativeTermination.set(Objects.requireNonNull(nativeTermination));
        }

        private void detachDecoder(Fmp4NativeVideoDecoder value) {
            decoder.compareAndSet(value, null);
        }

        private void completeDecodeTaskExit() {
            CompletableFuture<Void> lastNativeTermination = latestNativeTermination.get();
            decodeExit.complete(null);
            lastNativeTermination.whenComplete((ignored, error) -> {
                if (error == null) {
                    physicalTermination.complete(null);
                } else {
                    physicalTermination.completeExceptionally(error);
                }
            });
        }

        @Override
        public void close() {
            synchronized (owner.lifecycleLock) {
                closed.set(true);
                Fmp4NativeVideoDecoder attached = decoder.get();
                if (attached != null) {
                    attached.requestClose();
                }
            }
        }
    }

    private static final class SustainedPerformanceFallbackException extends IOException {
        private final String reason;

        private SustainedPerformanceFallbackException(String reason) {
            this(reason, null);
        }

        private SustainedPerformanceFallbackException(String reason, Throwable cause) {
            super("AV1 sustained performance fallback: " + reason, cause);
            this.reason = reason == null || reason.isBlank() ? "performance" : reason;
        }
    }

    private static final class DeviceVideoState {
        private final HandheldReplacementGate replacementGate;
        private final AtomicReference<HandheldVideoFrame> latestFrame = new AtomicReference<>();
        private final AtomicLong frameSequence = new AtomicLong();
        private final Object lifecycleLock = new Object();
        private final Object frameQueueLock = new Object();
        private final ArrayDeque<HandheldVideoFrame> frameQueue = new ArrayDeque<>();
        private long intentGeneration;
        private volatile PlaybackKey activeKey = PlaybackKey.EMPTY;
        private volatile VideoSession activeSession;
        private volatile PlaybackKey resolvingKey = PlaybackKey.EMPTY;
        private CancellableTaskFuture<ResolvedVideoStream> resolveTask;
        private volatile PlaybackKey failedKey = PlaybackKey.EMPTY;
        private volatile PlaybackKey endedKey = PlaybackKey.EMPTY;
        private volatile String statusText = "等待播放";
        private volatile int sourceWidth;
        private volatile int sourceHeight;
        private volatile boolean audioOnly;
        private volatile LyricRecord subtitleRecord;
        private volatile String currentSubtitle = "";
        private volatile long lastVisibleNanoTime = System.nanoTime();
        private volatile long offscreenSinceNanoTime;
        private volatile long rgbaConsumerUntilNanoTime;

        private DeviceVideoState(HandheldReplacementGate replacementGate) {
            this.replacementGate = Objects.requireNonNull(replacementGate);
        }
    }

    private static final class Mp4VideoThreadFactory implements ThreadFactory {
        private final AtomicInteger nextThreadId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mp4-handheld-video-" + nextThreadId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
