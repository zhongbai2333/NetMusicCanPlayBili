package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Coordinates logical stop, physical decoder convergence, diagnostics, and render-thread texture release. */
final class VideoPlaybackCloser {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CLOSE_TIMEOUT_MILLIS =
            VideoPipelineProperties.timing().decoderRestartCloseTimeoutMillis();

    private final VideoPlaybackInstance owner;

    VideoPlaybackCloser(VideoPlaybackInstance owner) {
        this.owner = owner;
    }

    void stop() {
        if (owner.stopRequested) {
            return;
        }
        owner.stopRequested = true;
        owner.restartState = VideoDecoderRestartState.STOPPED;
        owner.restartInProgress = false;
        long now = System.nanoTime();
        AutoCloseable decoder = owner.decoder;
        CompletableFuture<Void> threadExit = owner.decodeExit;
        CompletableFuture<Void> nativeTermination = decoder instanceof Fmp4NativeVideoDecoder nativeDecoder
                ? nativeDecoder.terminationFuture() : CompletableFuture.completedFuture(null);
        CompletableFuture<Void> closeReturned = decoder != null
                ? MediaCloseExecutor.closeAsyncStrict(decoder, "video decoder " + owner.sessionId())
                : CompletableFuture.completedFuture(null);
        CompletableFuture<Void> renderRelease = new CompletableFuture<>();
        owner.physicalCloseHandoff.attachClose(closeReturned, nativeTermination, threadExit);
        owner.physicalCloseHandoff.seal(renderRelease);
        EnumSet<VideoCloseDiagnostics.Phase> required = EnumSet.of(
                VideoCloseDiagnostics.Phase.FRAME_QUEUE_CLEARED,
                VideoCloseDiagnostics.Phase.RENDER_RELEASE_RETURNED);
        if (decoder != null) {
            required.add(VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED);
        }
        if (threadExit != null && !threadExit.isDone()) {
            required.add(VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED);
        }
        if (!nativeTermination.isDone()) {
            required.add(VideoCloseDiagnostics.Phase.NATIVE_TERMINATED);
        }
        long closeOperation = VideoCloseDiagnostics.global().begin(owner.playbackSessionId, required, now);
        owner.running = false;
        owner.generation.incrementAndGet();
        owner.frameQueue.clear();
        VideoCloseDiagnostics.global().complete(closeOperation,
                VideoCloseDiagnostics.Phase.FRAME_QUEUE_CLEARED, System.nanoTime());
        owner.decoder = null;
        observeRequiredSignals(closeOperation, required, closeReturned, threadExit, nativeTermination);
        Thread thread = owner.decodeThread;
        if (thread != null) {
            thread.interrupt();
        }
        if (threadExit != null && !threadExit.isDone()) {
            scheduleDecodeExitDiagnostic(thread, threadExit);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            releaseTextureAndReport(closeOperation, renderRelease);
        } else {
            minecraft.execute(() -> releaseTextureAndReport(closeOperation, renderRelease));
        }
    }

    private static void observeRequiredSignals(long closeOperation, EnumSet<VideoCloseDiagnostics.Phase> required,
            CompletableFuture<Void> closeReturned, CompletableFuture<Void> threadExit,
            CompletableFuture<Void> nativeTermination) {
        VideoCloseDiagnostics diagnostics = VideoCloseDiagnostics.global();
        if (required.contains(VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED)) {
            diagnostics.observe(closeOperation, VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED, closeReturned);
        }
        if (required.contains(VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED)) {
            diagnostics.observe(closeOperation, VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED, threadExit);
        }
        if (required.contains(VideoCloseDiagnostics.Phase.NATIVE_TERMINATED)) {
            diagnostics.observe(closeOperation, VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, nativeTermination);
        }
    }

    private void scheduleDecodeExitDiagnostic(Thread thread, CompletableFuture<Void> threadExit) {
        CompletableFuture.delayedExecutor(Math.max(1L, CLOSE_TIMEOUT_MILLIS), TimeUnit.MILLISECONDS).execute(() -> {
            if (threadExit.isDone()) {
                return;
            }
            if (thread == null) {
                LOGGER.error("视频 decode exit 在 stop 后仍 pending，但实例没有 decode thread: session={}",
                        owner.sessionId());
                return;
            }
            StackTraceElement[] trace = thread.getStackTrace();
            StringBuilder location = new StringBuilder();
            int limit = Math.min(12, trace.length);
            for (int index = 0; index < limit; index++) {
                if (index > 0) {
                    location.append(" <- ");
                }
                location.append(trace[index]);
            }
            LOGGER.error("视频 decode exit 在 stop 后仍 pending: session={} thread={} alive={} state={} stack={}",
                    owner.sessionId(), thread.getName(), thread.isAlive(), thread.getState(), location);
        });
    }

    void abandonBeforeStart() {
        if (owner.running || owner.stopRequested) {
            stop();
            return;
        }
        owner.stopRequested = true;
        owner.restartState = VideoDecoderRestartState.STOPPED;
        CompletableFuture<Void> renderRelease = new CompletableFuture<>();
        owner.physicalCloseHandoff.seal(renderRelease);
        renderRelease.complete(null);
    }

    private void releaseTextureAndReport(long closeOperation, CompletableFuture<Void> renderRelease) {
        try {
            owner.textures.release();
            renderRelease.complete(null);
        } catch (RuntimeException | Error error) {
            renderRelease.completeExceptionally(error);
            throw error;
        } finally {
            VideoCloseDiagnostics.global().complete(closeOperation,
                    VideoCloseDiagnostics.Phase.RENDER_RELEASE_RETURNED, System.nanoTime());
        }
    }
}
