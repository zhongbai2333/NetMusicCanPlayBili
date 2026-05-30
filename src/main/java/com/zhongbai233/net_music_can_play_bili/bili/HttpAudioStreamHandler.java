package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.codec.Eac3NativeDecoder;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.AacOpenALPipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.AacPcmPipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.AudioDecodePipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.DolbyEc3Pipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.FlacOpenALPipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.FlacPcmPipeline;
import com.zhongbai233.net_music_can_play_bili.bili.stream.ChunkPrefetchInputStream;
import com.zhongbai233.net_music_can_play_bili.bili.stream.Fmp4StreamParser;
import com.zhongbai233.net_music_can_play_bili.bili.stream.BlockingAudioPipe;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class HttpAudioStreamHandler implements IAudioStreamHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PIPE_BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int FORMAT_WAIT_SECONDS = 15;
    private static final long ALLOWED_URL_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final ConcurrentHashMap<String, Long> ALLOWED_URLS = new ConcurrentHashMap<>();

    public static void allowUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanupAllowedUrls(now);
        ALLOWED_URLS.put(url, now + ALLOWED_URL_TTL_MILLIS);
    }

    @Override
    public boolean canHandle(URL url) {
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            return false;
        }
        if (url.getHost() == null || (url.getPath() != null && url.getPath().endsWith(".m3u8"))) {
            return false;
        }
        return true;
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        boolean modernTurntable = consumeAllowedUrl(url);

        try {
            return handleWithPipeline(url, modernTurntable);
        } catch (UnsupportedAudioFileException e) {
            // 非现代化唱片机 + 非 fMP4/EC-3 格式 → 透传给 Java Sound API（MP3 等）
            if (!modernTurntable && isNotFmp4Error(e)) {
                LOGGER.debug("Falling back to Java Sound API for non-fMP4 URL: {}", url);
                return AudioSystem.getAudioInputStream(url);
            }
            throw e;
        }
    }

    /**
     * 用 fMP4/EC-3 管线处理音频
     * 现代化唱片机 → OpenAL；普通唱片机 → PcmPipeline
     */
    private AudioInputStream handleWithPipeline(URL url, boolean modernTurntable)
            throws UnsupportedAudioFileException, IOException {
        LOGGER.debug("HTTP audio handler probing {}://{}/... mode={}",
                url.getProtocol(), url.getHost(), modernTurntable ? "modern-turntable" : "netmusic-compatible");

        BlockingAudioPipe fallbackPipe = new BlockingAudioPipe(PIPE_BUFFER_SIZE);
        AtomicReference<AudioDecodePipeline> pipelineRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        AtomicReference<InputStream> bodyRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        CountDownLatch formatReady = new CountDownLatch(1);

        Thread worker = new Thread(
                () -> streamDecode(url, fallbackPipe, pipelineRef, errorRef, bodyRef, closed, formatReady,
                        modernTurntable),
                modernTurntable ? "AudioStreamWorker" : "BiliCompatAudioStreamWorker");
        worker.setDaemon(true);
        worker.start();

        awaitFormat(url, closed, bodyRef, worker, formatReady);
        try {
            throwIfFailed(errorRef);
        } catch (IOException | UnsupportedAudioFileException e) {
            closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipelineRef.get());
            throw e;
        }

        AudioDecodePipeline pipeline = pipelineRef.get();
        if (pipeline == null) {
            closeWorker(url, closed, bodyRef, worker, fallbackPipe, null);
            throw new IOException("unable to detect audio format");
        }

        AudioFormat format = pipeline.format();
        LOGGER.debug("HTTP audio ready: container={} codec={} format={}Hz/{}ch/{}bit detail={}",
                pipeline.container(), pipeline.codec(), format.getSampleRate(), format.getChannels(),
                format.getSampleSizeInBits(), pipeline.detail());

        if (pipeline instanceof FlacPcmPipeline flacPipeline) {
            AudioInputStream decoded = flacPipeline.openDecodedStream();
            AudioFormat decodedFormat = decoded.getFormat();
            if (decodedFormat.getSampleSizeInBits() > 16) {
                AudioFormat fmt16 = new AudioFormat(decodedFormat.getSampleRate(), 16,
                        decodedFormat.getChannels(), true, false);
                LOGGER.info("FLAC Hi-Res enabled TPDF dither {}bit -> 16bit: {}Hz/{}ch",
                        decodedFormat.getSampleSizeInBits(), decodedFormat.getSampleRate(),
                        decodedFormat.getChannels());
                decoded = new AudioInputStream(
                        new PcmDitheringStream(decoded, decodedFormat, fmt16),
                        fmt16, AudioSystem.NOT_SPECIFIED);
            }
            return managedStream(decoded, closed, worker, url, bodyRef, fallbackPipe, pipeline);
        }
        if (pipeline instanceof FlacOpenALPipeline flacPipeline) {
            AudioInputStream tapped = flacPipeline.openTappedStream();
            return managedStream(tapped, closed, worker, url, bodyRef, fallbackPipe, pipeline);
        }
        if (pipeline.usesOpenAlOutput()) {
            return silentStream(format, closed, worker, url, bodyRef, fallbackPipe, pipeline);
        }
        return managedStream(
                new AudioInputStream(fallbackPipe, format, AudioSystem.NOT_SPECIFIED),
                closed, worker, url, bodyRef, fallbackPipe, pipeline);
    }

    private static boolean consumeAllowedUrl(URL url) {
        long now = System.currentTimeMillis();
        cleanupAllowedUrls(now);
        Long expiresAt = ALLOWED_URLS.remove(url.toString());
        return expiresAt != null && expiresAt >= now;
    }

    /** 判断异常是否表示"内容不是 fMP4/EC-3"，此时应透传给 Java Sound API。 */
    private static boolean isNotFmp4Error(UnsupportedAudioFileException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("not fMP4") || msg.contains("unsupported HTTP audio stream"));
    }

    private static void cleanupAllowedUrls(long now) {
        ALLOWED_URLS.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private static void streamDecode(
            URL url,
            BlockingAudioPipe fallbackPipe,
            AtomicReference<AudioDecodePipeline> pipelineRef,
            AtomicReference<Exception> errorRef,
            AtomicReference<InputStream> bodyRef,
            AtomicBoolean closed,
            CountDownLatch formatReady,
            boolean modernTurntable) {
        long[] decoded = { 0L };
        long[] mdatBytes = { 0L };

        try (InputStream body = new ChunkPrefetchInputStream(url)) {
            bodyRef.set(body);
            if (closed.get()) {
                return;
            }
            Fmp4StreamParser parser = new Fmp4StreamParser();
            parser.parse(body, closed, new Fmp4StreamParser.Callback() {
                @Override
                public void onMoov(Fmp4ToMp4Converter.ParseResult parseResult, byte[] moovData)
                        throws IOException, UnsupportedAudioFileException {
                    if (pipelineRef.get() != null) {
                        return;
                    }
                    AudioDecodePipeline pipeline = createPipeline(url, parseResult, moovData, fallbackPipe, closed,
                            modernTurntable);
                    activatePipeline(url, pipelineRef, pipeline, formatReady);
                }

                @Override
                public void onMoof(int[] sampleSizes, byte[] moofData) throws IOException {
                    AudioDecodePipeline pipeline = pipelineRef.get();
                    if (pipeline != null) {
                        pipeline.onMoof(sampleSizes);
                    }
                }

                @Override
                public void onMdat(InputStream payload, long size) throws IOException {
                    AudioDecodePipeline pipeline = pipelineRef.get();
                    if (pipeline == null) {
                        Fmp4StreamParser.skipFully(payload, size);
                        return;
                    }
                    decoded[0] += pipeline.onMdat(payload, size);
                    mdatBytes[0] += Math.max(0L, size);
                }

                @Override
                public void onRawEac3(InputStream payload) throws IOException, UnsupportedAudioFileException {
                    DolbyEc3Pipeline pipeline = createRawDolbyPipeline(closed, modernTurntable);
                    activatePipeline(url, pipelineRef, pipeline, formatReady);
                    decoded[0] += pipeline.onRawStream(payload);
                }
            });

            AudioDecodePipeline pipeline = pipelineRef.get();
            LOGGER.debug("Audio stream finished: decoded={} mdatBytes={} {}",
                    decoded[0], mdatBytes[0], pipeline != null ? pipeline.statsSummary() : "");
        } catch (EOFException e) {
            LOGGER.debug("Audio stream EOF: decoded={} mdatBytes={}", decoded[0], mdatBytes[0]);
        } catch (IOException e) {
            if (closed.get() || isStreamEndException(e)) {
                LOGGER.debug("Audio stream stopped: closed={} msg={} decoded={} mdatBytes={}",
                        closed.get(), e.getMessage(), decoded[0], mdatBytes[0]);
                LOGGER.trace("Audio stream stop stack", e);
            } else {
                LOGGER.error("Audio stream IO failed", e);
                errorRef.set(e);
            }
        } catch (UnsupportedAudioFileException e) {
            if (!closed.get()) {
                LOGGER.warn("Audio stream unsupported: {}", e.getMessage());
                errorRef.set(e);
            }
        } catch (Exception e) {
            if (!closed.get()) {
                LOGGER.error("Audio stream decode failed", e);
                errorRef.set(e);
            }
        } finally {
            formatReady.countDown();
            AudioDecodePipeline pipeline = pipelineRef.get();
            if (pipeline != null) {
                try {
                    pipeline.finish();
                } catch (IOException e) {
                    if (!closed.get()) {
                        LOGGER.debug("Audio pipeline finish failed: {}", e.getMessage());
                    }
                }
            }
            closeBody(bodyRef);
            fallbackPipe.closeWriter();
        }
    }

    private static AudioDecodePipeline createPipeline(
            URL url,
            Fmp4ToMp4Converter.ParseResult parseResult,
            byte[] moovData,
            BlockingAudioPipe fallbackPipe,
            AtomicBoolean closed,
            boolean modernTurntable) throws IOException, UnsupportedAudioFileException {
        if ("ec-3".equals(parseResult.audioCodec)) {
            if (modernTurntable && BiliConfig.dolbyEnabled && Eac3NativeDecoder.isNativeAvailable()) {
                return new DolbyEc3Pipeline("fMP4", closed);
            }
            throw new UnsupportedAudioFileException(
                    "EC-3 requires modern turntable Dolby playback and native decoder support");
        }
        if (parseResult.flacDfLa != null) {
            return modernTurntable
                    ? new FlacOpenALPipeline(parseResult.flacDfLa.clone(), closed)
                    : new FlacPcmPipeline(parseResult.flacDfLa.clone(), fallbackPipe);
        }
        if (parseResult.asc != null) {
            return modernTurntable
                    ? new AacOpenALPipeline(parseResult.asc.clone(), closed)
                    : new AacPcmPipeline(parseResult.asc.clone(), fallbackPipe);
        }
        String codecs = Fmp4ToMp4Converter.listAudioCodecs(moovData);
        LOGGER.warn("fMP4 moov did not contain a supported audio track. found={}", codecs);
        throw new UnsupportedAudioFileException("unsupported fMP4 audio codec: " + codecs);
    }

    private static DolbyEc3Pipeline createRawDolbyPipeline(AtomicBoolean closed, boolean modernTurntable)
            throws UnsupportedAudioFileException {
        if (!modernTurntable || !BiliConfig.dolbyEnabled || !Eac3NativeDecoder.isNativeAvailable()) {
            throw new UnsupportedAudioFileException("raw E-AC-3 requires Dolby playback and native decoder support");
        }
        return new DolbyEc3Pipeline("raw", closed);
    }

    private static void activatePipeline(
            URL url,
            AtomicReference<AudioDecodePipeline> pipelineRef,
            AudioDecodePipeline pipeline,
            CountDownLatch formatReady) {
        if (!pipelineRef.compareAndSet(null, pipeline)) {
            pipeline.close();
            return;
        }
        BiliPlaybackDiagnostics.updateFormat(url, pipeline.container(), pipeline.codec(),
                pipeline.format(), pipeline.detail());
        formatReady.countDown();
        LOGGER.debug("Pipeline selected: container={} codec={} detail={}",
                pipeline.container(), pipeline.codec(), pipeline.detail());
    }

    private static AudioInputStream silentStream(
            AudioFormat format,
            AtomicBoolean closed,
            Thread worker,
            URL url,
            AtomicReference<InputStream> bodyRef,
            BlockingAudioPipe fallbackPipe,
            AudioDecodePipeline pipeline) {
        return new AudioInputStream(fallbackPipe, format, AudioSystem.NOT_SPECIFIED) {
            @Override
            public int read() {
                return closed.get() ? -1 : 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (closed.get()) {
                    return -1;
                }
                if (len <= 0) {
                    return 0;
                }
                int fill = Math.min(len, b.length - off);
                Arrays.fill(b, off, off + fill, (byte) 0);
                return fill;
            }

            @Override
            public void close() throws IOException {
                closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipeline);
                super.close();
            }
        };
    }

    private static AudioInputStream managedStream(
            AudioInputStream delegate,
            AtomicBoolean closed,
            Thread worker,
            URL url,
            AtomicReference<InputStream> bodyRef,
            BlockingAudioPipe fallbackPipe,
            AudioDecodePipeline pipeline) {
        return new AudioInputStream(delegate, delegate.getFormat(), AudioSystem.NOT_SPECIFIED) {
            @Override
            public void close() throws IOException {
                IOException error = null;
                try {
                    closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipeline);
                } catch (IOException e) {
                    error = e;
                }
                try {
                    delegate.close();
                } catch (IOException e) {
                    if (error != null) {
                        error.addSuppressed(e);
                    } else {
                        error = e;
                    }
                }
                if (error != null) {
                    throw error;
                }
            }
        };
    }

    private static void awaitFormat(
            URL url,
            AtomicBoolean closed,
            AtomicReference<InputStream> bodyRef,
            Thread worker,
            CountDownLatch formatReady) throws IOException {
        try {
            if (!formatReady.await(FORMAT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                closed.set(true);
                closeBody(bodyRef);
                worker.interrupt();
                BiliPlaybackDiagnostics.markClosed(url);
                throw new IOException("timed out waiting for audio format");
            }
        } catch (InterruptedException e) {
            closed.set(true);
            closeBody(bodyRef);
            worker.interrupt();
            Thread.currentThread().interrupt();
            BiliPlaybackDiagnostics.markClosed(url);
            throw new IOException("interrupted while loading audio stream", e);
        }
    }

    private static void throwIfFailed(AtomicReference<Exception> errorRef)
            throws IOException, UnsupportedAudioFileException {
        Exception err = errorRef.get();
        if (err == null) {
            return;
        }
        if (err instanceof IOException io) {
            throw io;
        }
        if (err instanceof UnsupportedAudioFileException unsupported) {
            throw unsupported;
        }
        throw new IOException("Audio stream handling failed", err);
    }

    private static void closeWorker(
            URL url,
            AtomicBoolean closed,
            AtomicReference<InputStream> bodyRef,
            Thread worker,
            BlockingAudioPipe fallbackPipe,
            AudioDecodePipeline pipeline) throws IOException {
        if (closed.compareAndSet(false, true)) {
            BiliPlaybackDiagnostics.markClosed(url);
            closeBody(bodyRef);
            fallbackPipe.closeWriter();
            fallbackPipe.close();
            worker.interrupt();
            if (pipeline != null) {
                pipeline.close();
            }
        }
    }

    private static void closeBody(AtomicReference<InputStream> bodyRef) {
        InputStream body = bodyRef.getAndSet(null);
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isStreamEndException(IOException e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("closed") || msg.contains("EOF") || msg.contains("Stream Closed"))) {
            return true;
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof EOFException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
