package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSubtitleLyricService;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
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
    private static final int MAX_ALLOWED_WIDTH = 8192;
    private static final int MAX_ALLOWED_HEIGHT = 4320;
    private static final int HIGH_RES_WARNING_WIDTH = 1920;
    private static final int HIGH_RES_WARNING_HEIGHT = 1080;
    private static final int MAX_FRAMES = Integer.getInteger("netmusic.mp4.video.max_frames", 1_000_000);
    private static final long FRAME_WAIT_SLICE_MILLIS = Long.getLong("netmusic.mp4.video.frame_wait_slice_ms", 8L);
    private static final long MAX_LATE_FRAME_NANOS = Long.getLong("netmusic.mp4.video.max_late_frame_ms", 250L)
            * 1_000_000L;
    private static final long STARTUP_DROP_LAG_NANOS = Long.getLong("netmusic.mp4.video.startup_drop_lag_ms", 750L)
            * 1_000_000L;
    private static final long MAX_DECODE_LEAD_NANOS = Long.getLong("netmusic.mp4.video.max_decode_lead_ms", 350L)
            * 1_000_000L;
    private static final long EARLY_TOLERANCE_NANOS = Long.getLong("netmusic.mp4.video.early_tolerance_ms", 24L)
            * 1_000_000L;
    private static final int FRAME_QUEUE_CAPACITY = Integer.getInteger("netmusic.mp4.video.queue_capacity", 4);
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new Mp4VideoThreadFactory());
    private static final Map<UUID, DeviceVideoState> STATES = new ConcurrentHashMap<>();
    private static final AtomicBoolean highResolutionWarningShown = new AtomicBoolean(false);

    private MP4HandheldVideoClient() {
    }

    public static boolean update(UUID deviceId) {
        if (deviceId == null) {
            return false;
        }
        DeviceVideoState state = state(deviceId);
        if (!isDeviceInHotbar(deviceId)) {
            stop(deviceId, "等待快捷栏");
            return false;
        }
        MP4Item.State renderState = stateForDevice(deviceId);
        if (!renderState.videoDecodeEnabled()) {
            stop(deviceId, "等待横屏播放");
            return false;
        }
        MP4ClientPlayback.LocalVideoPlayback playback = MP4ClientPlayback.localVideoPlayback(deviceId);
        if (!playback.hasPlayableVideoSource()) {
            stop(deviceId, "等待播放同步");
            return false;
        }
        PlaybackKey key = new PlaybackKey(playback.sessionId(), playback.rawUrl(), renderState.videoQualityCeiling(),
                renderState.subtitleAiEnabled(), shouldUseRgbaFallback());
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
        stop(deviceId, "切换视频源");
        state = state(deviceId);
        state.activeKey = key;
        state.resolvingKey = key;
        state.failedKey = PlaybackKey.EMPTY;
        state.endedKey = PlaybackKey.EMPTY;
        state.statusText = "解析视频流...";
        state.sourceWidth = 0;
        state.sourceHeight = 0;
        resolveAndStart(deviceId, state, playback, key);
        return false;
    }

    public static VideoFrame latestFrame(UUID deviceId) {
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

    public static String currentResolutionLabel(UUID deviceId) {
        DeviceVideoState state = stateOrNull(deviceId);
        if (state == null) {
            return "";
        }
        VideoFrame frame = state.latestFrame.get();
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
        MP4ClientPlayback.LocalVideoPlayback playback = MP4ClientPlayback.localVideoPlayback(deviceId);
        long visualMillis = anchoredVisualMillis(deviceId, playback);
        int tick = visualMillis >= 0L
                ? (int) Math.min(Integer.MAX_VALUE, visualMillis / 50L)
                : -1;
        String primary = currentLineAt(record.getLyrics(), tick);
        String secondary = currentLineAt(record.getTransLyrics(), tick);
        return MP4FocusState.subtitlePrimaryMode() ? primary : secondary;
    }

    public static void stop(String reason) {
        STATES.values().forEach(state -> stop(state, reason));
    }

    public static void stop(UUID deviceId, String reason) {
        stop(state(deviceId), reason);
    }

    private static void stop(DeviceVideoState state, String reason) {
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
        state.subtitleRecord = null;
        state.currentSubtitle = "";
        state.sourceWidth = 0;
        state.sourceHeight = 0;
        clearFrameQueue(state);
        VideoFrame latest = state.latestFrame.getAndSet(null);
        if (latest != null) {
            latest.close();
            state.frameSequence.incrementAndGet();
        }
    }

    public static void clearAll() {
        STATES.values().forEach(state -> stop(state, "等待播放"));
        STATES.clear();
    }

    public static void stopDevicesOutsideHotbar() {
        for (Map.Entry<UUID, DeviceVideoState> entry : STATES.entrySet()) {
            UUID deviceId = entry.getKey();
            if (!isDeviceInHotbar(deviceId)) {
                stop(entry.getValue(), "等待快捷栏");
            }
        }
    }

    public static void tickHotbarVideoFrames() {
        tickHotbarVideoSessions();
        for (Map.Entry<UUID, DeviceVideoState> entry : STATES.entrySet()) {
            UUID deviceId = entry.getKey();
            if (!isDeviceInHotbar(deviceId)) {
                continue;
            }
            DeviceVideoState state = entry.getValue();
            VideoSession session = state.activeSession;
            if (session == null || session.closed.get() || !session.key.equals(state.activeKey)) {
                continue;
            }
            MP4ClientPlayback.LocalVideoPlayback playback = MP4ClientPlayback.localVideoPlayback(deviceId);
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
            MP4ClientPlayback.LocalVideoPlayback playback,
            PlaybackKey key) {
        CompletableFuture.supplyAsync(() -> resolveStream(playback, key.quality()), EXECUTOR)
                .whenComplete((stream, error) -> {
                    if (!key.equals(state.activeKey)) {
                        return;
                    }
                    state.resolvingKey = PlaybackKey.EMPTY;
                    if (error != null) {
                        state.failedKey = key;
                        state.statusText = "视频解析失败";
                        LOGGER.warn("MP4 横屏视频流解析失败: session={} raw='{}' reason={}", playback.sessionId(),
                                playback.rawUrl(), error.toString());
                        return;
                    }
                    state.subtitleRecord = stream.subtitleRecord();
                    state.currentSubtitle = state.subtitleRecord != null ? "" : "无可用字幕";
                    state.sourceWidth = stream.sourceWidth();
                    state.sourceHeight = stream.sourceHeight();
                    startDecoder(deviceId, state, playback, key, stream);
                });
    }

    private static ResolvedStream resolveStream(MP4ClientPlayback.LocalVideoPlayback playback, int qualityCeiling) {
        try {
            BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(playback.rawUrl());
            if (selection == null) {
                throw new IllegalArgumentException("不是 B 站视频选择: " + playback.rawUrl());
            }
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
            BiliApiClient.VideoStream stream = BiliApiClient.getBestVideoStream(selection.videoId(), info.cid(),
                    qualityCeiling);
            int fps = parseFrameRate(stream.frameRate());
            LyricRecord subtitle = BiliSubtitleLyricService.tryBuildLyricRecord(playback.rawUrl(), playback.songName(),
                    playback.allowAiSubtitle());
            return new ResolvedStream(stream.baseUrl(), stream.codecId(), Math.max(1, stream.width()),
                    Math.max(1, stream.height()), fps, stream.quality(), info.displayTitle(), subtitle);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void startDecoder(UUID deviceId, DeviceVideoState state,
            MP4ClientPlayback.LocalVideoPlayback playback, PlaybackKey key,
            ResolvedStream stream) {
        long elapsedMillis = Math.max(0L, playback.timeline().mediaMillis());
        long totalMillis = Math.max(0L, playback.timeline().totalMillis());
        VideoSession session = new VideoSession(key, elapsedMillis);
        state.activeSession = session;
        state.statusText = "视频缓冲中...";
        CompletableFuture.runAsync(() -> {
            try {
                decodeLoop(deviceId, state, session, stream, elapsedMillis, totalMillis);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, EXECUTOR)
                .whenComplete((ignored, error) -> {
                    if (state.activeSession == session) {
                        state.activeSession = null;
                    }
                    if (error != null && !session.closed.get()) {
                        state.failedKey = key;
                        state.statusText = "视频播放失败";
                        LOGGER.warn("MP4 横屏视频解码失败: session={} stream={} quality={} reason={}", key.sessionId(),
                                stream.url(), stream.quality(), error.toString());
                    }
                });
    }

    private static void decodeLoop(UUID deviceId, DeviceVideoState state, VideoSession session, ResolvedStream stream,
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
                decodeSize.height(), MAX_FRAMES, true, outputFormat, null,
                elapsedMillis, totalMillis, stream.fps())) {
            long displayedFrames = 0L;
            boolean firstFrameAccepted = false;
            while (!session.closed.get() && session.key.equals(state.activeKey)) {
                Fmp4NativeVideoDecoder.DecodedFrame decoded = decoder.getNextDecodedFrame();
                if (decoded == null) {
                    state.endedKey = session.key;
                    state.statusText = "视频播放结束";
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
                VideoFrame frame = VideoFrame.retain(decoded, requiredBytes, decodeSize.width(), decodeSize.height(),
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

    private static MP4Item.State stateForDevice(UUID deviceId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || deviceId == null) {
            return MP4Item.State.DEFAULT;
        }
        ItemStack stack = MP4Item.findByDeviceId(minecraft.player, deviceId);
        return MP4Client.stateForHeldRender(stack);
    }

    private static DecodeSize chooseDecodeSize(int sourceWidth, int sourceHeight) {
        int safeSourceWidth = Math.max(2, sourceWidth);
        int safeSourceHeight = Math.max(2, sourceHeight);
        int maxWidth = MAX_ALLOWED_WIDTH;
        int maxHeight = MAX_ALLOWED_HEIGHT;
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
        if (decodeSize.width() <= HIGH_RES_WARNING_WIDTH && decodeSize.height() <= HIGH_RES_WARNING_HEIGHT) {
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
            MP4ClientPlayback.LocalVideoPlayback playback = MP4ClientPlayback.localVideoPlayback(deviceId);
            if (!session.key.sessionId().equals(playback.sessionId())) {
                return false;
            }
            long visualMillis = anchoredVisualMillis(deviceId, playback);
            long visualNanos = sessionRelativeVisualNanos(session, visualMillis);
            long leadNanos = targetNanos - visualNanos;
            if (leadNanos <= MAX_DECODE_LEAD_NANOS) {
                return true;
            }
            pumpFrameForTimeline(state, session, visualMillis);
            long sleepMillis = Math.min(FRAME_WAIT_SLICE_MILLIS,
                    Math.max(1L, (leadNanos - MAX_DECODE_LEAD_NANOS) / 1_000_000L));
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
        if (STARTUP_DROP_LAG_NANOS <= 0L) {
            return false;
        }
        MP4ClientPlayback.LocalVideoPlayback playback = MP4ClientPlayback.localVideoPlayback(deviceId);
        long visualNanos = sessionRelativeVisualNanos(session, anchoredVisualMillis(deviceId, playback));
        boolean drop = visualNanos - Math.max(0L, framePtsNanos) > STARTUP_DROP_LAG_NANOS;
        return drop && frameQueueEmpty(state);
    }

    private static boolean offerFrame(DeviceVideoState state, VideoSession session, VideoFrame frame) {
        synchronized (state.frameQueueLock) {
            while (!session.closed.get() && session.key.equals(state.activeKey)
                    && state.frameQueue.size() >= Math.max(1, FRAME_QUEUE_CAPACITY)) {
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
            for (VideoFrame frame : state.frameQueue) {
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

    private static long anchoredVisualMillis(UUID deviceId, MP4ClientPlayback.LocalVideoPlayback playback) {
        if (playback == null || playback.timeline() == null) {
            return -1L;
        }
        DolbyAudioRegistry.AudioTimeline audioTimeline = DolbyAudioRegistry.getOwnerAudioTimeline(deviceId);
        long audibleMillis = audioTimeline.audibleMillis();
        if (audibleMillis >= 0L && (audioTimeline.sessionId() == null || audioTimeline.sessionId().isBlank()
                || audioTimeline.sessionId().equals(playback.sessionId()))) {
            long totalMillis = Math.max(0L, playback.timeline().totalMillis());
            return totalMillis > 0L ? Math.min(totalMillis, audibleMillis) : audibleMillis;
        }
        return playback.timeline().visualMillis();
    }

    private static boolean pumpFrameForTimeline(DeviceVideoState state, VideoSession session, long visualMillis) {
        long visualNanos = sessionRelativeVisualNanos(session, visualMillis);
        VideoFrame selected = null;
        synchronized (state.frameQueueLock) {
            while (!state.frameQueue.isEmpty()) {
                VideoFrame first = state.frameQueue.peekFirst();
                if (first.ptsNanos() > visualNanos + EARLY_TOLERANCE_NANOS && selected == null) {
                    break;
                }
                VideoFrame candidate = state.frameQueue.pollFirst();
                if (candidate.ptsNanos() <= visualNanos + EARLY_TOLERANCE_NANOS) {
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
                    && visualNanos - state.frameQueue.peekFirst().ptsNanos() > MAX_LATE_FRAME_NANOS) {
                if (selected != null) {
                    selected.close();
                }
                selected = state.frameQueue.pollFirst();
            }
        }
        if (selected != null) {
            VideoFrame previous = state.latestFrame.getAndSet(selected);
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

    private static int parseFrameRate(String value) {
        if (value == null || value.isBlank()) {
            return 30;
        }
        try {
            String text = value.trim();
            int slash = text.indexOf('/');
            if (slash > 0) {
                double numerator = Double.parseDouble(text.substring(0, slash));
                double denominator = Double.parseDouble(text.substring(slash + 1));
                if (denominator > 0.0D) {
                    return Math.max(1, (int) Math.round(numerator / denominator));
                }
            }
            return Math.max(1, (int) Math.round(Double.parseDouble(text)));
        } catch (NumberFormatException ignored) {
            return 30;
        }
    }

    public record VideoFrame(byte[] data, ByteBuffer buffer, int byteLength,
            Fmp4NativeVideoDecoder.DecodedFrame.Format format, int width, int height, long ptsNanos,
            AutoCloseable delegate) implements AutoCloseable {
        static VideoFrame retain(Fmp4NativeVideoDecoder.DecodedFrame decoded, int byteLength, int width, int height,
                long ptsNanos) {
            ByteBuffer buffer = decoded.buffer();
            byte[] data = buffer == null ? decoded.data() : null;
            return new VideoFrame(data, buffer, byteLength, decoded.format(), width, height, ptsNanos, decoded);
        }

        @Override
        public void close() {
            if (delegate != null) {
                try {
                    delegate.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private record PlaybackKey(String sessionId, String rawUrl, int quality, boolean allowAiSubtitle,
            boolean rgbaFallback) {
        static final PlaybackKey EMPTY = new PlaybackKey("", "", 0, false, false);
    }

    public record DecodeSize(int width, int height) {
    }

    private record ResolvedStream(String url, int codecId, int sourceWidth, int sourceHeight, int fps, int quality,
            String title, LyricRecord subtitleRecord) {
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
        private final AtomicReference<VideoFrame> latestFrame = new AtomicReference<>();
        private final AtomicLong frameSequence = new AtomicLong();
        private final Object frameQueueLock = new Object();
        private final ArrayDeque<VideoFrame> frameQueue = new ArrayDeque<>();
        private volatile PlaybackKey activeKey = PlaybackKey.EMPTY;
        private volatile VideoSession activeSession;
        private volatile PlaybackKey resolvingKey = PlaybackKey.EMPTY;
        private volatile PlaybackKey failedKey = PlaybackKey.EMPTY;
        private volatile PlaybackKey endedKey = PlaybackKey.EMPTY;
        private volatile String statusText = "等待播放";
        private volatile int sourceWidth;
        private volatile int sourceHeight;
        private volatile LyricRecord subtitleRecord;
        private volatile String currentSubtitle = "";
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
