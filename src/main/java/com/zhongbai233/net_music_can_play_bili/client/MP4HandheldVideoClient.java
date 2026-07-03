package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.ResolvedVideoStream;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelineView;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaRenderState;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoPipelineConfig;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
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
    private static final int MAX_VIDEO_THREADS = Math.max(2,
            Integer.getInteger("ncpb.mp4.video.max_threads", 4));
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(MAX_VIDEO_THREADS,
            new Mp4VideoThreadFactory());
    private static final Map<UUID, DeviceVideoState> STATES = new ConcurrentHashMap<>();
    private static final AtomicBoolean highResolutionWarningShown = new AtomicBoolean(false);
    private static final MP4HandheldMediaProfile MP4_PROFILE = MP4HandheldMediaProfile.INSTANCE;

    private MP4HandheldVideoClient() {
    }

    public static boolean update(UUID deviceId) {
        if (deviceId == null) {
            return false;
        }
        DeviceVideoState state = state(deviceId);
        if (!MP4_PROFILE.isDeviceAvailable(deviceId)) {
            stop(deviceId, "等待快捷栏");
            return false;
        }
        HandheldMediaRenderState renderState = MP4_PROFILE.renderState(deviceId);
        if (!renderState.videoDecodeEnabled()) {
            stop(deviceId, "等待横屏播放");
            return false;
        }
        HandheldMediaPlayback playback = MP4_PROFILE.playback(deviceId);
        if (!playback.hasPlayableVideoSource()) {
            stop(deviceId, "等待播放同步");
            return false;
        }
        if (!MP4_PROFILE.hasStartedSound(deviceId, playback.sessionId())) {
            waitForAudioStart(state);
            return false;
        }
        PlaybackKey key = new PlaybackKey(playback.sessionId(), playback.rawUrl(), renderState.videoQualityCeiling(),
                renderState.allowAiSubtitle(), shouldUseRgbaFallback() || hasActiveRgbaConsumer(state));
        synchronized (state.lifecycleLock) {
            VideoSession session = state.activeSession;
            if (key.equals(state.activeKey) && session != null && !session.closed.get()) {
                return pumpFrameForTimeline(state, session, anchoredVisualMillis(deviceId, playback));
            }
            if (key.equals(state.resolvingKey)) {
                return false;
            }
            if (key.equals(state.activeKey) && (key.equals(state.failedKey) || key.equals(state.endedKey))) {
                return false;
            }
            stopLocked(state, "切换视频源");
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
        resolveAndStart(deviceId, state, playback, key);
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
        HandheldMediaPlayback playback = MP4_PROFILE.playback(deviceId);
        long visualMillis = anchoredVisualMillis(deviceId, playback);
        int tick = visualMillis >= 0L
                ? (int) Math.min(Integer.MAX_VALUE, visualMillis / 50L)
                : -1;
        String primary = currentLineAt(record.getLyrics(), tick);
        String secondary = currentLineAt(record.getTransLyrics(), tick);
        return "primary".equals(MP4_PROFILE.subtitleMode(deviceId)) ? primary : secondary;
    }

    public static void stop(String reason) {
        STATES.values().forEach(state -> stop(state, reason));
        MP4ItemScreenRenderer.releaseAllVideoLayers();
    }

    public static void stop(UUID deviceId, String reason) {
        DeviceVideoState state = stateOrNull(deviceId);
        if (state != null) {
            stop(state, reason);
        }
        MP4ItemScreenRenderer.releaseVideoLayers(deviceId);
    }

    private static void stop(DeviceVideoState state, String reason) {
        synchronized (state.lifecycleLock) {
            stopLocked(state, reason);
        }
    }

    private static void stopLocked(DeviceVideoState state, String reason) {
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

    private static void waitForAudioStart(DeviceVideoState state) {
        synchronized (state.lifecycleLock) {
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
        MP4ItemScreenRenderer.releaseAllVideoLayers();
    }

    public static void stopDevicesOutsideHotbar() {
        for (Map.Entry<UUID, DeviceVideoState> entry : STATES.entrySet()) {
            UUID deviceId = entry.getKey();
            if (!MP4_PROFILE.isDeviceAvailable(deviceId)) {
                stop(entry.getValue(), "等待快捷栏");
                MP4ItemScreenRenderer.releaseVideoLayers(deviceId);
            }
        }
    }

    public static void tickHotbarVideoFrames() {
        tickHotbarVideoSessions();
        for (Map.Entry<UUID, DeviceVideoState> entry : STATES.entrySet()) {
            UUID deviceId = entry.getKey();
            if (!MP4_PROFILE.isDeviceAvailable(deviceId)) {
                continue;
            }
            DeviceVideoState state = entry.getValue();
            VideoSession session = state.activeSession;
            if (session == null || session.closed.get() || !session.key.equals(state.activeKey)) {
                continue;
            }
            HandheldMediaPlayback playback = MP4_PROFILE.playback(deviceId);
            if (playback == null || !session.key.sessionId().equals(playback.sessionId())) {
                continue;
            }
            pumpFrameForTimeline(state, session, anchoredVisualMillis(deviceId, playback));
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
            PlaybackKey key) {
        CompletableFuture.supplyAsync(() -> resolveStream(playback, key.quality()), EXECUTOR)
                .whenComplete((stream, error) -> {
                    synchronized (state.lifecycleLock) {
                        if (!key.equals(state.activeKey) || !key.equals(state.resolvingKey)) {
                            return;
                        }
                        state.resolvingKey = PlaybackKey.EMPTY;
                        if (error != null) {
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
                    }
                    startDecoder(deviceId, state, playback, key, stream);
                });
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
            ResolvedVideoStream stream) {
        long elapsedMillis = Math.max(0L, playback.timeline().mediaMillis());
        long totalMillis = Math.max(0L, playback.timeline().totalMillis());
        VideoSession session = new VideoSession(key, elapsedMillis);
        synchronized (state.lifecycleLock) {
            if (!key.equals(state.activeKey) || state.activeSession != null && !state.activeSession.closed.get()) {
                session.close();
                return;
            }
            state.activeSession = session;
            state.statusText = "视频缓冲中...";
        }
        CompletableFuture.runAsync(() -> {
            try {
                decodeLoop(deviceId, state, session, stream, elapsedMillis, totalMillis);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, EXECUTOR)
                .whenComplete((ignored, error) -> {
                    synchronized (state.lifecycleLock) {
                        if (state.activeSession == session) {
                            state.activeSession = null;
                        }
                        if (error != null && !session.closed.get()) {
                            state.failedKey = key;
                            state.statusText = "视频播放失败";
                            LOGGER.warn("MP4 横屏视频解码失败: session={} stream={} quality={} reason={}", key.sessionId(),
                                    stream.url(), stream.quality(), error.toString());
                        }
                    }
                });
    }

    private static void decodeLoop(UUID deviceId, DeviceVideoState state, VideoSession session,
            ResolvedVideoStream stream,
            long elapsedMillis, long totalMillis)
            throws IOException {
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
        try (Fmp4NativeVideoDecoder decoder = new Fmp4NativeVideoDecoder(stream.url(), stream.codecId(),
                decodeSize.width(),
                decodeSize.height(), CONFIG.maxFrames(), true, outputFormat, null,
                elapsedMillis, totalMillis, stream.fps())) {
            long displayedFrames = 0L;
            boolean firstFrameAccepted = false;
            while (!session.closed.get() && session.key.equals(state.activeKey)) {
                if (!waitWhileOffscreen(deviceId, state, session)) {
                    return;
                }
                Fmp4NativeVideoDecoder.DecodedFrame decoded = decoder.getNextDecodedFrame();
                if (decoded == null) {
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
                    decoded.close();
                    continue;
                }
                long framePtsNanos = framePtsOrFallback(decoded.ptsNanos(), displayedFrames, stream.fps());
                if (!firstFrameAccepted && shouldDropStaleStartupFrame(deviceId, state, session, framePtsNanos)) {
                    decoded.close();
                    displayedFrames++;
                    continue;
                }
                firstFrameAccepted = true;
                if (!waitForDecodeLead(deviceId, state, session, framePtsNanos)) {
                    decoded.close();
                    return;
                }
                HandheldVideoFrame frame = HandheldVideoFrame.retain(decoded, requiredBytes, decodeSize.width(),
                        decodeSize.height(),
                        framePtsNanos);
                if (!offerFrame(state, session, frame)) {
                    frame.close();
                    return;
                }
                displayedFrames++;
                state.statusText = "视频播放中";
            }
        }
    }

    private static boolean waitWhileOffscreen(UUID deviceId, DeviceVideoState state, VideoSession session) {
        if (!CONFIG.offscreenPauseDecode() || !isOffscreenPauseActive(state)) {
            return !session.closed.get() && session.key.equals(state.activeKey);
        }
        long pauseStartNs = System.nanoTime();
        while (!session.closed.get() && session.key.equals(state.activeKey) && isOffscreenPauseActive(state)) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        long pausedNs = System.nanoTime() - pauseStartNs;
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
        HandheldMediaPlayback playback = MP4_PROFILE.playback(deviceId);
        if (playback == null || !session.key.sessionId().equals(playback.sessionId())) {
            return;
        }
        long visualMillis = anchoredVisualMillis(deviceId, playback);
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
            HandheldMediaPlayback playback = MP4_PROFILE.playback(deviceId);
            if (!session.key.sessionId().equals(playback.sessionId())) {
                return false;
            }
            long visualMillis = anchoredVisualMillis(deviceId, playback);
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
        HandheldMediaPlayback playback = MP4_PROFILE.playback(deviceId);
        long visualNanos = sessionRelativeVisualNanos(session, anchoredVisualMillis(deviceId, playback));
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

    private static long anchoredVisualMillis(UUID deviceId, HandheldMediaPlayback playback) {
        if (playback == null || playback.timeline() == null) {
            return -1L;
        }
        return ClientMediaTimelineView.forHandheldOwner(deviceId, playback,
                MP4_PROFILE.hasStartedSound(deviceId, playback.sessionId()), playback.timeline().visualMillis(),
                playback.timeline().totalMillis()).visualMillis();
    }

    private static boolean pumpFrameForTimeline(DeviceVideoState state, VideoSession session, long visualMillis) {
        long visualNanos = sessionRelativeVisualNanos(session, visualMillis);
        HandheldVideoFrame selected = null;
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
                }
                selected = state.frameQueue.pollFirst();
            }
        }
        if (selected != null) {
            HandheldVideoFrame previous = state.latestFrame.getAndSet(selected);
            if (previous != null) {
                previous.close();
            }
            state.frameSequence.incrementAndGet();
            synchronized (state.frameQueueLock) {
                state.frameQueueLock.notifyAll();
            }
            return true;
        }
        return false;
    }

    private static DeviceVideoState state(UUID deviceId) {
        if (deviceId == null) {
            throw new IllegalArgumentException("MP4 video state requires a device id");
        }
        return STATES.computeIfAbsent(deviceId, ignored -> new DeviceVideoState());
    }

    private static DeviceVideoState stateOrNull(UUID deviceId) {
        return deviceId != null ? STATES.get(deviceId) : null;
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

    private record PlaybackKey(String sessionId, String rawUrl, int quality, boolean allowAiSubtitle,
            boolean rgbaFallback) {
        static final PlaybackKey EMPTY = new PlaybackKey("", "", 0, false, false);
    }

    public record DecodeSize(int width, int height) {
    }

    private static final class VideoSession implements AutoCloseable {
        private final PlaybackKey key;
        private final long decoderStartOffsetMillis;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private VideoSession(PlaybackKey key, long decoderStartOffsetMillis) {
            this.key = Objects.requireNonNull(key);
            this.decoderStartOffsetMillis = Math.max(0L, decoderStartOffsetMillis);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class DeviceVideoState {
        private final AtomicReference<HandheldVideoFrame> latestFrame = new AtomicReference<>();
        private final AtomicLong frameSequence = new AtomicLong();
        private final Object lifecycleLock = new Object();
        private final Object frameQueueLock = new Object();
        private final ArrayDeque<HandheldVideoFrame> frameQueue = new ArrayDeque<>();
        private volatile PlaybackKey activeKey = PlaybackKey.EMPTY;
        private volatile VideoSession activeSession;
        private volatile PlaybackKey resolvingKey = PlaybackKey.EMPTY;
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
