package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.stream.ChunkPrefetchInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4RangeSeekSupport;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeClient;
import org.slf4j.Logger;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java fMP4/DASH demux + FFmpeg JNI video decoder.
 */
public final class Fmp4NativeVideoDecoder implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CODEC_H264 = 7;
    private static final int CODEC_HEVC = 12;
    private static final int MAX_PENDING_FRAMES = Integer.getInteger("bili.video.native.max_pending_frames", 8);
    private static final int FMP4_INIT_PROBE_BYTES = Integer.getInteger("bili.video.native.seek.init_probe_bytes",
            4 * 1024 * 1024);
    private static final int FMP4_MOOF_SCAN_BYTES = Integer.getInteger("bili.video.native.seek.moof_scan_bytes",
            8 * 1024 * 1024);
    private static final int FMP4_SEEK_MAX_ATTEMPTS = Integer.getInteger("bili.video.native.seek.max_attempts", 12);
    private static final long FMP4_SEEK_PREROLL_BYTES = Long.getLong("bili.video.native.seek.preroll_bytes",
            1L * 1024L * 1024L);
    private static final double FMP4_CLOSE_FRAGMENT_SECONDS = Double.parseDouble(
            System.getProperty("bili.video.native.seek.close_fragment_seconds", "3.0"));
    private static final double FMP4_TARGET_EPSILON_SECONDS = Double.parseDouble(
            System.getProperty("bili.video.native.seek.target_epsilon_seconds", "0.25"));
    private static final double FMP4_SEEK_LEAD_SECONDS = Double.parseDouble(
            System.getProperty("bili.video.native.seek.lead_seconds", "0.0"));
    private static final boolean FMP4_RANGE_SEEK_ENABLED = Boolean.parseBoolean(
            System.getProperty("bili.video.native.seek.enabled", "false"));
    private static final long FMP4_RANGE_SEEK_AUTO_OFFSET_MILLIS = Long.getLong(
            "bili.video.native.seek.auto_offset_ms", 5_000L);
    private static final double FMP4_FALLBACK_MAX_RESIDUAL_SECONDS = Double.parseDouble(
            System.getProperty("bili.video.native.seek.fallback_max_residual_seconds", "-1.0"));
    private static final int FMP4_STREAM_RECOVERY_ATTEMPTS = Integer.getInteger(
            "bili.video.native.stream_recovery_attempts", 3);
    private static final byte[] DECODE_ONLY_FRAME = new byte[0];
    private static final boolean REUSE_OUTPUT_BUFFERS = Boolean.parseBoolean(
            System.getProperty("bili.video.native.reuse_output_buffers", "true"));
    private static final long SEGMENT_BASE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final int MAX_SEGMENT_BASE_ENTRIES = Integer.getInteger(
            "bili.video.segment_base_cache.max_entries", 512);
    private static final ConcurrentHashMap<String, SegmentBaseInfo> SEGMENT_BASE_BY_URL = new ConcurrentHashMap<>();

    private final URL videoUrl;
    private final int codecId;
    private final int targetWidth;
    private final int targetHeight;
    private final int maxFrames;
    private final boolean outputFrames;
    private final boolean outputYuv420;
    private final long startOffsetMillis;
    private final long totalMillis;
    private final int fps;
    private final BlockingQueue<DecodedFrame> frames = new ArrayBlockingQueue<>(MAX_PENDING_FRAMES);
    private final BlockingQueue<byte[]> reusableBuffers = new ArrayBlockingQueue<>(MAX_PENDING_FRAMES);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean decoderClosed = new AtomicBoolean(false);
    private final VideoNativeDecoder decoder;
    private final HttpRangeClient http = new HttpRangeClient();

    private byte[] decoderConfig;
    private int nalLengthSize = 4;
    private int[] pendingSampleSizes = new int[0];
    private long[] pendingSamplePtsNanos = new long[0];
    private int pendingSampleIndex;
    private boolean sentConfig;
    private int totalFrames;
    private int fallbackFramesToDrop;
    private long timelineStartNanos;
    private long dropBeforeMediaPtsNanos;
    private long lastDecodedMediaPtsNanos = -1L;
    private int streamTimescale;
    private final java.util.ArrayDeque<Long> pendingDecodedPtsNanos = new java.util.ArrayDeque<>();
    private volatile IOException failure;
    private Thread worker;

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
        this.videoUrl = URI.create(videoUrl).toURL();
        this.codecId = codecId == CODEC_HEVC ? CODEC_HEVC : CODEC_H264;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.maxFrames = Math.max(1, maxFrames);
        this.outputFrames = outputFrames;
        this.outputYuv420 = outputYuv420;
        this.startOffsetMillis = Math.max(0L, startOffsetMillis);
        this.totalMillis = Math.max(0L, totalMillis);
        this.fps = Math.max(1, fps);
        this.decoder = new VideoNativeDecoder(this.codecId, targetWidth, targetHeight);
        if (requestedHwaccel != null) {
            this.decoder.setRequestedHwaccel(requestedHwaccel);
        }
    }

    public static void registerSegmentBase(String videoUrl, long initStart, long initEnd, long indexStart,
            long indexEnd) {
        if (videoUrl == null || videoUrl.isBlank() || initStart < 0L || initEnd < initStart
                || indexStart < 0L || indexEnd < indexStart) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanupSegmentBaseInfo(now);
        SEGMENT_BASE_BY_URL.put(videoUrl, new SegmentBaseInfo(initStart, initEnd, indexStart, indexEnd, now));
    }

    public byte[] getNextFrame() throws IOException {
        DecodedFrame frame = getNextDecodedFrame();
        if (frame == null) {
            return null;
        }
        try {
            return Arrays.copyOf(frame.rgba(), frame.rgba().length);
        } finally {
            frame.close();
        }
    }

    public DecodedFrame getNextDecodedFrame() throws IOException {
        ensureStarted();
        while (true) {
            DecodedFrame frame;
            try {
                frame = frames.poll(250L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (closed.get() || finished.get()) {
                    return null;
                }
                throw new IOException("等待 native 视频帧时被中断", e);
            }
            if (frame != null) {
                return frame;
            }
            if (finished.get()) {
                if (failure != null) {
                    throw failure;
                }
                return null;
            }
            if (closed.get()) {
                return null;
            }
        }
    }

    public int getTotalFrames() {
        return totalFrames;
    }

    private void ensureStarted() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(() -> {
            try {
                parseAndDecode();
            } catch (IOException e) {
                failure = e;
            } finally {
                finished.set(true);
                closed.set(true);
                closeDecoderOnce();
            }
        }, "bili-native-video-decoder");
        worker.setDaemon(true);
        worker.start();
    }

    private void parseAndDecode() throws IOException {
        long streamStartOffsetMillis = startOffsetMillis;
        int recoveries = 0;
        while (!closed.get() && totalFrames < maxFrames) {
            try {
                parseStreamOnce(streamStartOffsetMillis);
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
                LOGGER.warn("Native video stream interrupted ({}), recovery {}/{} from ~{}ms",
                        e.getMessage(), recoveries, FMP4_STREAM_RECOVERY_ATTEMPTS, streamStartOffsetMillis);
                resetParserStateForRecovery();
            }
        }
    }

    private void parseStreamOnce(long offsetMillis) throws IOException, UnsupportedAudioFileException {
        Fmp4StreamStart seekStart = openStreamStart(offsetMillis);
        try (InputStream stream = seekStart.stream()) {
            fallbackFramesToDrop = Math.max(0, Math.round(seekStart.residualSeconds() * fps));
            timelineStartNanos = Math.max(0L, Math.round(seekStart.fragmentSeconds() * 1_000_000_000.0D));
            dropBeforeMediaPtsNanos = Math.max(0L, offsetMillis * 1_000_000L);
            new Fmp4StreamParser().parse(stream, closed, new Fmp4StreamParser.Callback() {
                @Override
                public void onMoov(Fmp4ToMp4Converter.ParseResult parseResult, byte[] moovData)
                        throws IOException {
                    DecoderConfig config = extractDecoderConfig(moovData, codecId);
                    if (config == null || config.annexBConfig().length == 0) {
                        throw new IOException("无法从 moov/stsd 提取视频 decoder config: codecId=" + codecId);
                    }
                    decoderConfig = config.annexBConfig();
                    nalLengthSize = config.nalLengthSize();
                    int videoTimescale = Fmp4ToMp4Converter.parseVideoTimescale(moovData);
                    streamTimescale = videoTimescale > 0 ? videoTimescale : parseResult.timescale;
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
                }

                @Override
                public void onMdat(InputStream payload, long size) throws IOException {
                    if (decoderConfig == null) {
                        throw new IOException("mdat arrived before video decoder config");
                    }
                    if (pendingSampleSizes.length == 0) {
                        byte[] all = Fmp4StreamParser.readFully(payload, size);
                        decodeSample(all, nextSamplePtsNanos());
                        return;
                    }
                    for (int sampleSize : pendingSampleSizes) {
                        if (closed.get() || totalFrames >= maxFrames) {
                            return;
                        }
                        if (sampleSize <= 0) {
                            continue;
                        }
                        byte[] sample = Fmp4StreamParser.readFully(payload, sampleSize);
                        decodeSample(sample, nextSamplePtsNanos());
                    }
                }

                @Override
                public void onRawEac3(InputStream payload) throws IOException, UnsupportedAudioFileException {
                    throw new UnsupportedAudioFileException("video stream is not fMP4 video");
                }
            });
        }
    }

    private long estimateCurrentOffsetMillis() {
        if (lastDecodedMediaPtsNanos >= 0L) {
            long offset = lastDecodedMediaPtsNanos / 1_000_000L;
            return totalMillis > 0L ? Math.min(totalMillis, offset) : offset;
        }
        long decodedMillis = Math.round(totalFrames * 1000.0D / fps);
        long offset = Math.max(0L, startOffsetMillis + decodedMillis);
        return totalMillis > 0L ? Math.min(totalMillis, offset) : offset;
    }

    private void resetParserStateForRecovery() {
        pendingSampleSizes = new int[0];
        pendingSamplePtsNanos = new long[0];
        pendingSampleIndex = 0;
        pendingDecodedPtsNanos.clear();
        fallbackFramesToDrop = 0;
        timelineStartNanos = 0L;
        dropBeforeMediaPtsNanos = 0L;
        streamTimescale = 0;
        sentConfig = false;
        decoder.flush();
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

    private Fmp4StreamStart openStreamStart(long offsetMillis) throws IOException {
        if (offsetMillis <= 1_000L) {
            HttpRangeClient.CdnResponse response = http.get(videoUrl);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.close();
                throw new IOException("DASH video HTTP " + response.statusCode());
            }
            return new Fmp4StreamStart(response.body(), 0.0F, 0.0D);
        }

        boolean shouldTryRangeSeek = FMP4_RANGE_SEEK_ENABLED
                || offsetMillis >= Math.max(1_001L, FMP4_RANGE_SEEK_AUTO_OFFSET_MILLIS);
        if (shouldTryRangeSeek) {
            Fmp4StreamStart ranged = tryOpenRangeSeek(offsetMillis);
            if (ranged != null) {
                return ranged;
            }
        }
        double requestedResidualSeconds = offsetMillis / 1000.0D;
        float fallbackResidualSeconds = (float) Math.max(0.0D,
                FMP4_FALLBACK_MAX_RESIDUAL_SECONDS >= 0.0D
                        ? Math.min(requestedResidualSeconds, FMP4_FALLBACK_MAX_RESIDUAL_SECONDS)
                        : requestedResidualSeconds);
        HttpRangeClient.CdnResponse response = http.get(videoUrl);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.close();
            throw new IOException("DASH video HTTP " + response.statusCode());
        }
        return new Fmp4StreamStart(response.body(), fallbackResidualSeconds, 0.0D);
    }

    private Fmp4StreamStart tryOpenRangeSeek(long offsetMillis) {
        InputStream lastRange = null;
        long seekStartNanos = System.nanoTime();
        try {
            Fmp4RangeSeekSupport.InitSegment init = readInitSegment();
            long contentLength = init.contentLength();
            if (contentLength <= 0L) {
                return null;
            }
            float playbackSeconds = offsetMillis / 1000.0F;
            double durationSeconds = totalMillis > 0L ? totalMillis / 1000.0D
                    : Math.max(playbackSeconds + FMP4_SEEK_LEAD_SECONDS + 60.0D, 1.0D);
            float targetSeconds = seekTargetSeconds(playbackSeconds, durationSeconds);
            Fmp4StreamStart sidxStart = tryOpenSidxSeek(init, targetSeconds, playbackSeconds, seekStartNanos);
            if (sidxStart != null) {
                return sidxStart;
            }
            long estimatedOffset = Math.min(contentLength - 1L,
                    Math.max(0L, Math.round(contentLength * Math.min(0.98D, targetSeconds / durationSeconds))));
            long rangeStart = Math.max(init.bytes().length, estimatedOffset - FMP4_SEEK_PREROLL_BYTES);
            int timescale = init.timescale() > 0 ? init.timescale() : 16_000;
            for (int attempt = 0; attempt < FMP4_SEEK_MAX_ATTEMPTS; attempt++) {
                ChunkRange range = openRange(rangeStart);
                lastRange = range.stream();
                Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(range.stream(),
                        targetSeconds, timescale, FMP4_MOOF_SCAN_BYTES, FMP4_TARGET_EPSILON_SECONDS,
                        FMP4_CLOSE_FRAGMENT_SECONDS);
                if (probe == null) {
                    closeQuietly(lastRange);
                    lastRange = null;
                    long nextStart = Math.min(contentLength - 1L, rangeStart + FMP4_MOOF_SCAN_BYTES);
                    if (attempt + 1 >= FMP4_SEEK_MAX_ATTEMPTS || nextStart <= rangeStart) {
                        return null;
                    }
                    rangeStart = nextStart;
                    continue;
                }
                Fmp4RangeSeekSupport.MoofCandidate candidate = probe.candidate();
                long absoluteMoofOffset = rangeStart + candidate.offset();
                if (attempt + 1 < FMP4_SEEK_MAX_ATTEMPTS
                        && Fmp4RangeSeekSupport.isAfterTargetCandidate(candidate, targetSeconds,
                                FMP4_TARGET_EPSILON_SECONDS)) {
                    long nextStart = Math.max(init.bytes().length,
                            absoluteMoofOffset - FMP4_MOOF_SCAN_BYTES - FMP4_SEEK_PREROLL_BYTES);
                    if (nextStart < rangeStart) {
                        closeQuietly(lastRange);
                        lastRange = null;
                        rangeStart = nextStart;
                        continue;
                    }
                }
                if (attempt + 1 < FMP4_SEEK_MAX_ATTEMPTS
                        && Fmp4RangeSeekSupport.shouldRetry(candidate, targetSeconds,
                                FMP4_TARGET_EPSILON_SECONDS, FMP4_CLOSE_FRAGMENT_SECONDS)) {
                    long nextStart = Fmp4RangeSeekSupport.nextRangeStart(candidate, targetSeconds, durationSeconds,
                            contentLength, absoluteMoofOffset, init.bytes().length, FMP4_SEEK_PREROLL_BYTES);
                    if (Math.abs(nextStart - rangeStart) > FMP4_SEEK_PREROLL_BYTES) {
                        closeQuietly(lastRange);
                        lastRange = null;
                        rangeStart = nextStart;
                        continue;
                    }
                }
                float residualSeconds = Fmp4RangeSeekSupport.residualSeconds(targetSeconds, candidate,
                        durationSeconds, contentLength, absoluteMoofOffset);
                residualSeconds = Math.max(0.0F, residualSeconds - (targetSeconds - playbackSeconds));
                InputStream tail = new SequenceInputStream(
                        new ByteArrayInputStream(probe.bytes(), candidate.offset(),
                                probe.bytes().length - candidate.offset()),
                        range.stream());
                lastRange = null;
                InputStream combined = new SequenceInputStream(new ByteArrayInputStream(init.bytes()), tail);
                LOGGER.info(
                        "视频fMP4 RangeSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} probe={}ms host={}",
                        playbackSeconds, candidate.fragmentSeconds(), residualSeconds, startOffsetMillis / 1000.0D,
                        absoluteMoofOffset, contentLength, (System.nanoTime() - seekStartNanos) / 1_000_000L,
                        videoUrl.getHost());
                return new Fmp4StreamStart(combined, residualSeconds,
                        Double.isNaN(candidate.fragmentSeconds()) ? 0.0D : candidate.fragmentSeconds());
            }
            return null;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Native video fMP4 range seek unavailable: {}", e.getMessage());
            closeQuietly(lastRange);
            return null;
        }
    }

    private Fmp4StreamStart tryOpenSidxSeek(Fmp4RangeSeekSupport.InitSegment init, float targetSeconds,
            float playbackSeconds,
            long seekStartNanos) {
        SegmentBaseInfo info = segmentBaseInfo(videoUrl.toString());
        if (info == null) {
            return null;
        }
        try (HttpRangeClient.CdnResponse response = http.getRange(videoUrl, info.indexStart(), info.indexEnd())) {
            int status = response.statusCode();
            if (status != 206 && status != 200) {
                return null;
            }
            byte[] sidxBytes = readAllBytes(response.body(), Math.max(1L, info.indexEnd() - info.indexStart() + 1L));
            Fmp4RangeSeekSupport.SidxIndex sidx = Fmp4RangeSeekSupport.parseSidx(sidxBytes, info.indexStart());
            if (sidx == null || sidx.entries().isEmpty()) {
                return null;
            }
            Fmp4RangeSeekSupport.SidxEntry selected = null;
            for (Fmp4RangeSeekSupport.SidxEntry entry : sidx.entries()) {
                if (entry.timeSeconds() <= targetSeconds + 0.05D) {
                    selected = entry;
                } else {
                    break;
                }
            }
            if (selected == null) {
                selected = sidx.entries().get(0);
            }
            ChunkRange range = openRange(selected.byteStart());
            InputStream stream = range.stream();
            Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(stream, targetSeconds,
                    init.timescale() > 0 ? init.timescale() : 16_000, FMP4_MOOF_SCAN_BYTES,
                    FMP4_TARGET_EPSILON_SECONDS, FMP4_CLOSE_FRAGMENT_SECONDS);
            if (probe == null) {
                closeQuietly(stream);
                return null;
            }
            Fmp4RangeSeekSupport.MoofCandidate candidate = probe.candidate();
            double fragmentSeconds = !Double.isNaN(candidate.fragmentSeconds()) ? candidate.fragmentSeconds()
                    : selected.timeSeconds();
            float residualSeconds = (float) Math.max(0.0D, targetSeconds - fragmentSeconds);
            residualSeconds = Math.max(0.0F, residualSeconds - (targetSeconds - playbackSeconds));
            InputStream tail = new SequenceInputStream(
                    new ByteArrayInputStream(probe.bytes(), candidate.offset(),
                            probe.bytes().length - candidate.offset()),
                    stream);
            InputStream combined = new SequenceInputStream(new ByteArrayInputStream(init.bytes()), tail);
            LOGGER.info(
                    "视频fMP4 SidxSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} probe={}ms host={}",
                    playbackSeconds, fragmentSeconds, residualSeconds, startOffsetMillis / 1000.0D,
                    selected.byteStart(), init.contentLength(), (System.nanoTime() - seekStartNanos) / 1_000_000L,
                    videoUrl.getHost());
            return new Fmp4StreamStart(combined, residualSeconds, fragmentSeconds);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Native video fMP4 sidx seek unavailable: {}", e.getMessage());
            return null;
        }
    }

    private static byte[] readAllBytes(InputStream stream, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes, 1024 * 1024L));
        byte[] buffer = new byte[64 * 1024];
        long remaining = maxBytes;
        while (remaining > 0L) {
            int n = stream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                break;
            }
            if (n == 0) {
                continue;
            }
            out.write(buffer, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    private Fmp4RangeSeekSupport.InitSegment readInitSegment() throws IOException {
        try (HttpRangeClient.CdnResponse response = http.getRange(videoUrl, 0L, FMP4_INIT_PROBE_BYTES - 1L)) {
            int status = response.statusCode();
            if (status != 206 && status != 200) {
                throw new IOException("HTTP " + status + " while probing fMP4 init segment");
            }
            long contentLength = response.totalLength() > 0L ? response.totalLength() : response.contentLength();
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            Fmp4RangeSeekSupport.InitSegment init = null;
            while (prefix.size() < FMP4_INIT_PROBE_BYTES) {
                int request = Math.min(buffer.length, FMP4_INIT_PROBE_BYTES - prefix.size());
                int n = response.body().read(buffer, 0, request);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                prefix.write(buffer, 0, n);
                init = Fmp4RangeSeekSupport.extractInitSegment(prefix.toByteArray(), contentLength,
                        (moovPayload, moov) -> {
                            int videoTimescale = Fmp4ToMp4Converter.parseVideoTimescale(moovPayload);
                            return videoTimescale > 0 ? videoTimescale : moov.timescale;
                        });
                if (init != null) {
                    return init;
                }
            }
            throw new IOException("unable to read complete fMP4 init segment");
        }
    }

    private ChunkRange openRange(long start) throws IOException {
        return new ChunkRange(new ChunkPrefetchInputStream(videoUrl, start));
    }

    private static float seekTargetSeconds(float playbackSeconds, double durationSeconds) {
        if (FMP4_SEEK_LEAD_SECONDS <= 0.0D) {
            return playbackSeconds;
        }
        double upperBound = durationSeconds > 0.0D ? Math.max(0.0D, durationSeconds - 1.0D)
                : playbackSeconds + FMP4_SEEK_LEAD_SECONDS;
        return (float) Math.max(0.0D, Math.min(upperBound, playbackSeconds + FMP4_SEEK_LEAD_SECONDS));
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private record Fmp4StreamStart(InputStream stream, float residualSeconds, double fragmentSeconds) {
    }

    private static SegmentBaseInfo segmentBaseInfo(String url) {
        SegmentBaseInfo info = SEGMENT_BASE_BY_URL.get(url);
        if (info == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - info.createdAtMillis() > SEGMENT_BASE_TTL_MILLIS) {
            SEGMENT_BASE_BY_URL.remove(url, info);
            return null;
        }
        return info;
    }

    private static void cleanupSegmentBaseInfo(long now) {
        if (SEGMENT_BASE_BY_URL.isEmpty()) {
            return;
        }
        SEGMENT_BASE_BY_URL.entrySet().removeIf(
                entry -> now - entry.getValue().createdAtMillis() > SEGMENT_BASE_TTL_MILLIS);
        int maxEntries = Math.max(1, MAX_SEGMENT_BASE_ENTRIES);
        while (SEGMENT_BASE_BY_URL.size() > maxEntries) {
            String oldestKey = null;
            long oldestCreatedAt = Long.MAX_VALUE;
            for (var entry : SEGMENT_BASE_BY_URL.entrySet()) {
                long createdAt = entry.getValue().createdAtMillis();
                if (createdAt < oldestCreatedAt) {
                    oldestCreatedAt = createdAt;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            SEGMENT_BASE_BY_URL.remove(oldestKey);
        }
    }

    private record SegmentBaseInfo(long initStart, long initEnd, long indexStart, long indexEnd,
            long createdAtMillis) {
    }

    private record ChunkRange(InputStream stream) {
    }

    private long nextSamplePtsNanos() {
        int index = pendingSampleIndex++;
        if (pendingSamplePtsNanos != null && index >= 0 && index < pendingSamplePtsNanos.length) {
            return pendingSamplePtsNanos[index];
        }
        return -1L;
    }

    private void decodeSample(byte[] mp4Sample, long samplePtsNanos) throws IOException {
        if (mp4Sample.length == 0 || totalFrames >= maxFrames) {
            return;
        }
        ByteArrayOutputStream annexB = new ByteArrayOutputStream(mp4Sample.length + decoderConfig.length + 32);
        if (!sentConfig || isKeyframeSample(mp4Sample, codecId, nalLengthSize)) {
            annexB.write(decoderConfig, 0, decoderConfig.length);
            sentConfig = true;
        }
        writeLengthPrefixedSampleAsAnnexB(mp4Sample, nalLengthSize, annexB);
        byte[] packet = annexB.toByteArray();
        if (!decoder.sendPacket(packet, samplePtsNanos)) {
            throw new IOException(
                    "VideoNativeDecoder.sendPacket failed codecId=" + codecId + ", packet=" + packet.length);
        }
        pendingDecodedPtsNanos.addLast(samplePtsNanos);
        drainFrames();
    }

    private void drainFrames() {
        if (!outputFrames) {
            drainFramesNoOutput();
            return;
        }
        while (totalFrames < maxFrames) {
            if (drainDropFrameNoOutput()) {
                continue;
            }
            DecodedFrame frame = outputYuv420 ? getNextYuv420Frame() : getNextRgbaFrame();
            if (frame == null) {
                return;
            }
            Long fallbackPtsNanos = pendingDecodedPtsNanos.isEmpty() ? null : pendingDecodedPtsNanos.removeFirst();
            long samplePtsNanos = decoder.lastFramePtsNanos();
            if (samplePtsNanos < 0L && fallbackPtsNanos != null) {
                samplePtsNanos = fallbackPtsNanos.longValue();
            }
            long mediaPtsNanos = samplePtsNanos >= 0L
                    ? samplePtsNanos
                    : timelineStartNanos + Math.round((totalFrames + 1) * 1_000_000_000.0D / fps);
            lastDecodedMediaPtsNanos = mediaPtsNanos;
            if (shouldDropDecodedFrame(mediaPtsNanos, samplePtsNanos >= 0L)) {
                frame.close();
                totalFrames++;
                continue;
            }
            long relativePtsNanos = mediaPtsNanos - startOffsetMillis * 1_000_000L;
            frame = frame.withPtsNanos(Math.max(0L, relativePtsNanos));
            while (!closed.get()) {
                try {
                    if (frames.offer(frame, 250L, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    closed.set(true);
                    return;
                }
            }
            if (closed.get()) {
                return;
            }
            totalFrames++;
        }
    }

    private boolean drainDropFrameNoOutput() {
        boolean shouldDropByPts = dropBeforeMediaPtsNanos > 0L
                && !pendingDecodedPtsNanos.isEmpty()
                && pendingDecodedPtsNanos.peekFirst() != null
                && pendingDecodedPtsNanos.peekFirst().longValue() >= 0L
                && pendingDecodedPtsNanos.peekFirst().longValue() + 1_000_000L < dropBeforeMediaPtsNanos;
        boolean shouldDropByFallback = fallbackFramesToDrop > 0;
        if (!shouldDropByPts && !shouldDropByFallback) {
            return false;
        }
        if (!decoder.receiveFrameNoCopy()) {
            return false;
        }
        Long fallbackPtsNanos = pendingDecodedPtsNanos.isEmpty() ? null : pendingDecodedPtsNanos.removeFirst();
        long samplePtsNanos = decoder.lastFramePtsNanos();
        if (samplePtsNanos < 0L && fallbackPtsNanos != null) {
            samplePtsNanos = fallbackPtsNanos.longValue();
        }
        if (samplePtsNanos >= 0L) {
            lastDecodedMediaPtsNanos = samplePtsNanos;
            if (samplePtsNanos + 1_000_000L >= dropBeforeMediaPtsNanos) {
                dropBeforeMediaPtsNanos = 0L;
            }
        }
        if (shouldDropByFallback && fallbackFramesToDrop > 0) {
            fallbackFramesToDrop--;
        }
        totalFrames++;
        return true;
    }

    private boolean shouldDropDecodedFrame(long mediaPtsNanos, boolean hasRealPts) {
        if (hasRealPts) {
            return dropBeforeMediaPtsNanos > 0L && mediaPtsNanos + 1_000_000L < dropBeforeMediaPtsNanos;
        }
        if (fallbackFramesToDrop > 0) {
            fallbackFramesToDrop--;
            return true;
        }
        return false;
    }

    private DecodedFrame getNextRgbaFrame() {
        if (!REUSE_OUTPUT_BUFFERS) {
            return DecodedFrame.wrap(decoder.getVideoFrame());
        }
        byte[] buffer = reusableBuffers.poll();
        byte[] output = buffer != null ? buffer : new byte[Math.max(1, targetWidth) * Math.max(1, targetHeight) * 4];
        if (!decoder.getVideoFrameInto(output)) {
            reusableBuffers.offer(output);
            return null;
        }
        return new DecodedFrame(output, () -> reusableBuffers.offer(output));
    }

    private DecodedFrame getNextYuv420Frame() {
        return DecodedFrame.wrap(decoder.getVideoFrameYuv420());
    }

    private void drainFramesNoOutput() {
        while (totalFrames < maxFrames) {
            if (!decoder.receiveFrameNoCopy()) {
                return;
            }
            while (!closed.get()) {
                try {
                    if (frames.offer(DecodedFrame.wrap(DECODE_ONLY_FRAME), 250L, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    closed.set(true);
                    return;
                }
            }
            if (closed.get()) {
                return;
            }
            totalFrames++;
        }
    }

    private static void writeLengthPrefixedSampleAsAnnexB(byte[] sample, int lengthSize, ByteArrayOutputStream out)
            throws IOException {
        int pos = 0;
        while (pos + lengthSize <= sample.length) {
            int nalSize = 0;
            for (int i = 0; i < lengthSize; i++) {
                nalSize = (nalSize << 8) | (sample[pos + i] & 0xFF);
            }
            pos += lengthSize;
            if (nalSize <= 0 || pos + nalSize > sample.length) {
                break;
            }
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(sample, pos, nalSize);
            pos += nalSize;
        }
    }

    private static boolean isKeyframeSample(byte[] sample, int codecId, int lengthSize) {
        int pos = 0;
        while (pos + lengthSize < sample.length) {
            int nalSize = 0;
            for (int i = 0; i < lengthSize; i++) {
                nalSize = (nalSize << 8) | (sample[pos + i] & 0xFF);
            }
            pos += lengthSize;
            if (nalSize <= 0 || pos + nalSize > sample.length) {
                return false;
            }
            int type = codecId == CODEC_HEVC
                    ? ((sample[pos] & 0x7E) >> 1)
                    : (sample[pos] & 0x1F);
            if (codecId == CODEC_HEVC) {
                if (type >= 16 && type <= 21) {
                    return true;
                }
            } else if (type == 5) {
                return true;
            }
            pos += nalSize;
        }
        return false;
    }

    private static DecoderConfig extractDecoderConfig(byte[] moovData, int codecId) {
        String configBox = codecId == CODEC_HEVC ? "hvcC" : "avcC";
        byte[] config = findBoxPayloadRecursive(moovData, configBox);
        if (config == null) {
            return null;
        }
        return codecId == CODEC_HEVC ? parseHvcC(config) : parseAvcC(config);
    }

    private static DecoderConfig parseAvcC(byte[] avcC) {
        if (avcC.length < 7) {
            return null;
        }
        int lengthSize = (avcC[4] & 0x03) + 1;
        int pos = 5;
        int spsCount = avcC[pos++] & 0x1F;
        List<byte[]> nalus = new ArrayList<>();
        for (int i = 0; i < spsCount && pos + 2 <= avcC.length; i++) {
            int len = readU16(avcC, pos);
            pos += 2;
            if (pos + len > avcC.length) {
                return null;
            }
            nalus.add(slice(avcC, pos, len));
            pos += len;
        }
        if (pos >= avcC.length) {
            return null;
        }
        int ppsCount = avcC[pos++] & 0xFF;
        for (int i = 0; i < ppsCount && pos + 2 <= avcC.length; i++) {
            int len = readU16(avcC, pos);
            pos += 2;
            if (pos + len > avcC.length) {
                return null;
            }
            nalus.add(slice(avcC, pos, len));
            pos += len;
        }
        return new DecoderConfig(lengthSize, toAnnexB(nalus));
    }

    private static DecoderConfig parseHvcC(byte[] hvcC) {
        if (hvcC.length < 23) {
            return null;
        }
        int lengthSize = (hvcC[21] & 0x03) + 1;
        int arrays = hvcC[22] & 0xFF;
        int pos = 23;
        List<byte[]> nalus = new ArrayList<>();
        for (int i = 0; i < arrays && pos + 3 <= hvcC.length; i++) {
            pos++; // array_completeness + NAL_unit_type
            int count = readU16(hvcC, pos);
            pos += 2;
            for (int j = 0; j < count && pos + 2 <= hvcC.length; j++) {
                int len = readU16(hvcC, pos);
                pos += 2;
                if (pos + len > hvcC.length) {
                    return null;
                }
                nalus.add(slice(hvcC, pos, len));
                pos += len;
            }
        }
        return new DecoderConfig(lengthSize, toAnnexB(nalus));
    }

    private static byte[] toAnnexB(List<byte[]> nalus) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] nalu : nalus) {
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(nalu, 0, nalu.length);
        }
        return out.toByteArray();
    }

    private static byte[] findBoxPayloadRecursive(byte[] data, String targetType) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 8) {
            int boxStart = buf.position();
            long size = buf.getInt() & 0xFFFFFFFFL;
            String type = read4cc(buf);
            int headerSize = 8;
            if (size == 1) {
                if (buf.remaining() < 8) {
                    return null;
                }
                size = buf.getLong();
                headerSize = 16;
            } else if (size == 0) {
                size = data.length - boxStart;
            }
            long payloadSize = size - headerSize;
            if (size < headerSize || payloadSize < 0 || boxStart + size > data.length) {
                return null;
            }
            int payloadStart = boxStart + headerSize;
            if (targetType.equals(type)) {
                return slice(data, payloadStart, (int) payloadSize);
            }
            if (isContainerBox(type) && payloadSize > 0 && payloadSize <= Integer.MAX_VALUE) {
                int childOffset = childPayloadOffset(type);
                if (childOffset < payloadSize) {
                    byte[] nested = slice(data, payloadStart + childOffset, (int) payloadSize - childOffset);
                    byte[] found = findBoxPayloadRecursive(nested, targetType);
                    if (found != null) {
                        return found;
                    }
                }
            }
            buf.position((int) (boxStart + size));
        }
        return null;
    }

    private static boolean isContainerBox(String type) {
        return switch (type) {
            case "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "moof", "traf", "mvex" -> true;
            case "stsd", "avc1", "avc3", "hvc1", "hev1" -> true;
            default -> false;
        };
    }

    private static int childPayloadOffset(String type) {
        return switch (type) {
            case "stsd" -> 8; // fullbox header + entry_count
            case "avc1", "avc3", "hvc1", "hev1" -> 78; // VisualSampleEntry fields before codec config boxes
            default -> 0;
        };
    }

    private static String read4cc(ByteBuffer buffer) {
        byte[] bytes = new byte[4];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    @Override
    public void close() {
        closed.set(true);
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(2_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (thread == null || thread == Thread.currentThread() || !thread.isAlive()) {
            closeDecoderOnce();
        }
        DecodedFrame frame;
        while ((frame = frames.poll()) != null) {
            frame.close();
        }
    }

    private void closeDecoderOnce() {
        if (decoderClosed.compareAndSet(false, true)) {
            decoder.close();
        }
    }

    private record DecoderConfig(int nalLengthSize, byte[] annexBConfig) {
    }

    public static final class DecodedFrame implements AutoCloseable {
        private final byte[] rgba;
        private final Runnable release;
        private final long ptsNanos;
        private boolean closed;

        private DecodedFrame(byte[] rgba, Runnable release) {
            this(rgba, release, -1L);
        }

        private DecodedFrame(byte[] rgba, Runnable release, long ptsNanos) {
            this.rgba = rgba;
            this.release = release;
            this.ptsNanos = ptsNanos;
        }

        static DecodedFrame wrap(byte[] rgba) {
            return rgba != null ? new DecodedFrame(rgba, null) : null;
        }

        public byte[] rgba() {
            return rgba;
        }

        public long ptsNanos() {
            return ptsNanos;
        }

        private DecodedFrame withPtsNanos(long ptsNanos) {
            return new DecodedFrame(rgba, release, ptsNanos);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (release != null) {
                    release.run();
                }
            }
        }
    }
}
