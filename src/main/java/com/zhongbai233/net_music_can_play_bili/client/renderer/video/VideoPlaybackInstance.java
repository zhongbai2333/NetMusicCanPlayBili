package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个同步视频播放会话，负责解码线程、会话专属动态纹理和投影仪列表
 */
final class VideoPlaybackInstance {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * 可选视频相位补偿。默认不补偿，避免掩盖 OpenAL 音频 pacing 问题；需要按设备/驱动微调时再通过
     * JVM 参数启用。
     */
    private static final long AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS = Long.getLong(
            "bili.video.pipeline.audio_latency_compensation_ms", 0L);
    private static final long CHASE_WINDOW_MILLIS = Long.getLong("bili.video.pipeline.chase_window_ms", 10_000L);
    private static final long SLOWDOWN_WINDOW_MILLIS = Long.getLong("bili.video.pipeline.slowdown_window_ms", 2_500L);
    private static final double IRIS_WARNING_PLACEHOLDER_VIEW_DEPTH_OFFSET = Double.parseDouble(
            System.getProperty("bili.video.pipeline.iris_warning_placeholder_view_depth_offset", "0.03"));

    private final String videoUrl;
    private int targetWidth;
    private int targetHeight;
    private final int fps;
    private final int codecId;
    private final String sessionId;
    private final long startOffsetMillis;
    private final long totalMillis;
    private final boolean preferNative;
    private final String decoderOverride;
    private final Identifier firstTextureId;
    private final Identifier secondTextureId;
    private final Identifier loadingTextureId;
    private final Identifier yTextureId;
    private final Identifier uTextureId;
    private final Identifier vTextureId;
    private final VideoPlaybackAnchor anchor;
    private final VideoFrameQueue frameQueue = new VideoFrameQueue(
            Integer.getInteger("bili.video.pipeline.queue_capacity", 3));
    private final AtomicLong generation = new AtomicLong();
    private final Set<BlockPos> projectorPositions = new CopyOnWriteArraySet<>();
    private volatile boolean running;
    private volatile boolean hasFrame;
    private volatile long startNanoTime;
    private volatile Thread decodeThread;
    private volatile AutoCloseable decoder;
    private volatile DynamicTexture frontTexture;
    private volatile DynamicTexture backTexture;
    private volatile DynamicTexture loadingTexture;
    private volatile VideoYuvTextureSet yuvTextureSet;
    private volatile Identifier frontTextureId;
    private volatile Identifier backTextureId;
    private volatile boolean firstFrameLogged;
    private volatile boolean firstSubmitLogged;
    private volatile boolean firstYuvImmediateLogged;
    private volatile long firstDecodedNanoTime;
    private volatile boolean startupBufferReady;
    private volatile long lastUploadPumpNanoTime;
    private volatile long decoderStartOffsetMillis;
    private volatile long lastUploadedPtsNanos = -1L;
    private volatile long adaptiveRestartOffsetMillis = -1L;
    private int consecutiveBadUploads;

    VideoPlaybackInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> projectorPositions,
            BlockPos turntablePos, boolean preferNative, String decoderOverride) {
        this(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis,
            projectorPositions, VideoPlaybackAnchor.turntable(turntablePos, sessionId, Math.max(0L, totalMillis)),
            preferNative, decoderOverride);
        }

        VideoPlaybackInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> projectorPositions,
            VideoPlaybackAnchor anchor, boolean preferNative, String decoderOverride) {
        this.videoUrl = videoUrl;
        this.targetWidth = Math.max(1, targetWidth);
        this.targetHeight = Math.max(1, targetHeight);
        this.fps = Math.max(1, fps);
        this.codecId = codecId;
        this.sessionId = sessionId;
        this.startOffsetMillis = Math.max(0L, startOffsetMillis);
        this.totalMillis = Math.max(0L, totalMillis);
        this.preferNative = preferNative;
        this.decoderOverride = decoderOverride;
        this.anchor = anchor != null ? anchor : VideoPlaybackAnchor.turntable(null, this.sessionId, this.totalMillis);
        String textureSuffix = Integer.toUnsignedString(sessionId.hashCode(), 16);
        this.firstTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_a");
        this.secondTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_b");
        this.loadingTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_loading");
        this.yTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_y");
        this.uTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_u");
        this.vTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_v");
        this.frontTextureId = firstTextureId;
        this.backTextureId = secondTextureId;
        replaceProjectors(projectorPositions);
        LOGGER.info("视频会话创建: session={}, {}x{} @ {}fps, renderBackend={}, decodeFormat={}", sessionId,
                this.targetWidth, this.targetHeight, this.fps, VideoBillboardPreview.RENDER_BACKEND,
                VideoBillboardPreview.YUV_DECODE_BACKEND
                        ? (VideoBillboardPreview.isCustomYuvShaderAvailable()
                                ? VideoBillboardPreview.yuvDecodeFormat().name() + "→RGB(shader)"
                                : VideoBillboardPreview.yuvDecodeFormat().name() + "→RGBA(cpu/iris-fallback)")
                        : "RGBA");
    }

    void start() {
        running = true;
        hasFrame = false;
        firstFrameLogged = false;
        firstSubmitLogged = false;
        firstYuvImmediateLogged = false;
        firstDecodedNanoTime = 0L;
        startupBufferReady = false;
        lastUploadPumpNanoTime = System.nanoTime();
        decoderStartOffsetMillis = startOffsetMillis;
        lastUploadedPtsNanos = -1L;
        adaptiveRestartOffsetMillis = -1L;
        consecutiveBadUploads = 0;
        frameQueue.clear();
        startNanoTime = System.nanoTime();
        long gen = generation.incrementAndGet();
        Thread thread = new Thread(() -> decode(gen), "bili-video-" + sessionId);
        thread.setDaemon(true);
        decodeThread = thread;
        thread.start();
    }

    private void decode(long gen) {
        long frameIntervalNs = Math.max(1L, 1_000_000_000L / fps);
        long frameIndex = 0L;
        long effectiveStartOffsetMillis = effectiveDecoderStartOffsetMillis();
        decoderStartOffsetMillis = effectiveStartOffsetMillis;
        adaptiveRestartOffsetMillis = -1L;
        try (AutoCloseable dec = VideoBillboardPreview.openDecoder(videoUrl, targetWidth, targetHeight, fps, codecId,
                preferNative, decoderOverride, effectiveStartOffsetMillis, totalMillis)) {
            decoder = dec;
            while (running && gen == generation.get()) {
                if (!waitWhilePaused(gen)) {
                    break;
                }
                if (projectorPositions.isEmpty()) {
                    break;
                }
                if (frameIndex > 0L) {
                    waitForDecodeLead(frameIntervalNs, gen);
                }
                long waitStartNs = System.nanoTime();
                VideoBillboardPreview.DecodedFrame frame = VideoBillboardPreview.nextDecodedFrame(dec);
                long waitNs = System.nanoTime() - waitStartNs;
                if (frameIndex == 0L && waitNs > 2_000_000_000L) {
                    LOGGER.warn("视频流水线首帧等待耗时较长: session={}, wait={}ms, startOffset={}ms, queue={}",
                            sessionId, waitNs / 1_000_000L, effectiveStartOffsetMillis, frameQueue.size());
                }
                if (frame == null) {
                    break;
                }
                frameIndex++;
                long ptsNanos = frame.ptsNanos() >= 0L ? frame.ptsNanos() : frameIndex * frameIntervalNs;
                if (!firstFrameLogged && shouldDropStaleStartupFrame(ptsNanos)) {
                    frame.close();
                    continue;
                }
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    firstDecodedNanoTime = System.nanoTime();
                }
                if (!frameQueue.offer(new DecodedVideoFrame(frameIndex, ptsNanos, frame),
                        () -> running && gen == generation.get())) {
                    frame.close();
                    break;
                }
                warnIfUploadPumpStalled();
            }
        } catch (Exception e) {
            if (gen != generation.get() || (!running && isInterruptedWait(e))) {
                return;
            }
            LOGGER.error("视频会话解码失败: session={}", sessionId, e);
        } finally {
            if (gen == generation.get()) {
                running = false;
                decoder = null;
            }
        }
    }

    private boolean shouldDropStaleStartupFrame(long ptsNanos) {
        long maxStartupLagNs = Long.getLong("bili.video.pipeline.startup_drop_lag_ms", 750L) * 1_000_000L;
        if (maxStartupLagNs <= 0L) {
            return false;
        }
        long playbackNs = playbackNanos();
        boolean drop = playbackNs - ptsNanos > maxStartupLagNs;
        return drop && frameQueue.isEmpty();
    }

    private void waitForDecodeLead(long frameIntervalNs, long gen) throws InterruptedException {
        long maxLeadNs = Math.max(frameIntervalNs * frameQueue.capacity(),
                Long.getLong("bili.video.pipeline.max_decode_lead_ms", 250L) * 1_000_000L);
        while (running && gen == generation.get() && frameQueue.isFull()
                && frameQueue.latestPtsNanos() - playbackNanos() > maxLeadNs) {
            warnIfUploadPumpStalled();
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(5L);
        }
    }

    private void warnIfUploadPumpStalled() {
        long thresholdNs = Long.getLong("bili.video.pipeline.upload_pump_warn_ms", 1000L) * 1_000_000L;
        long idleNs = System.nanoTime() - lastUploadPumpNanoTime;
        if (thresholdNs > 0L && frameQueue.isFull() && idleNs > thresholdNs) {
            lastUploadPumpNanoTime = System.nanoTime();
            LOGGER.warn("视频流水线上传泵疑似停滞: session={}, queue={}, latestPts={}ms, clock={}ms, idle={}ms",
                    sessionId, frameQueue.size(), frameQueue.latestPtsNanos() / 1_000_000L,
                    playbackNanos() / 1_000_000L, idleNs / 1_000_000L);
        }
    }

    private boolean waitWhilePaused(long gen) {
        if (!isGamePaused()) {
            return running && gen == generation.get();
        }
        long pauseStartNs = System.nanoTime();
        while (running && gen == generation.get() && isGamePaused()) {
            try {
                java.util.concurrent.TimeUnit.MILLISECONDS.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        startNanoTime += Math.max(0L, System.nanoTime() - pauseStartNs);
        return running && gen == generation.get();
    }

    private static boolean isGamePaused() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.isPaused();
    }

    private static boolean isInterruptedWait(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
            if (current instanceof IOException && current.getMessage() != null
                    && current.getMessage().contains("等待 native 视频帧时被中断")) {
                return true;
            }
        }
        return false;
    }

    private long playbackNanos() {
        long synced = syncedPlaybackNanos();
        if (synced >= 0L) {
            return synced;
        }
        long startNs = startNanoTime;
        return startNs > 0L ? Math.max(0L, System.nanoTime() - startNs) : 0L;
    }

    private long syncedPlaybackNanos() {
        long restartOffset = adaptiveRestartOffsetMillis;
        long requestedOffsetMillis = restartOffset >= 0L ? restartOffset : startOffsetMillis;
        long baseOffsetMillis = Math.max(requestedOffsetMillis, decoderStartOffsetMillis);
        long synced = anchor.timeline().relativeNanos(
                baseOffsetMillis + AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS);
        return synced;
    }

    private long effectiveDecoderStartOffsetMillis() {
        long restartOffset = adaptiveRestartOffsetMillis;
        long requestedOffsetMillis = restartOffset >= 0L ? restartOffset : startOffsetMillis;
        long synced = anchor.timeline().mediaMillis();
        long offset = synced >= 0L ? Math.max(requestedOffsetMillis, synced) : requestedOffsetMillis;
        return totalMillis > 0L ? Math.min(totalMillis, offset) : offset;
    }

    void pumpUploadOnRenderThread() {
        lastUploadPumpNanoTime = System.nanoTime();
        if (!running && frameQueue.isEmpty()) {
            return;
        }
        if (!startupBufferReady && shouldWaitForStartupBuffer()) {
            return;
        }
        startupBufferReady = true;
        long playbackNs = playbackNanos();
        DecodedVideoFrame frame = frameQueue.pollBestFrame(playbackNs,
                Long.getLong("bili.video.pipeline.early_tolerance_ms", 12L) * 1_000_000L);
        if (frame == null) {
            return;
        }
        long maxVisibleLagNs = Long.getLong("bili.video.pipeline.max_visible_lag_ms", 250L) * 1_000_000L;
        if (maxVisibleLagNs > 0L && playbackNs - frame.ptsNanos() > maxVisibleLagNs) {
            DecodedVideoFrame chase = frameQueue.pollBestFrame(playbackNs, 0L);
            if (chase != null) {
                frame.close();
                frame = chase;
            }
        }
        boolean uploaded = false;
        long uploadNs = 0L;
        try {
            long uploadStartNs = System.nanoTime();
            uploaded = uploadDecodedFrameOnRenderThread(frame.frame());
            uploadNs = System.nanoTime() - uploadStartNs;
            if (uploaded) {
                lastUploadedPtsNanos = frame.ptsNanos();
            }
        } finally {
            frame.close();
        }
        if (uploaded) {
            recordAdaptiveUploadCost(uploadNs);
        }
    }

    private void recordAdaptiveUploadCost(long uploadNs) {
        if (uploadNs > VideoBillboardPreview.ADAPTIVE_FRAME_BUDGET_NS) {
            consecutiveBadUploads++;
            if (consecutiveBadUploads >= VideoBillboardPreview.ADAPTIVE_BAD_FRAME_THRESHOLD) {
                requestAdaptiveDownscale();
            }
        } else {
            consecutiveBadUploads = Math.max(0, consecutiveBadUploads - 2);
        }
    }

    private synchronized boolean requestAdaptiveDownscale() {
        int currentWidth = targetWidth;
        int currentHeight = targetHeight;
        if (currentWidth <= VideoBillboardPreview.MIN_ADAPTIVE_WIDTH) {
            return false;
        }
        int nextWidth = Math.max(VideoBillboardPreview.MIN_ADAPTIVE_WIDTH, Math.round(currentWidth * 0.75F));
        int nextHeight = Math.max(1, Math.round(currentHeight * (nextWidth / (float) currentWidth)));
        if (nextWidth >= currentWidth || nextHeight >= currentHeight) {
            return false;
        }

        long restartOffsetMillis = currentRestartOffsetMillis();
        LOGGER.warn("视频会话上传持续超预算，优先保证游戏流畅: session={}, {}x{} -> {}x{}，offset={}ms",
                sessionId, currentWidth, currentHeight, nextWidth, nextHeight, restartOffsetMillis);
        restartDecoder(nextWidth, nextHeight, restartOffsetMillis);
        return true;
    }

    private long currentRestartOffsetMillis() {
        long displayed = mediaMillis();
        if (displayed >= 0L) {
            return totalMillis > 0L ? Math.min(totalMillis, displayed) : displayed;
        }
        long synced = anchor.timeline().mediaMillis();
        if (synced >= 0L) {
            return totalMillis > 0L ? Math.min(totalMillis, synced) : synced;
        }
        long base = Math.max(startOffsetMillis, decoderStartOffsetMillis);
        long elapsed = Math.max(0L, playbackNanos() / 1_000_000L);
        long value = Math.max(0L, base + elapsed);
        return totalMillis > 0L ? Math.min(totalMillis, value) : value;
    }

    private void restartDecoder(int nextWidth, int nextHeight, long restartOffsetMillis) {
        long gen = generation.incrementAndGet();
        AutoCloseable oldDecoder = decoder;
        decoder = null;
        if (oldDecoder != null) {
            Thread closer = new Thread(() -> {
                try {
                    oldDecoder.close();
                } catch (Exception ignored) {
                }
            }, "bili-video-adaptive-close-" + sessionId);
            closer.setDaemon(true);
            closer.start();
        }
        Thread oldThread = decodeThread;
        if (oldThread != null) {
            oldThread.interrupt();
        }

        frameQueue.clear();
        releaseTexture();
        targetWidth = nextWidth;
        targetHeight = nextHeight;
        hasFrame = false;
        firstFrameLogged = false;
        firstDecodedNanoTime = 0L;
        startupBufferReady = false;
        startNanoTime = System.nanoTime();
        lastUploadPumpNanoTime = System.nanoTime();
        decoderStartOffsetMillis = Math.max(0L, restartOffsetMillis);
        adaptiveRestartOffsetMillis = decoderStartOffsetMillis;
        lastUploadedPtsNanos = -1L;
        consecutiveBadUploads = 0;
        running = true;

        Thread thread = new Thread(() -> decode(gen), "bili-video-" + sessionId + "-adaptive");
        thread.setDaemon(true);
        decodeThread = thread;
        thread.start();
    }

    private boolean shouldWaitForStartupBuffer() {
        int requiredFrames = Integer.getInteger("bili.video.pipeline.startup_prebuffer_frames", 2);
        if (requiredFrames <= 1 || hasFrame) {
            return false;
        }
        if (frameQueue.size() >= requiredFrames) {
            return false;
        }
        long firstDecodedNs = firstDecodedNanoTime;
        if (firstDecodedNs <= 0L) {
            return false;
        }
        long maxWaitNs = Long.getLong("bili.video.pipeline.startup_prebuffer_max_wait_ms", 250L) * 1_000_000L;
        boolean wait = System.nanoTime() - firstDecodedNs < maxWaitNs;
        if (!wait) {
            return false;
        }
        return wait;
    }

    private boolean uploadOnRenderThread(byte[] rgba) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || rgba.length < targetWidth * targetHeight * 4) {
            return false;
        }
        ensureTexture();
        NativeImage image = backTexture.getPixels();
        if (image == null || image.isClosed()) {
            return false;
        }
        VideoFrameUploader.uploadRgba(image, rgba, targetWidth, targetHeight);
        backTexture.upload();
        swapTextures();
        releaseYuvTextures();
        hasFrame = true;
        return true;
    }

    private boolean uploadDecodedFrameOnRenderThread(VideoBillboardPreview.DecodedFrame frame) {
        if (VideoBillboardPreview.isCustomYuvShaderAvailable()
                && isYuvFrameFormat(frame.format())) {
            return uploadYuvOnRenderThread(frame);
        }
        return uploadOnRenderThread(Yuv420pConverter.toUploadRgba(frame, targetWidth, targetHeight));
    }

    private boolean uploadYuvOnRenderThread(VideoBillboardPreview.DecodedFrame frame) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        ensureYuvTextureSet(frame.format());
        if (!uploadYuvFrameData(frame)) {
            return false;
        }
        releaseRgbaTextures();
        hasFrame = true;
        return true;
    }

    private boolean uploadYuvFrameData(VideoBillboardPreview.DecodedFrame frame) {
        if (yuvTextureSet == null || frame == null || yuvTextureSet.format() != frame.format()) {
            return false;
        }
        java.nio.ByteBuffer buffer = frame.buffer();
        if (buffer != null) {
            return yuvTextureSet.upload(buffer, frame.byteLength(), targetWidth, targetHeight);
        }
        return yuvTextureSet.upload(frame.data(), targetWidth, targetHeight);
    }

    private void ensureYuvTextureSet(
            com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
        com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format normalized = format == com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                ? com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                : com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
        if (yuvTextureSet != null && yuvTextureSet.format() == normalized) {
            return;
        }
        if (yuvTextureSet != null) {
            yuvTextureSet.close();
        }
        if (normalized == com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P) {
            yuvTextureSet = new Yuv420pTextureSet(yTextureId, uTextureId, vTextureId,
                    "bili_video_" + sessionId + "_yuv420p");
        } else {
            yuvTextureSet = new Nv12TextureSet(yTextureId, uTextureId, yTextureId,
                    "bili_video_" + sessionId + "_nv12");
        }
    }

    private static boolean isYuvFrameFormat(
            com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
        return format == com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                || format == com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
    }

    private void releaseRgbaTextures() {
        if (frontTexture != null) {
            Minecraft.getInstance().getTextureManager().release(frontTextureId);
            frontTexture.close();
            frontTexture = null;
        }
        if (backTexture != null && !backTextureId.equals(frontTextureId)) {
            Minecraft.getInstance().getTextureManager().release(backTextureId);
            backTexture.close();
            backTexture = null;
        } else if (backTexture != null) {
            backTexture.close();
            backTexture = null;
        }
        frontTextureId = firstTextureId;
        backTextureId = secondTextureId;
    }

    private void releaseYuvTextures() {
        if (yuvTextureSet != null) {
            yuvTextureSet.close();
            yuvTextureSet = null;
        }
    }

    private void ensureTexture() {
        if (frontTexture != null && backTexture != null) {
            NativeImage image = frontTexture.getPixels();
            NativeImage backImage = backTexture.getPixels();
            if (image != null && !image.isClosed() && image.getWidth() == targetWidth
                    && image.getHeight() == targetHeight
                    && backImage != null && !backImage.isClosed() && backImage.getWidth() == targetWidth
                    && backImage.getHeight() == targetHeight) {
                return;
            }
        }
        releaseTexture();
        frontTexture = new DynamicTexture("bili_video_" + sessionId + "_front", targetWidth, targetHeight, false);
        backTexture = new DynamicTexture("bili_video_" + sessionId + "_back", targetWidth, targetHeight, false);
        frontTextureId = firstTextureId;
        backTextureId = secondTextureId;
        Minecraft.getInstance().getTextureManager().register(frontTextureId, frontTexture);
        Minecraft.getInstance().getTextureManager().register(backTextureId, backTexture);
    }

    private void swapTextures() {
        DynamicTexture oldFront = frontTexture;
        frontTexture = backTexture;
        backTexture = oldFront;
        Identifier oldFrontId = frontTextureId;
        frontTextureId = backTextureId;
        backTextureId = oldFrontId;
    }

    void submit(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera) {
        if (!firstSubmitLogged) {
            firstSubmitLogged = true;
        }
        pumpUploadOnRenderThread();
        List<BlockPos> stale = new ArrayList<>();
        for (BlockPos pos : projectorPositions) {
            if (!(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector)) {
                stale.add(pos);
                continue;
            }
            if (hasFrame && frontTexture != null) {
                VideoBillboardPreview.submitProjectorGeometry(event, minecraft, camera, projector, frontTextureId,
                        targetWidth, targetHeight);
            } else if (hasFrame && yuvTextureSet != null && VideoBillboardPreview.isCustomYuvShaderAvailable()
                    && !VideoBillboardPreview.shouldDrawYuvImmediateWithIris()) {
                VideoBillboardPreview.submitProjectorYuvGeometry(event, minecraft, camera, projector, yuvTextureSet);
            } else if (Boolean.parseBoolean(System.getProperty("bili.video.pipeline.loading_placeholder", "true"))) {
                ensureLoadingTexture();
                boolean irisWarning = shouldShowIrisTranslucencyWarning();
                updateLoadingTexture(irisWarning);
                if (irisWarning && IRIS_WARNING_PLACEHOLDER_VIEW_DEPTH_OFFSET > 0.0D) {
                    VideoBillboardPreview.submitProjectorViewDepthOffsetGeometry(event, minecraft, camera, projector,
                            loadingTextureId, LoadingTexture.WIDTH, LoadingTexture.HEIGHT,
                            IRIS_WARNING_PLACEHOLDER_VIEW_DEPTH_OFFSET);
                } else {
                    VideoBillboardPreview.submitProjectorEmissiveGeometry(event, minecraft, camera, projector,
                            loadingTextureId, LoadingTexture.WIDTH, LoadingTexture.HEIGHT);
                }
            }
        }
        projectorPositions.removeAll(stale);
    }

    void renderYuvImmediate(RenderLevelStageEvent event, String route) {
        if (!hasFrame || yuvTextureSet == null || !VideoBillboardPreview.isCustomYuvShaderAvailable()
                || !VideoBillboardPreview.shouldDrawYuvImmediateWithIris()) {
            return;
        }
        pumpUploadOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        List<BlockPos> stale = new ArrayList<>();
        boolean drew = false;
        for (BlockPos pos : projectorPositions) {
            if (!(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector)) {
                stale.add(pos);
                continue;
            }
            drew |= VideoBillboardPreview.drawProjectorYuvImmediate(event, minecraft, camera, projector, yuvTextureSet,
                    route);
        }
        projectorPositions.removeAll(stale);
        if (drew && !firstYuvImmediateLogged) {
            firstYuvImmediateLogged = true;
            LOGGER.warn("Iris/YUV: session={} 的投影仪 YUV 使用实例纹理 immediate 绘制，route={}, texture={}x{}",
                    sessionId, route, yuvTextureSet.width(), yuvTextureSet.height());
        }
    }

    private void ensureLoadingTexture() {
        if (loadingTexture != null) {
            NativeImage image = loadingTexture.getPixels();
            if (image != null && !image.isClosed()) {
                return;
            }
        }
        if (loadingTexture != null) {
            Minecraft.getInstance().getTextureManager().release(loadingTextureId);
            loadingTexture.close();
        }
        loadingTexture = new DynamicTexture("bili_video_" + sessionId + "_loading", LoadingTexture.WIDTH,
                LoadingTexture.HEIGHT, false);
        Minecraft.getInstance().getTextureManager().register(loadingTextureId, loadingTexture);
        updateLoadingTexture(shouldShowIrisTranslucencyWarning());
    }

    private void updateLoadingTexture(boolean irisTranslucencyWarning) {
        if (loadingTexture == null) {
            return;
        }
        NativeImage image = loadingTexture.getPixels();
        if (image == null || image.isClosed()) {
            return;
        }
        LoadingTexture.draw(image, System.nanoTime() - startNanoTime, frameQueue.size(), frameQueue.capacity(),
                irisTranslucencyWarning);
        loadingTexture.upload();
    }

    private boolean shouldShowIrisTranslucencyWarning() {
        return hasFrame && yuvTextureSet != null && VideoBillboardPreview.shouldDrawYuvImmediateWithIris();
    }

    boolean isWithinAudioRange(Minecraft minecraft) {
        if (minecraft.player == null || projectorPositions.isEmpty()) {
            return false;
        }
        return anchor.isWithinAudioRange(minecraft, projectorPositions, VideoBillboardPreview.AUDIO_SYNC_RANGE_SQR);
    }

    boolean isRunningAtOffset(long requestedOffsetMillis) {
        return isRunningAtOffset(requestedOffsetMillis, 1_500L);
    }

    boolean isRunningAtOffset(long requestedOffsetMillis, long toleranceMillis) {
        if (!running) {
            return false;
        }
        long tolerance = Math.max(0L, toleranceMillis);
        if (hasFrame) {
            long elapsedMillis = Math.max(0L, (System.nanoTime() - startNanoTime) / 1_000_000L);
            long estimatedDisplayedMillis = totalMillis > 0L
                    ? Math.min(totalMillis, startOffsetMillis + elapsedMillis)
                    : startOffsetMillis + elapsedMillis;
            if (Math.abs(estimatedDisplayedMillis - Math.max(0L, requestedOffsetMillis)) < tolerance) {
                return true;
            }
        }
        if (!hasFrame) {
            // 解码器仍在 seek/解码第一帧可见画面。这里先认为会话兼容，避免周期性投影刷新或服务端同步包
            // 在产出第一帧前反复杀掉解码器。
            return true;
        }
        long expectedOffset = Math.max(startOffsetMillis, decoderStartOffsetMillis);
        return Math.abs(expectedOffset - Math.max(0L, requestedOffsetMillis)) < tolerance;
    }

    boolean canChaseToOffset(long requestedOffsetMillis) {
        if (!running) {
            return false;
        }
        if (!hasFrame) {
            // 首帧还在加载/seek：此时最容易被 3 秒同步包误杀，必须继续复用当前解码器。
            return true;
        }
        long target = totalMillis > 0L ? Math.min(totalMillis, Math.max(0L, requestedOffsetMillis))
                : Math.max(0L, requestedOffsetMillis);
        long current = mediaMillis();
        if (current < 0L) {
            return true;
        }
        long delta = target - current;
        if (delta >= 0L) {
            long queuedTargetMillis = Math.max(startOffsetMillis, decoderStartOffsetMillis)
                    + Math.max(0L, frameQueue.latestPtsNanos() / 1_000_000L);
            return target <= queuedTargetMillis + Math.max(0L, CHASE_WINDOW_MILLIS);
        }
        return -delta <= Math.max(0L, SLOWDOWN_WINDOW_MILLIS);
    }

    void replaceProjectors(Collection<BlockPos> positions) {
        projectorPositions.clear();
        projectorPositions.addAll(VideoBillboardPreview.immutablePositions(positions));
    }

    void addProjector(BlockPos pos) {
        if (pos != null) {
            projectorPositions.add(pos.immutable());
        }
    }

    void removeProjector(BlockPos pos) {
        projectorPositions.remove(pos);
    }

    boolean isForTurntable(BlockPos pos) {
        return anchor.isForTurntable(pos);
    }

    boolean isSession(String candidateSessionId) {
        return sessionId.equals(candidateSessionId != null ? candidateSessionId : "");
    }

    boolean hasProjectors() {
        return !projectorPositions.isEmpty();
    }

    boolean containsProjector(BlockPos pos) {
        return pos != null && projectorPositions.contains(pos);
    }

    boolean isRunning() {
        return running;
    }

    boolean hasFrame() {
        return hasFrame;
    }

    long mediaMillis() {
        long uploadedPts = lastUploadedPtsNanos;
        if (!hasFrame || uploadedPts < 0L) {
            return -1L;
        }
        long clockMillis = Math.max(0L, uploadedPts / 1_000_000L);
        long baseOffsetMillis = Math.max(startOffsetMillis, decoderStartOffsetMillis);
        long value = Math.max(0L, baseOffsetMillis + clockMillis - AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS);
        return totalMillis > 0L ? Math.min(totalMillis, value) : value;
    }

    long queuedMediaMillis() {
        long latestPts = frameQueue.latestPtsNanos();
        if (latestPts <= 0L) {
            return -1L;
        }
        long baseOffsetMillis = Math.max(startOffsetMillis, decoderStartOffsetMillis);
        long value = Math.max(0L, baseOffsetMillis + latestPts / 1_000_000L);
        return totalMillis > 0L ? Math.min(totalMillis, value) : value;
    }

    VideoBillboardPreview.VideoStatus status() {
        return new VideoBillboardPreview.VideoStatus(targetWidth, targetHeight, fps, hasFrame, true);
    }

    void stop() {
        running = false;
        generation.incrementAndGet();
        frameQueue.clear();
        AutoCloseable dec = decoder;
        decoder = null;
        if (dec != null) {
            Thread closer = new Thread(() -> {
                try {
                    dec.close();
                } catch (Exception ignored) {
                }
            }, "bili-video-close-" + sessionId);
            closer.setDaemon(true);
            closer.start();
        }
        Thread thread = decodeThread;
        if (thread != null) {
            thread.interrupt();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            releaseTexture();
        } else {
            minecraft.execute(this::releaseTexture);
        }
    }

    private void releaseTexture() {
        releaseRgbaTextures();
        releaseYuvTextures();
        if (loadingTexture != null) {
            Minecraft.getInstance().getTextureManager().release(loadingTextureId);
            loadingTexture.close();
            loadingTexture = null;
        }
    }

    static final class LoadingTexture {
        static final int WIDTH = 320;
        static final int HEIGHT = 180;
        private static final int BG = 0xFF08090D;
        private static final int PANEL = 0xFF151923;
        private static final int GOLD = 0xFFFFD166;
        private static final int GOLD_DIM = 0xFF7A6230;
        private static final int TEXT = 0xFFE8E8E8;
        private static final int SHADOW = 0xAA000000;

        private LoadingTexture() {
        }

        static void draw(NativeImage image, long elapsedNs, int queuedFrames, int capacity,
                boolean irisTranslucencyWarning) {
            fill(image, 0, 0, WIDTH, HEIGHT, BG);
            fill(image, 18, 18, WIDTH - 36, HEIGHT - 36, PANEL);
            rect(image, 18, 18, WIDTH - 36, HEIGHT - 36, GOLD_DIM);

            int phase = (int) ((elapsedNs / 300_000_000L) % 4L);
            if (irisTranslucencyWarning) {
                drawCenteredText(image, "VIDEO ACTIVE", 54, TEXT);
                drawCenteredText(image, "TRANSLUCENT", 78, GOLD);
                drawCenteredText(image, "MAY HIDE VIDEO", 102, 0xFFB8C1CC);
            } else {
                drawCenteredText(image, "LOADING" + dots(phase), 62, TEXT);
                drawCenteredText(image, "DECODING", 84, 0xFFB8C1CC);
                drawCenteredText(image, "PLEASE WAIT", 100, 0xFF8F9BA8);
            }

            int barX = 58;
            int barY = 126;
            int barW = 204;
            int barH = 10;
            rect(image, barX, barY, barW, barH, GOLD_DIM);
            int segmentW = 42;
            int movingX = barX + 2 + (int) (((elapsedNs / 12_000_000L) % Math.max(1, barW - segmentW - 4)));
            fill(image, movingX, barY + 2, segmentW, barH - 4, GOLD);

            int buffered = capacity <= 0 ? 0 : Math.min(barW - 4, Math.max(0, queuedFrames) * (barW - 4) / capacity);
            fill(image, barX + 2, barY + barH + 8, buffered, 3, GOLD_DIM);
        }

        private static String dots(int phase) {
            return switch (phase) {
                case 1 -> ".";
                case 2 -> "..";
                case 3 -> "...";
                default -> "";
            };
        }

        private static void rect(NativeImage image, int x, int y, int w, int h, int color) {
            fill(image, x, y, w, 1, color);
            fill(image, x, y + h - 1, w, 1, color);
            fill(image, x, y, 1, h, color);
            fill(image, x + w - 1, y, 1, h, color);
        }

        private static void fill(NativeImage image, int x, int y, int w, int h, int color) {
            int maxX = Math.min(WIDTH, x + Math.max(0, w));
            int maxY = Math.min(HEIGHT, y + Math.max(0, h));
            for (int py = Math.max(0, y); py < maxY; py++) {
                for (int px = Math.max(0, x); px < maxX; px++) {
                    image.setPixel(px, py, color);
                }
            }
        }

        private static void drawText(NativeImage image, String text, int x, int y, int color) {
            drawTextRaw(image, text, x + 1, y + 1, SHADOW);
            drawTextRaw(image, text, x, y, color);
        }

        private static void drawCenteredText(NativeImage image, String text, int y, int color) {
            drawText(image, text, (WIDTH - textWidth(text)) / 2, y, color);
        }

        private static int textWidth(String text) {
            int width = 0;
            for (int i = 0; i < text.length(); i++) {
                width += text.charAt(i) == ' ' ? 4 : 12;
            }
            return Math.max(0, width - 2);
        }

        private static void drawTextRaw(NativeImage image, String text, int x, int y, int color) {
            int cursor = x;
            for (int i = 0; i < text.length(); i++) {
                char c = Character.toUpperCase(text.charAt(i));
                if (c == ' ') {
                    cursor += 4;
                    continue;
                }
                int[] glyph = glyph(c);
                for (int row = 0; row < 7; row++) {
                    int bits = glyph[row];
                    for (int col = 0; col < 5; col++) {
                        if ((bits & (1 << (4 - col))) != 0) {
                            fill(image, cursor + col * 2, y + row * 2, 2, 2, color);
                        }
                    }
                }
                cursor += 12;
            }
        }

        private static int[] glyph(char c) {
            return switch (c) {
                case 'A' -> new int[] { 0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001 };
                case 'C' -> new int[] { 0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110 };
                case 'D' -> new int[] { 0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110 };
                case 'E' -> new int[] { 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111 };
                case 'G' -> new int[] { 0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01110 };
                case 'H' -> new int[] { 0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001 };
                case 'I' -> new int[] { 0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111 };
                case 'K' -> new int[] { 0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001 };
                case 'L' -> new int[] { 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111 };
                case 'M' -> new int[] { 0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001 };
                case 'N' -> new int[] { 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001 };
                case 'O' -> new int[] { 0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110 };
                case 'P' -> new int[] { 0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000 };
                case 'R' -> new int[] { 0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001 };
                case 'S' -> new int[] { 0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110 };
                case 'T' -> new int[] { 0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100 };
                case 'U' -> new int[] { 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110 };
                case 'V' -> new int[] { 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100 };
                case 'W' -> new int[] { 0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001 };
                case 'Y' -> new int[] { 0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100 };
                case '.' -> new int[] { 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100 };
                default -> new int[] { 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000 };
            };
        }
    }

    private record DecodedVideoFrame(long frameIndex, long ptsNanos, VideoBillboardPreview.DecodedFrame frame) {
        void close() {
            frame.close();
        }
    }

    private static final class VideoFrameQueue {
        private final int capacity;
        private final ArrayDeque<DecodedVideoFrame> frames = new ArrayDeque<>();
        private long latestPtsNanos;

        VideoFrameQueue(int capacity) {
            this.capacity = Math.max(1, capacity);
        }

        synchronized boolean offer(DecodedVideoFrame frame, java.util.function.BooleanSupplier shouldContinue)
                throws InterruptedException {
            while (frames.size() >= capacity && shouldContinue.getAsBoolean()) {
                wait(5L);
            }
            if (!shouldContinue.getAsBoolean()) {
                return false;
            }
            latestPtsNanos = Math.max(latestPtsNanos, frame.ptsNanos());
            frames.addLast(frame);
            notifyAll();
            return true;
        }

        synchronized DecodedVideoFrame pollBestFrame(long playbackNanos, long earlyToleranceNanos) {
            DecodedVideoFrame best = null;
            long visibleUntil = playbackNanos + Math.max(0L, earlyToleranceNanos);
            while (!frames.isEmpty()) {
                DecodedVideoFrame next = frames.peekFirst();
                if (next.ptsNanos() > visibleUntil) {
                    break;
                }
                DecodedVideoFrame polled = frames.pollFirst();
                if (best != null) {
                    best.close();
                }
                best = polled;
            }
            if (best != null) {
                notifyAll();
            }
            return best;
        }

        synchronized void clear() {
            for (DecodedVideoFrame frame : frames) {
                frame.close();
            }
            frames.clear();
            notifyAll();
        }

        synchronized boolean isFull() {
            return frames.size() >= capacity;
        }

        synchronized boolean isEmpty() {
            return frames.isEmpty();
        }

        synchronized int size() {
            return frames.size();
        }

        int capacity() {
            return capacity;
        }

        synchronized long latestPtsNanos() {
            return latestPtsNanos;
        }
    }
}