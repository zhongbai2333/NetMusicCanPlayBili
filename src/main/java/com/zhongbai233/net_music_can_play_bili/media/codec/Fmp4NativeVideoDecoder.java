package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import org.slf4j.Logger;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java fMP4/DASH 解复用 + FFmpeg JNI 视频解码器。
 */
public final class Fmp4NativeVideoDecoder implements AutoCloseable {
    private static final int CODEC_H264 = 7;
    private static final int CODEC_AV1 = 13;
    private static final Fmp4NativeVideoProperties.Decoder PROPERTIES = Fmp4NativeVideoProperties.decoder();
    private static final int FMP4_STREAM_RECOVERY_ATTEMPTS = PROPERTIES.streamRecoveryAttempts();
    private static final int DECODER_CLOSE_MAX_ATTEMPTS = 3;
    private static final long DECODER_CLOSE_INITIAL_BACKOFF_MILLIS = 25L;

    /** fMP4 模式下的媒体地址；直播总线模式为 null。 */
    private final URL videoUrl;
    /** 直播视频样本总线 key；非 null 时输入来自 {@code LiveVideoSampleBus} 而不是 HTTP。 */
    private final String liveBusKey;
    private final int codecId;
    private final int targetWidth;
    private final int targetHeight;
    private final int maxFrames;
    private final OutputFormat outputFormat;
    private final long startOffsetMillis;
    private final long totalMillis;
    private final int fps;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean physicalCloseScheduled = new AtomicBoolean(false);
    private final AtomicReference<DecoderCloseState> decoderCloseState = new AtomicReference<>(
            DecoderCloseState.OPEN);
    private final CompletableFuture<Void> decoderCloseCompletion = new CompletableFuture<>();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private final TrackedInputRegistry trackedInputs = new TrackedInputRegistry();
    private final VideoNativeDecoder decoder;
    private final Fmp4NativeVideoDecodePump decodePump;
    private final Fmp4VideoStreamSeeker streamSeeker;

    private int[] pendingSampleSizes = new int[0];
    private long[] pendingSamplePtsNanos = new long[0];
    private int pendingSampleIndex;
    private int streamTimescale;
    private volatile IOException failure;
    private volatile Thread worker;
    private int parsedMoofCount;
    private int parsedSampleCount;

