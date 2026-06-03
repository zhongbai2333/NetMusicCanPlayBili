package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个同步视频播放会话，负责解码线程、会话专属动态纹理和投影仪列表
 */
final class VideoPlaybackInstance {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final String videoUrl;
    private final int targetWidth;
    private final int targetHeight;
    private final int fps;
    private final int codecId;
    private final String sessionId;
    private final long startOffsetMillis;
    private final long totalMillis;
    private final boolean preferNative;
    private final String decoderOverride;
    private final Identifier textureId;
    private final BlockPos turntablePos;
    private final AtomicLong generation = new AtomicLong();
    private final Set<BlockPos> projectorPositions = new CopyOnWriteArraySet<>();
    private volatile boolean running;
    private volatile boolean hasFrame;
    private volatile long startNanoTime;
    private volatile Thread decodeThread;
    private volatile AutoCloseable decoder;
    private volatile DynamicTexture texture;

    VideoPlaybackInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> projectorPositions,
            BlockPos turntablePos, boolean preferNative, String decoderOverride) {
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
        this.turntablePos = turntablePos != null ? turntablePos.immutable() : null;
        this.textureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + Integer.toUnsignedString(sessionId.hashCode(), 16));
        replaceProjectors(projectorPositions);
    }

    void start() {
        running = true;
        hasFrame = false;
        startNanoTime = System.nanoTime();
        long gen = generation.incrementAndGet();
        Thread thread = new Thread(() -> decode(gen), "bili-video-" + sessionId);
        thread.setDaemon(true);
        decodeThread = thread;
        thread.start();
        LOGGER.info("视频会话已启动: session={} {}x{} @ {}fps projectors={}", sessionId, targetWidth, targetHeight,
                fps, projectorPositions);
    }

    private void decode(long gen) {
        long frameIntervalNs = Math.max(1L, 1_000_000_000L / fps);
        long frameIndex = 0L;
        try (AutoCloseable dec = VideoBillboardPreview.openDecoder(videoUrl, targetWidth, targetHeight, fps, codecId,
                preferNative, decoderOverride, startOffsetMillis, totalMillis)) {
            decoder = dec;
            while (running && gen == generation.get()) {
                if (!waitWhilePaused(gen)) {
                    break;
                }
                if (projectorPositions.isEmpty()) {
                    break;
                }
                byte[] frame = VideoBillboardPreview.nextFrame(dec);
                if (frame == null) {
                    break;
                }
                frameIndex++;
                int dropped = 0;
                long nowNs = System.nanoTime();
                while (running && gen == generation.get()
                        && nowNs - expectedFrameTimeNs(frameIndex, frameIntervalNs) > frameIntervalNs * 2L
                        && dropped < VideoBillboardPreview.MAX_CATCH_UP_DROPS_PER_TICK) {
                    byte[] next = VideoBillboardPreview.nextFrame(dec);
                    if (next == null) {
                        frame = null;
                        break;
                    }
                    frame = next;
                    frameIndex++;
                    dropped++;
                    nowNs = System.nanoTime();
                }
                if (frame == null) {
                    break;
                }
                long waitNs = expectedFrameTimeNs(frameIndex, frameIntervalNs) - System.nanoTime();
                if (waitNs > 0L) {
                    try {
                        sleepUntilFrame(frameIndex, frameIntervalNs, gen);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (upload(frame, gen) < 0L) {
                    break;
                }
            }
        } catch (Exception e) {
            if (!running && isInterruptedWait(e)) {
                LOGGER.debug("视频会话已停止: session={}", sessionId);
                return;
            }
            LOGGER.error("视频会话解码失败: session={}", sessionId, e);
        } finally {
            running = false;
            decoder = null;
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

    private void sleepUntilFrame(long frameIndex, long frameIntervalNs, long gen) throws InterruptedException {
        while (running && gen == generation.get()) {
            if (isGamePaused()) {
                waitWhilePaused(gen);
            }
            long remainingNs = expectedFrameTimeNs(frameIndex, frameIntervalNs) - System.nanoTime();
            if (remainingNs <= 0L) {
                return;
            }
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(remainingNs, 25_000_000L));
        }
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

    private long expectedFrameTimeNs(long frameIndex, long frameIntervalNs) {
        return startNanoTime + frameIndex * frameIntervalNs;
    }

    private long upload(byte[] rgba, long gen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || gen != generation.get() || !running) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            if (gen != generation.get() || !running) {
                future.complete(-1L);
                return;
            }
            long startNs = System.nanoTime();
            boolean ok = uploadOnRenderThread(rgba);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频会话上传帧失败: session={}", sessionId, e);
            return -1L;
        }
    }

    private boolean uploadOnRenderThread(byte[] rgba) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || rgba.length < targetWidth * targetHeight * 4) {
            return false;
        }
        ensureTexture();
        NativeImage image = texture.getPixels();
        if (image == null || image.isClosed()) {
            return false;
        }
        VideoFrameUploader.uploadRgba(image, rgba, targetWidth, targetHeight);
        texture.upload();
        hasFrame = true;
        return true;
    }

    private void ensureTexture() {
        if (texture != null) {
            NativeImage image = texture.getPixels();
            if (image != null && !image.isClosed() && image.getWidth() == targetWidth
                    && image.getHeight() == targetHeight) {
                return;
            }
        }
        releaseTexture();
        texture = new DynamicTexture("bili_video_" + sessionId, targetWidth, targetHeight, false);
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
    }

    void submit(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera) {
        if (!hasFrame || texture == null) {
            return;
        }
        List<BlockPos> stale = new ArrayList<>();
        for (BlockPos pos : projectorPositions) {
            if (!(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector)) {
                stale.add(pos);
                continue;
            }
            VideoBillboardPreview.submitProjectorGeometry(event, minecraft, camera, projector, textureId, targetWidth,
                    targetHeight);
        }
        projectorPositions.removeAll(stale);
    }

    boolean isWithinAudioRange(Minecraft minecraft) {
        if (minecraft.player == null || projectorPositions.isEmpty()) {
            return false;
        }
        Vec3 playerPos = minecraft.player.position();
        if (turntablePos != null) {
            double dx = turntablePos.getX() + 0.5D - playerPos.x;
            double dy = turntablePos.getY() + 0.5D - playerPos.y;
            double dz = turntablePos.getZ() + 0.5D - playerPos.z;
            return dx * dx + dy * dy + dz * dz <= VideoBillboardPreview.AUDIO_SYNC_RANGE_SQR;
        }
        for (BlockPos pos : projectorPositions) {
            double dx = pos.getX() + 0.5D - playerPos.x;
            double dy = pos.getY() + 0.5D - playerPos.y;
            double dz = pos.getZ() + 0.5D - playerPos.z;
            if (dx * dx + dy * dy + dz * dz <= VideoBillboardPreview.AUDIO_SYNC_RANGE_SQR) {
                return true;
            }
        }
        return false;
    }

    boolean isRunningAtOffset(long requestedOffsetMillis) {
        if (!running) {
            return false;
        }
        long expectedOffset = startOffsetMillis + Math.max(0L, (System.nanoTime() - startNanoTime) / 1_000_000L);
        return Math.abs(expectedOffset - Math.max(0L, requestedOffsetMillis)) < 1_500L;
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
        return pos != null && turntablePos != null && turntablePos.equals(pos);
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

    VideoBillboardPreview.VideoStatus status() {
        return new VideoBillboardPreview.VideoStatus(targetWidth, targetHeight, fps, hasFrame, true);
    }

    void stop() {
        running = false;
        generation.incrementAndGet();
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
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            texture.close();
            texture = null;
        }
    }
}