    public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames)
            throws IOException {
        this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, true);
    }

    public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames,
            boolean outputFrames) throws IOException {
        this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames, false);
    }

    public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames,
            boolean outputFrames, boolean outputYuv420) throws IOException {
        this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames, outputYuv420, null);
    }

    public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames,
            boolean outputFrames, boolean outputYuv420, String requestedHwaccel) throws IOException {
        this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames, outputYuv420, requestedHwaccel, 0L);
    }

    public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames,
            boolean outputFrames, boolean outputYuv420, String requestedHwaccel, long startOffsetMillis)
            throws IOException {
        this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames, outputYuv420, requestedHwaccel,
                startOffsetMillis, 0L);
    }

    public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames,
            boolean outputFrames, boolean outputYuv420, String requestedHwaccel, long startOffsetMillis,
            long totalMillis) throws IOException {
        this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames, outputYuv420, requestedHwaccel,
                startOffsetMillis, totalMillis, 60);
    }

    public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames,
            boolean outputFrames, boolean outputYuv420, String requestedHwaccel, long startOffsetMillis,
            long totalMillis, int fps) throws IOException {
        this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames,
                outputYuv420 ? OutputFormat.NV12 : OutputFormat.RGBA, requestedHwaccel, startOffsetMillis,
                totalMillis, fps);
    }

    public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames,
            boolean outputFrames, OutputFormat outputFormat, String requestedHwaccel, long startOffsetMillis,
            long totalMillis, int fps) throws IOException {
        this(URI.create(videoUrl).toURL(), null, codecId, targetWidth, targetHeight, maxFrames, outputFrames,
                outputFormat, requestedHwaccel, startOffsetMillis, totalMillis, fps);
    }

    /**
     * 直播总线模式：输入是 {@code LiveVideoSampleBus} 里由音频会话解出的 H.264 样本，
     * pts 已在音频输出时间域。无 seek、无 HTTP，流结束由总线关闭驱动。
     */
    public static Fmp4NativeVideoDecoder forLiveBus(String busKey, int targetWidth, int targetHeight,
            OutputFormat outputFormat, String requestedHwaccel, int fps) throws IOException {
        if (busKey == null || busKey.isBlank()) {
            throw new IOException("直播视频总线 key 为空");
        }
        return new Fmp4NativeVideoDecoder(null, busKey, CODEC_H264, targetWidth, targetHeight, Integer.MAX_VALUE,
                true, outputFormat, requestedHwaccel, 0L, 0L, fps);
    }

    private Fmp4NativeVideoDecoder(URL videoUrl, String liveBusKey, int codecId, int targetWidth, int targetHeight,
            int maxFrames, boolean outputFrames, OutputFormat outputFormat, String requestedHwaccel,
            long startOffsetMillis, long totalMillis, int fps) throws IOException {
        this.videoUrl = videoUrl;
        this.liveBusKey = liveBusKey;
        if (codecId != CODEC_H264 && codecId != CODEC_AV1) {
            throw new IOException("不支持的视频 codecId=" + codecId + "（仅支持 7=H.264, 13=AV1）");
        }
        this.codecId = codecId;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.maxFrames = Math.max(1, maxFrames);
        this.outputFormat = outputFormat != null ? outputFormat : OutputFormat.RGBA;
        this.startOffsetMillis = Math.max(0L, startOffsetMillis);
        this.totalMillis = Math.max(0L, totalMillis);
        this.fps = Math.max(1, fps);
        this.streamSeeker = videoUrl != null
                ? new Fmp4VideoStreamSeeker(videoUrl, this.startOffsetMillis, this.totalMillis, trackedInputs, closed)
                : null;
        this.decoder = new VideoNativeDecoder(this.codecId, targetWidth, targetHeight);
        this.decodePump = new Fmp4NativeVideoDecodePump(this.decoder, this.codecId, targetWidth, targetHeight,
                this.maxFrames, outputFrames, this.outputFormat, this.startOffsetMillis, this.fps, closed);
        if (requestedHwaccel != null) {
            this.decoder.setRequestedHwaccel(requestedHwaccel);
        }
        try {
            if (!this.decoder.open()) {
                throw new IOException("无法打开 native 视频 decoder: codecId=" + codecId
                        + ", hwaccel=" + requestedHwaccel);
            }
        } catch (RuntimeException error) {
            this.decoder.close();
            throw new IOException("无法打开 native 视频 decoder: codecId=" + codecId
                    + ", hwaccel=" + requestedHwaccel, error);
        }
    }

    public String actualHwaccel() {
        return decoder.actualHwaccel();
    }

    public boolean isHardwareAccelerated() {
        return decoder.isHardwareAccelerated();
    }

    public static void registerSegmentBase(String videoUrl, long initStart, long initEnd, long indexStart,
            long indexEnd) {
        Fmp4VideoStreamSeeker.registerSegmentBase(videoUrl, initStart, initEnd, indexStart, indexEnd);
    }

    public byte[] getNextFrame() throws IOException {
        DecodedFrame frame = getNextDecodedFrame();
        if (frame == null) {
            return null;
        }
        try {
            return Arrays.copyOf(frame.data(), frame.data().length);
        } finally {
            frame.close();
        }
    }

    public DecodedFrame getNextDecodedFrame() throws IOException {
        decodePump.requireRegularGetter();
        return decodePump.awaitNextFrame(this::ensureStarted, finished::get, () -> failure, this::signalCancel);
    }

    public DecodedFrame getNextDecodedFrameWithAv1FirstFrameProbe() throws IOException {
        synchronized (this) {
            decodePump.beginFirstFrameProbe(started.get());
            ensureStarted();
        }
        return decodePump.awaitNextFrame(this::ensureStarted, finished::get, () -> failure, this::signalCancel);
    }

    public void commitAv1FirstFrameProbe(DecodedFrame frame) throws IOException {
        decodePump.commitFirstFrame(frame);
    }

    public void rejectAv1FirstFrameProbeFrame(DecodedFrame frame) throws IOException {
        decodePump.rejectFirstFrame(frame);
    }

    public int getTotalFrames() {
        return decodePump.totalFrames();
    }

    private void ensureStarted() {
        synchronized (this) {
            if (started.get() || closed.get()) {
                return;
            }
            started.set(true);
            Thread created = NetMusicThreadFactory.daemonThread("bili-native-video-decoder", () -> {
                try {
                    parseAndDecode();
                } catch (IOException e) {
                    failure = e;
                } finally {
                    finished.set(true);
                    closed.set(true);
                    schedulePhysicalTermination(Thread.currentThread());
                }
            });
            worker = created;
            try {
                created.start();
            } catch (RuntimeException | Error startFailure) {
                finished.set(true);
                closed.set(true);
                schedulePhysicalTermination(created);
                throw startFailure;
            }
        }
    }

    private void parseAndDecode() throws IOException {
        if (liveBusKey != null) {
            decodeLiveBusStream();
            return;
        }
        long streamStartOffsetMillis = startOffsetMillis;
        int recoveries = 0;
        while (!closed.get() && (decodePump.totalFrames() < maxFrames || decodePump.hasActiveUncommittedProbe())) {
            try {
                parseStreamOnce(streamStartOffsetMillis);
                decodePump.drainNaturalEndOfStream();
                break;
            } catch (UnsupportedAudioFileException e) {
                throw new IOException(e);
            } catch (IOException e) {
                if (closed.get() || !isRecoverableStreamException(e)
                        || recoveries >= FMP4_STREAM_RECOVERY_ATTEMPTS) {
                    throw e;
                }
                recoveries++;
                streamStartOffsetMillis = estimateCurrentOffsetMillis();
                logger().warn("Native video stream interrupted ({}), recovery {}/{} from ~{}ms",
                        e.getMessage(), recoveries, FMP4_STREAM_RECOVERY_ATTEMPTS, streamStartOffsetMillis);
                resetParserStateForRecovery();
            }
        }
    }

    /** 直播模式主循环：从样本总线取 AVCC 样本喂解码器，直到总线关闭或解码器被关闭。 */
    private void decodeLiveBusStream() throws IOException {
        com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus bus = com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus
                .find(liveBusKey);
        if (bus == null) {
            throw new IOException("直播视频总线不存在: " + liveBusKey);
        }
        logger().debug("直播视频总线解码开始: key={} output={} target={}x{}", liveBusKey, outputFormat,
                targetWidth, targetHeight);
        byte[] activeConfig = null;
        while (!closed.get()) {
            com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus.VideoSample sample;
            try {
                sample = bus.poll(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (sample == null) {
                if (bus.isClosed()) {
                    logger().debug("直播视频总线已关闭，解码结束: key={} frames={}", liveBusKey,
                            decodePump.totalFrames());
                    return;
                }
                continue;
            }
            if (sample.avcConfig() != activeConfig) {
                DecoderConfig config = Fmp4VideoDecoderConfigParser.parseAvcC(sample.avcConfig());
                if (config == null || config.packetPrefix().length == 0) {
                    throw new IOException("直播视频 avcC 无效: bytes="
                            + (sample.avcConfig() != null ? sample.avcConfig().length : 0));
                }
                decodePump.configure(config);
                decodePump.resetAfterRecovery();
                activeConfig = sample.avcConfig();
                logger().debug("直播视频 avcC 已应用: key={} configBytes={} nalLengthSize={}", liveBusKey,
                        config.packetPrefix().length, config.nalLengthSize());
            }
            decodePump.decodeSample(sample.data(), sample.ptsNanos());
        }
    }

    private void parseStreamOnce(long offsetMillis) throws IOException, UnsupportedAudioFileException {
        Fmp4VideoStreamSeeker.StreamStart seekStart = streamSeeker.open(offsetMillis);
        InputStream stream = trackInput(seekStart.stream());
        Throwable streamFailure = null;
        try {
            logger().debug("视频 fMP4 解码流开始: offset={}ms fragment={}s residual={}s output={} target={}x{} codecId={}",
                    offsetMillis, seekStart.fragmentSeconds(), seekStart.residualSeconds(), outputFormat,
                    targetWidth, targetHeight, codecId);
            decodePump.setSeekWindow(seekStart.residualSeconds(), seekStart.fragmentSeconds(), offsetMillis);
            Fmp4StreamParser.ContainerKind containerKind = new Fmp4StreamParser().parse(stream, closed,
                    new Fmp4StreamParser.Callback() {
                        @Override
                        public void onMoov(Fmp4ToMp4Converter.ParseResult parseResult, byte[] moovData)
                                throws IOException {
                            DecoderConfig config = extractDecoderConfig(moovData, codecId);
                            if (config == null || codecId == CODEC_H264 && config.packetPrefix().length == 0) {
                                throw new IOException("无法从 moov/stsd 提取视频 decoder config: codecId=" + codecId);
                            }
                            decodePump.configure(config);
                            int videoTimescale = Fmp4ToMp4Converter.parseVideoTimescale(moovData);
                            streamTimescale = videoTimescale > 0 ? videoTimescale : parseResult.timescale;
                            logger().debug(
                                    "视频 fMP4 moov 已解析: configBytes={} nalLengthSize={} timescale={} moovBytes={}",
                                    config.packetPrefix().length, config.nalLengthSize(), streamTimescale,
                                    moovData.length);
                        }

                        @Override
                        public void onMoof(int[] sampleSizes, byte[] moofData) {
                            Fmp4ToMp4Converter.SampleTable table = Fmp4ToMp4Converter.extractSampleTableFromMoof(
                                    moofData, streamTimescale > 0 ? streamTimescale : fps, fps);
                            pendingSampleSizes = table.sampleSizes().length > 0
                                    ? table.sampleSizes()
                                    : sampleSizes != null ? sampleSizes : new int[0];
                            pendingSamplePtsNanos = table.ptsNanos();
                            pendingSampleIndex = 0;
                            parsedMoofCount++;
                            decodePump.setParsedMoofCount(parsedMoofCount);
                            parsedSampleCount += pendingSampleSizes.length;
                            if (parsedMoofCount <= 2) {
                                logger().debug(
                                        "视频 fMP4 moof 已解析: index={} samples={} firstSampleBytes={} firstPtsNanos={}",
                                        parsedMoofCount, pendingSampleSizes.length,
                                        pendingSampleSizes.length > 0 ? pendingSampleSizes[0] : 0,
                                        pendingSamplePtsNanos.length > 0 ? pendingSamplePtsNanos[0] : -1L);
                            }
                        }

                        @Override
                        public void onMdat(InputStream payload, long size) throws IOException {
                            if (!decodePump.isConfigured()) {
                                throw new IOException("mdat arrived before video decoder config");
                            }
                            if (pendingSampleSizes.length == 0) {
                                logger().debug("视频 fMP4 mdat 无样本表: bytes={}", size);
                                byte[] all = Fmp4StreamParser.readFully(payload, size);
                                decodePump.decodeSample(all, nextSamplePtsNanos());
                                return;
                            }
                            if (parsedMoofCount <= 2) {
                                logger().debug("视频 fMP4 mdat 开始: bytes={} samples={} parsedSamples={}", size,
                                        pendingSampleSizes.length, parsedSampleCount);
                            }
                            for (int sampleSize : pendingSampleSizes) {
                                if (closed.get() || decodePump.totalFrames() >= maxFrames) {
                                    return;
                                }
                                if (sampleSize <= 0) {
                                    continue;
                                }
                                byte[] sample = Fmp4StreamParser.readFully(payload, sampleSize);
                                decodePump.decodeSample(sample, nextSamplePtsNanos());
                            }
                        }

                        @Override
                        public void onRawEac3(InputStream payload) throws IOException, UnsupportedAudioFileException {
                            throw new UnsupportedAudioFileException("video stream is not fMP4 video");
                        }
                    });
            if (containerKind == Fmp4StreamParser.ContainerKind.OTHER_AUDIO) {
                throw new UnsupportedAudioFileException("video stream is not fMP4 video");
            }
        } catch (IOException | UnsupportedAudioFileException | RuntimeException | Error error) {
            streamFailure = error;
            throw error;
        } finally {
            CompletableFuture<Void> closeOutcome = closeInputTracked(stream);
            try {
                closeOutcome.join();
            } catch (java.util.concurrent.CompletionException closeError) {
                if (streamFailure != null) {
                    streamFailure.addSuppressed(closeError.getCause());
                } else {
                    throw new IOException("native video input close failed", closeError.getCause());
                }
            }
        }
    }

    private InputStream trackInput(InputStream stream) throws IOException {
        InputStream tracked = trackedInputs.track(stream);
        if (closed.get()) {
            trackedInputs.beginClose();
            throw new IOException("native video decoder closed");
        }
        return tracked;
    }

    private CompletableFuture<Void> closeInputTracked(InputStream stream) {
        return trackedInputs.closeAsync(stream);
    }

    private long estimateCurrentOffsetMillis() {
        return decodePump.estimateCurrentOffsetMillis(totalMillis);
    }

    private void resetParserStateForRecovery() {
        pendingSampleSizes = new int[0];
        pendingSamplePtsNanos = new long[0];
        pendingSampleIndex = 0;
        streamTimescale = 0;
        decodePump.resetAfterRecovery();
    }

    private static boolean isRecoverableStreamException(IOException error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof EOFException) {
                return true;
            }
            if (current instanceof IOException && current.getMessage() != null) {
                String message = current.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (message.contains("closed") || message.contains("eof reached")
                        || message.contains("ended early")) {
                    return true;
                }
            }
        }
        return false;
    }

    private long nextSamplePtsNanos() {
        int index = pendingSampleIndex++;
        if (pendingSamplePtsNanos != null && index >= 0 && index < pendingSamplePtsNanos.length) {
            return pendingSamplePtsNanos[index];
        }
        return -1L;
    }

    static DecoderConfig extractDecoderConfig(byte[] moovData, int codecId) {
        return Fmp4VideoDecoderConfigParser.extract(moovData, codecId);
    }

    static byte[] parseAv1ConfigObus(byte[] av1C) {
        return Fmp4VideoDecoderConfigParser.parseAv1ConfigObus(av1C);
    }

    @Override
    public void close() {
        requestClose();
        Thread thread = worker;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                logger().warn("Native video decoder worker did not stop within 2000ms: {}", videoUrl);
            }
        }
        if (thread == null || !thread.isAlive()) {
            schedulePhysicalTermination(thread);
        }
        decodePump.releaseResources();
    }

    /** Requests cancellation without waiting for the decoder worker to exit. */
    public void requestClose() {
        Thread thread;
        synchronized (this) {
            closed.set(true);
            decodePump.cancelProbe();
            thread = worker;
            if (thread != null) {
                thread.interrupt();
            }
        }
        // Every stream opened during init/range/seek is registered before use.
        // Provider close calls stay off the cancellation caller thread.
        trackedInputs.beginClose();
        if (thread == null || !thread.isAlive()) {
            schedulePhysicalTermination(thread);
        }
    }

    private void signalCancel() {
        requestClose();
    }

    /**
     * Completes only after the native decoder worker has exited and its decoder
     * handle is closed.
     */
    public CompletableFuture<Void> terminationFuture() {
        return termination.copy();
    }

    private void schedulePhysicalTermination(Thread workerToObserve) {
        trackedInputs.beginClose();
        if (!physicalCloseScheduled.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<Void> closeTask = MediaCloseExecutor.closeAsyncStrict(() -> {
            awaitWorkerExit(workerToObserve);
            completeTerminationAfterDecoderClose();
        }, "native video physical termination");
        closeTask.whenComplete((ignored, error) -> {
            if (error != null) {
                termination.completeExceptionally(error);
            }
        });
    }

    private static void awaitWorkerExit(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void completeTerminationAfterDecoderClose() {
        trackedInputs.beginClose();
        closeDecoderOnce();
        CompletableFuture<Void> physicalClose = CompletableFuture.allOf(
                decoderCloseCompletion, trackedInputs.completionSnapshot());
        physicalClose.whenComplete((ignored, error) -> {
            if (error == null) {
                termination.complete(null);
            } else {
                termination.completeExceptionally(error);
            }
        });
    }

    private void closeDecoderOnce() {
        DecoderCloseState current = decoderCloseState.get();
        if (current != DecoderCloseState.OPEN) {
            return;
        }
        if (!decoderCloseState.compareAndSet(DecoderCloseState.OPEN, DecoderCloseState.CLOSING)) {
            return;
        }
        Throwable closeFailure = closeWithBoundedRetry(decoder::close, DECODER_CLOSE_MAX_ATTEMPTS,
                DECODER_CLOSE_INITIAL_BACKOFF_MILLIS, Thread::sleep,
                (attempt, delayMillis, error) -> logger().warn(
                        "Native video decoder handle close attempt {}/{} failed; retrying in {}ms",
                        attempt, DECODER_CLOSE_MAX_ATTEMPTS, delayMillis, error));
        if (closeFailure == null) {
            decoderCloseState.set(DecoderCloseState.CLOSED);
            decoderCloseCompletion.complete(null);
        } else {
            decoderCloseState.set(DecoderCloseState.FAILED);
            decoderCloseCompletion.completeExceptionally(closeFailure);
            logger().error("Native video decoder handle close failed after {} attempts; termination is exceptional",
                    DECODER_CLOSE_MAX_ATTEMPTS, closeFailure);
        }
    }

    static Throwable closeWithBoundedRetry(CloseOperation operation, int maxAttempts,
            long initialBackoffMillis, RetrySleeper sleeper, CloseRetryListener retryListener) {
        return NativeDecoderCloseRetry.close(operation, maxAttempts, initialBackoffMillis, sleeper, retryListener);
    }

    @FunctionalInterface
    interface CloseOperation extends NativeDecoderCloseRetry.CloseOperation {
    }

    @FunctionalInterface
    interface RetrySleeper extends NativeDecoderCloseRetry.RetrySleeper {
    }

    @FunctionalInterface
    interface CloseRetryListener extends NativeDecoderCloseRetry.CloseRetryListener {
    }

    @FunctionalInterface
    interface InputCloseScheduler extends NativeVideoTrackedInputs.InputCloseScheduler {
    }

    static final class TrackedInputRegistry extends NativeVideoTrackedInputs {
        TrackedInputRegistry() {
            super();
        }

        TrackedInputRegistry(InputCloseScheduler closeScheduler) {
            super(closeScheduler);
        }
    }

    private enum DecoderCloseState {
        OPEN,
        CLOSING,
        CLOSED,
        FAILED
    }

    record DecoderConfig(int nalLengthSize, byte[] packetPrefix) {
    }

    public enum OutputFormat {
        RGBA,
        YUV420P,
        NV12
    }

    public static final class DecodedFrame implements AutoCloseable {
        public enum Format {
            RGBA,
            YUV420P,
            NV12
        }

        private final byte[] data;
        private final ByteBuffer buffer;
        private final int byteLength;
        private final Format format;
        private final SharedRelease release;
        private long ptsNanos;
        private long nativeGetNanos;
        private long queueWaitNanos;
        private long probeTicket = -1L;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        DecodedFrame(byte[] data, Format format, Runnable release) {
            this(data, null, data != null ? data.length : 0, format, release, -1L, -1L, -1L);
        }

        DecodedFrame(ByteBuffer buffer, int byteLength, Format format, Runnable release) {
            this(null, buffer, byteLength, format, release, -1L, -1L, -1L);
        }

        private DecodedFrame(byte[] data, ByteBuffer buffer, int byteLength, Format format, Runnable release,
                long ptsNanos, long nativeGetNanos, long queueWaitNanos) {
            this(data, buffer, byteLength, format, new SharedRelease(release), ptsNanos, nativeGetNanos,
                    queueWaitNanos);
        }

        private DecodedFrame(byte[] data, ByteBuffer buffer, int byteLength, Format format, SharedRelease release,
                long ptsNanos, long nativeGetNanos, long queueWaitNanos) {
            this.data = data;
            this.buffer = buffer;
            this.byteLength = Math.max(0, byteLength);
            this.format = format != null ? format : Format.RGBA;
            this.release = release;
            this.ptsNanos = ptsNanos;
            this.nativeGetNanos = nativeGetNanos;
            this.queueWaitNanos = queueWaitNanos;
        }

        static DecodedFrame wrap(byte[] rgba) {
            return wrap(rgba, Format.RGBA);
        }

        static DecodedFrame wrap(byte[] data, Format format) {
            return data != null ? new DecodedFrame(data, format, null) : null;
        }

        public byte[] data() {
            if (data == null && buffer != null) {
                ByteBuffer src = bufferSlice();
                byte[] copy = new byte[src.remaining()];
                src.get(copy);
                return copy;
            }
            return data;
        }

        public ByteBuffer buffer() {
            return bufferSlice();
        }

        public int byteLength() {
            if (byteLength > 0) {
                return byteLength;
            }
            return data != null ? data.length : 0;
        }

        private ByteBuffer bufferSlice() {
            if (buffer == null) {
                return null;
            }
            ByteBuffer duplicate = buffer.duplicate();
            duplicate.position(0);
            duplicate.limit(Math.min(buffer.capacity(), byteLength()));
            return duplicate.slice().order(buffer.order());
        }

        public Format format() {
            return format;
        }

        public byte[] rgba() {
            if (format != Format.RGBA) {
                throw new IllegalStateException("decoded frame is " + format + ", not RGBA");
            }
            return data;
        }

        public long ptsNanos() {
            return ptsNanos;
        }

        public long nativeGetNanos() {
            return nativeGetNanos;
        }

        public long queueWaitNanos() {
            return queueWaitNanos;
        }

        long probeTicket() {
            return probeTicket;
        }

        DecodedFrame withPtsNanos(long ptsNanos) {
            this.ptsNanos = ptsNanos;
            return this;
        }

        DecodedFrame withNativeGetNanos(long nativeGetNanos) {
            this.nativeGetNanos = nativeGetNanos;
            return this;
        }

        DecodedFrame withQueueWaitNanos(long queueWaitNanos) {
            this.queueWaitNanos = queueWaitNanos;
            return this;
        }

        DecodedFrame withProbeTicket(long probeTicket) {
            this.probeTicket = probeTicket;
            return this;
        }

        @SuppressWarnings("resource") // Ownership of the retained frame is transferred to the caller.
        public DecodedFrame retain() {
            if (closed.get() || !release.tryRetain()) {
                throw new IllegalStateException("decoded frame is already closed");
            }
            return new DecodedFrame(data, buffer, byteLength, format, release, ptsNanos, nativeGetNanos,
                    queueWaitNanos).withProbeTicket(probeTicket);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.release();
            }
        }

        private static final class SharedRelease {
            private final AtomicInteger references = new AtomicInteger(1);
            private final Runnable action;

            private SharedRelease(Runnable action) {
                this.action = action;
            }

            private boolean tryRetain() {
                int current;
                do {
                    current = references.get();
                    if (current == 0) {
                        return false;
                    }
                } while (!references.compareAndSet(current, current + 1));
                return true;
            }

            private void release() {
                if (references.decrementAndGet() == 0 && action != null) {
                    action.run();
                }
            }
        }
    }

    private static Logger logger() {
        return LoggerHolder.INSTANCE;
    }

    private static final class LoggerHolder {
        private static final Logger INSTANCE = LogUtils.getLogger();

        private LoggerHolder() {
        }
    }
}
