package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.sync.AudioStartupSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMinecartAudioAnchors;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.client.api.implement.DirectHttpHandler;
import com.github.tartaricacid.netmusic.client.api.implement.NetEaseHttpHandler;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.codec.Eac3NativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AacOpenALPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AacPcmPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AudioDecodePipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.DolbyEc3Pipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.FlacOpenALPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.FlacPcmPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.ChunkPrefetchInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4RangeSeekSupport;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeHeaders;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeClient;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnUrlFallbacks;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedStreamRecoveryRegistry;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.LifecycleClose;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

public class HttpAudioStreamHandler implements IAudioStreamHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PIPE_BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int FORMAT_WAIT_SECONDS = Math.max(15,
            Integer.getInteger("ncpb.bili.media.format_wait_seconds", 30));
    private static final long WORKER_JOIN_TIMEOUT_MILLIS = 2_000L;
    private static final int MP3_SYNC_SCAN_BYTES = 512 * 1024;
    private static final int FMP4_INIT_PROBE_BYTES = 4 * 1024 * 1024;
    private static final int FMP4_MOOF_SCAN_BYTES = 2 * 1024 * 1024;
    private static final int FMP4_SEEK_MAX_ATTEMPTS = 3;
    private static final double FMP4_CLOSE_FRAGMENT_SECONDS = 15.0D;
    private static final double FMP4_TARGET_EPSILON_SECONDS = 0.05D;
    private static final int MAX_HTTP_REDIRECTS = 5;
    private static final long RANGE_SEEK_PREROLL_BYTES = 256 * 1024L;
    private static final long FMP4_SEEK_PREROLL_BYTES = 256 * 1024L;
    private static final long ALLOWED_URL_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long SEGMENT_BASE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final int RANGE_RACE_MAX_CANDIDATES = Math.max(1,
            Integer.getInteger("ncpb.bili.audio.range_race.max_candidates", 4));
    private static final long RANGE_RACE_TIMEOUT_MILLIS = Math.max(250L,
            Long.getLong("ncpb.bili.audio.range_race.timeout_ms", 2_500L));
    private static final int MAX_SEGMENT_BASE_ENTRIES = Integer.getInteger(
            "bili.audio.segment_base_cache.max_entries", 512);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    private static final ExecutorService RANGE_RACE_EXECUTOR = Executors.newFixedThreadPool(
            RANGE_RACE_MAX_CANDIDATES, NetMusicThreadFactory.daemon("bili-audio-range-race"));
    private static final ConcurrentHashMap<String, PlaybackContextQueue> ALLOWED_URLS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, SegmentBaseInfo> SEGMENT_BASE_BY_URL = new ConcurrentHashMap<>();
    private static final Set<ActiveStreamControl> ACTIVE_MODERN_STREAMS = ConcurrentHashMap.newKeySet();

    public static void allowUrl(String url) {
        allowUrl(url, null);
    }

    public static void registerSegmentBase(String audioUrl, long initStart, long initEnd, long indexStart,
            long indexEnd) {
        if (audioUrl == null || audioUrl.isBlank() || initStart < 0L || initEnd < initStart
                || indexStart < 0L || indexEnd < indexStart) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanupSegmentBaseInfo(now);
        SEGMENT_BASE_BY_URL.put(audioUrl, new SegmentBaseInfo(initStart, initEnd, indexStart, indexEnd, now));
    }

    public static void allowUrl(String url, BlockPos pos) {
        allowUrl(url, pos, null);
    }

    public static void allowUrl(String url, BlockPos pos, UUID ownerId) {
        if (url == null || url.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanupAllowedUrls(now);
        PlaybackSync.Metadata sync = PlaybackSync.parse(url);
        PlaybackSync.MinecartAnchor minecartAnchor = PlaybackSync.parseMinecartAnchor(url);
        PlaybackContext context = new PlaybackContext(
                AudioUtils.copyPos(pos),
                now + ALLOWED_URL_TTL_MILLIS,
                sync.sessionId(),
                sync.elapsedMillis(),
                sync.totalMillis(),
                ownerId,
                minecartAnchor != null ? minecartAnchor.entityUuid() : null,
                System.nanoTime());
        closeStaleModernStreams(context.pos(), context.sessionId(), context.minecartUuid());
        String key = contextKey(url);
        ALLOWED_URLS.compute(key, (ignored, queue) -> {
            PlaybackContextQueue contexts = queue != null ? queue : new PlaybackContextQueue();
            contexts.removeExpired(now);
            contexts.add(context);
            return contexts;
        });
    }

    public static void closeModernStreams() {
        for (ActiveStreamControl control : ACTIVE_MODERN_STREAMS) {
            control.close();
        }
        ACTIVE_MODERN_STREAMS.clear();
        ALLOWED_URLS.clear();
        SEGMENT_BASE_BY_URL.clear();
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
        if (hasAllowedContext(url)) {
            return true;
        }
        if (isNativeNetMusicHost(url)) {
            return false;
        }
        return isBiliCdnHost(url);
    }

    private static boolean isNativeNetMusicHost(URL url) {
        String host = url.getHost();
        if (host == null)
            return false;
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("music.163.com")
                || lower.contains("music.126.net");
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        PlaybackContext playbackContext = consumeAllowedUrl(url);
        URL requestUrl = PlaybackSync.strip(url);

        // 回退：当 side-channel 中找不到 context 时（如 URL 被重定向修改），
        // 直接从 URL fragment 中解析已播放偏移，确保 seek 不会丢失位置。
        if (playbackContext == null) {
            PlaybackSync.Metadata fallbackSync = PlaybackSync.parse(url.toString());
            if (fallbackSync.hasSession()) {
                playbackContext = new PlaybackContext(
                        null, 0L, fallbackSync.sessionId(),
                        fallbackSync.elapsedMillis(), fallbackSync.totalMillis(), null,
                        ClientMinecartAudioAnchors.entityUuid(fallbackSync.sessionId()), System.nanoTime());
            }
        }

        if (playbackContext != null && isNativeNetMusicHost(requestUrl)) {
            return fallbackHttpStream(requestUrl, playbackContext, null);
        }

        try {
            return handleWithPipeline(requestUrl, playbackContext);
        } catch (UnsupportedAudioFileException e) {
            // 非 fMP4/raw EC-3 内容应回到 NetMusic 的普通 HTTP 路径处理。
            if (isNotFmp4Error(e)) {
                return fallbackHttpStream(requestUrl, playbackContext, e);
            }
            BiliPlaybackDiagnostics.markFailed(requestUrl, e);
            throw e;
        } catch (IOException e) {
            BiliPlaybackDiagnostics.markFailed(requestUrl, e);
            throw e;
        }
    }

    /**
     * 用 fMP4/EC-3 管线处理音频
     * 现代化唱片机 → OpenAL；普通唱片机 → PcmPipeline
     */
    private AudioInputStream handleWithPipeline(URL url, PlaybackContext playbackContext)
            throws UnsupportedAudioFileException, IOException {
        long started = System.currentTimeMillis();
        boolean modernTurntable = playbackContext != null;
        final float startOffsetSeconds = startOffsetSeconds(playbackContext);

        BlockingAudioPipe fallbackPipe = new BlockingAudioPipe(PIPE_BUFFER_SIZE);
        AtomicReference<AudioDecodePipeline> pipelineRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        AtomicReference<InputStream> bodyRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        CountDownLatch formatReady = new CountDownLatch(1);

        Thread worker = NetMusicThreadFactory.daemonThread(
                modernTurntable ? "AudioStreamWorker" : "BiliCompatAudioStreamWorker",
                () -> streamDecode(url, fallbackPipe, pipelineRef, errorRef, bodyRef, closed, formatReady,
                        playbackContext, startOffsetSeconds));
        worker.start();
        ActiveStreamControl streamControl = null;
        if (modernTurntable && playbackContext != null) {
            streamControl = new ActiveStreamControl(url, playbackContext.pos(), playbackContext.sessionId(),
                    playbackContext.minecartUuid(), closed,
                    bodyRef, worker, fallbackPipe, pipelineRef, formatReady);
        }
        if (streamControl != null) {
            ACTIVE_MODERN_STREAMS.add(streamControl);
        }

        try {
            awaitFormat(url, closed, bodyRef, worker, formatReady);
            LOGGER.debug("HTTP 音频格式就绪: cost={}ms session={} offset={}s host={}",
                    System.currentTimeMillis() - started,
                    playbackContext != null ? playbackContext.sessionId() : "", startOffsetSeconds, url.getHost());
        } catch (IOException e) {
            closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipelineRef.get(), streamControl);
            throw e;
        }
        try {
            throwIfFailed(errorRef);
        } catch (IOException | UnsupportedAudioFileException e) {
            closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipelineRef.get(), streamControl);
            throw e;
        }

        AudioDecodePipeline pipeline = pipelineRef.get();
        if (pipeline == null) {
            closeWorker(url, closed, bodyRef, worker, fallbackPipe, null, streamControl);
            throw new IOException("unable to detect audio format");
        }

        AudioFormat format = pipeline.format();
        LOGGER.debug(
                "HTTP 音频管线摘要: mode={} session={} pos={} offset={}s total={}ms container={} codec={} format={}Hz/{}ch/{}bit detail={} host={}",
                modernTurntable ? "modern-turntable" : "compat",
                playbackContext != null ? playbackContext.sessionId() : "",
                playbackContext != null ? playbackContext.pos() : null, startOffsetSeconds, playbackContext != null
                        ? playbackContext.totalMillis()
                        : 0L,
                pipeline.container(), pipeline.codec(), format.getSampleRate(), format.getChannels(),
                format.getSampleSizeInBits(), pipeline.detail(), url.getHost());

        if (pipeline instanceof FlacPcmPipeline flacPipeline) {
            AudioInputStream decoded = flacPipeline.openDecodedStream();
            AudioFormat decodedFormat = decoded.getFormat();
            if (decodedFormat.getSampleSizeInBits() > 16) {
                AudioFormat fmt16 = new AudioFormat(decodedFormat.getSampleRate(), 16,
                        decodedFormat.getChannels(), true, false);
                LOGGER.debug("FLAC Hi-Res enabled TPDF dither {}bit -> 16bit: {}Hz/{}ch",
                        decodedFormat.getSampleSizeInBits(), decodedFormat.getSampleRate(),
                        decodedFormat.getChannels());
                decoded = new AudioInputStream(
                        new PcmDitheringStream(decoded, decodedFormat, fmt16),
                        fmt16, AudioSystem.NOT_SPECIFIED);
            }
            return managedStream(decoded, closed, worker, url, bodyRef, fallbackPipe, pipeline, streamControl);
        }
        if (pipeline instanceof FlacOpenALPipeline flacPipeline) {
            AudioInputStream tapped = flacPipeline.openTappedStream();
            return managedStream(tapped, closed, worker, url, bodyRef, fallbackPipe, pipeline, streamControl);
        }
        if (pipeline.usesOpenAlOutput()) {
            return silentStream(format, closed, worker, url, bodyRef, fallbackPipe, pipeline, streamControl);
        }
        return managedStream(
                new AudioInputStream(fallbackPipe, format, AudioSystem.NOT_SPECIFIED),
                closed, worker, url, bodyRef, fallbackPipe, pipeline, streamControl);
    }

    private static PlaybackContext consumeAllowedUrl(URL url) {
        long now = System.currentTimeMillis();
        cleanupAllowedUrls(now);
        String key = contextKey(url);
        AtomicReference<PlaybackContext> result = new AtomicReference<>();
        ALLOWED_URLS.computeIfPresent(key, (ignored, queue) -> {
            queue.removeExpired(now);
            result.set(queue.poll());
            return queue.isEmpty() ? null : queue;
        });
        return result.get();
    }

    private static float startOffsetSeconds(PlaybackContext context) {
        return context != null ? context.startOffsetSeconds() : 0f;
    }

    /** 判断异常是否表示"内容不是 fMP4/EC-3"，此时应透传给 Java Sound API。 */
    private static boolean isNotFmp4Error(UnsupportedAudioFileException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("not fMP4") || msg.contains("unsupported HTTP audio stream"));
    }

    private static AudioInputStream fallbackHttpStream(URL url, PlaybackContext playbackContext,
            UnsupportedAudioFileException probeError)
            throws UnsupportedAudioFileException, IOException {
        try {
            LOGGER.debug("Falling back to NetMusic direct HTTP handler for non-fMP4 URL: {}", url);
            float startOffsetSeconds = playbackContext != null ? playbackContext.startOffsetSeconds() : 0f;
            if (playbackContext != null && startOffsetSeconds > 1.0f && isNativeNetMusicHost(url)) {
                AudioInputStream ranged = tryOpenModernRangedMp3Stream(url, playbackContext);
                if (ranged != null) {
                    return ranged;
                }
            }

            NetEaseHttpHandler netEase = new NetEaseHttpHandler();
            AudioInputStream stream;
            if (netEase.canHandle(url)) {
                stream = netEase.handle(url);
            } else {
                stream = new DirectHttpHandler().handle(url);
            }
            return playbackContext != null
                    ? openModernFallbackStream(stream, playbackContext, startOffsetSeconds)
                    : applyStartOffset(stream, startOffsetSeconds);
        } catch (UnsupportedAudioFileException | IOException fallbackError) {
            if (probeError != null) {
                fallbackError.addSuppressed(probeError);
            }
            BiliPlaybackDiagnostics.markFailed(url, fallbackError);
            throw fallbackError;
        }
    }

    private static AudioInputStream tryOpenModernRangedMp3Stream(URL url, PlaybackContext playbackContext)
            throws IOException, UnsupportedAudioFileException {
        long elapsedMillis = Math.max(0L, playbackContext.elapsedMillis());
        long totalMillis = Math.max(0L, playbackContext.totalMillis());
        if (elapsedMillis <= 0L || totalMillis <= 0L || elapsedMillis >= totalMillis) {
            return null;
        }

        try {
            RangedResource resource = probeRangedResource(url);
            long contentLength = resource.contentLength();
            if (contentLength <= 0L) {
                LOGGER.debug("NetEase range seek unavailable: no content length for {}", url);
                return null;
            }

            double ratio = Math.max(0.0D, Math.min(0.98D, elapsedMillis / (double) totalMillis));
            long estimatedOffset = Math.min(contentLength - 1L, Math.max(0L, Math.round(contentLength * ratio)));
            long rangeOffset = Math.max(0L, estimatedOffset - RANGE_SEEK_PREROLL_BYTES);
            float residualSeconds = Math.max(0f,
                    (elapsedMillis - Math.round(totalMillis * (rangeOffset / (double) contentLength))) / 1000.0f);

            InputStream ranged = openHttpRangeStream(resource.url(), rangeOffset);
            InputStream aligned = alignMp3Frame(ranged, rangeOffset);
            BufferedInputStream buffered = new BufferedInputStream(aligned, MP3_SYNC_SCAN_BYTES);
            AudioInputStream stream = AudioSystem.getAudioInputStream(buffered);
            LOGGER.debug("NetEase range seek: offset={}s total={}s content={} byte={} residual={}s host={}",
                    playbackContext.startOffsetSeconds(), totalMillis / 1000.0f, contentLength, rangeOffset,
                    residualSeconds, resource.url().getHost());
            return openModernFallbackStream(stream, playbackContext, residualSeconds);
        } catch (UnsupportedAudioFileException | IOException e) {
            LOGGER.debug("NetEase range seek failed, falling back to decoded skip: {}", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            LOGGER.debug("NetEase range seek unavailable, falling back to decoded skip: {}", e.toString());
            return null;
        }
    }

    private static RangedResource probeRangedResource(URL url) throws IOException {
        try {
            HttpResponse<InputStream> response = sendHttpRequest(url, 0L, true, 0);
            InputStream body = response.body();
            try {
                int status = response.statusCode();
                if (status != 200 && status != 206) {
                    throw new IOException("HTTP " + status + " while probing audio length");
                }
                URL responseUrl = response.uri().toURL();
                java.util.Optional<Long> contentRangeTotal = response.headers().firstValue("Content-Range")
                        .flatMap(HttpRangeHeaders::parseContentRangeTotal);
                if (contentRangeTotal.isPresent()) {
                    return new RangedResource(responseUrl, contentRangeTotal.get());
                }
                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (status == 200 && contentLength > 0L) {
                    return new RangedResource(responseUrl, contentLength);
                }
                throw new IOException("missing total content length for ranged audio");
            } finally {
                if (body != null) {
                    body.close();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while probing audio length", e);
        }
    }

    private record RangedResource(URL url, long contentLength) {
    }

    private static InputStream openHttpRangeStream(URL url, long rangeOffset) throws IOException {
        try {
            HttpResponse<InputStream> response = sendHttpRequest(url, rangeOffset, false, 0);
            int status = response.statusCode();
            if (status == 206 || (rangeOffset == 0L && status == 200)) {
                InputStream body = response.body();
                if (body == null) {
                    throw new IOException("empty audio response body");
                }
                return body;
            }
            InputStream body = response.body();
            try {
                throw new IOException("HTTP range request ignored or failed: status=" + status + " offset="
                        + rangeOffset);
            } finally {
                if (body != null) {
                    body.close();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while opening audio range", e);
        }
    }

    private static HttpResponse<InputStream> sendHttpRequest(URL url, long rangeOffset, boolean probe, int redirects)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = HTTP_CLIENT.send(
                requestBuilder(url, rangeOffset, probe).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (!HttpRangeHeaders.isRedirectStatus(status)) {
            if (status == 200 || status == 206) {
                BiliCdnSelector.recordSuccess(response.uri().toString());
            }
            return response;
        }

        InputStream body = response.body();
        if (body != null) {
            body.close();
        }
        if (redirects >= MAX_HTTP_REDIRECTS) {
            throw new IOException("too many HTTP redirects while opening audio");
        }
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new IOException("HTTP " + status + " redirect without Location"));
        URL redirected = URI.create(url.toString()).resolve(location).toURL();
        LOGGER.debug("HTTP audio redirect: {} -> {}", url.getHost(), redirected.getHost());
        return sendHttpRequest(redirected, rangeOffset, probe, redirects + 1);
    }

    private static java.net.http.HttpRequest.Builder requestBuilder(URL url, long rangeOffset, boolean probe) {
        URL requestUrl;
        try {
            requestUrl = PlaybackSync.strip(url);
        } catch (java.net.MalformedURLException e) {
            requestUrl = url;
        }
        java.net.http.HttpRequest.Builder builder = HttpRangeHeaders.rangeRequest(requestUrl, rangeOffset, probe,
                java.time.Duration.ofSeconds(20));
        BiliRequestHeaders.applyBiliCdnHeaders(builder, requestUrl);
        return builder;
    }

    private static InputStream alignMp3Frame(InputStream stream, long rangeOffset)
            throws IOException, UnsupportedAudioFileException {
        if (rangeOffset <= 0L) {
            return stream;
        }
        byte[] probe = stream.readNBytes(MP3_SYNC_SCAN_BYTES);
        int sync = Mp3FrameSync.findFrameSync(probe, probe.length);
        if (sync < 0) {
            stream.close();
            throw new UnsupportedAudioFileException("unable to find MP3 frame after range seek");
        }
        LOGGER.debug("MP3 range aligned: byte={} frameOffset={}", rangeOffset, sync);
        return new SequenceInputStream(new ByteArrayInputStream(probe, sync, probe.length - sync), stream);
    }

    private static AudioInputStream openModernFallbackStream(AudioInputStream stream, PlaybackContext playbackContext,
            float startOffsetSeconds)
            throws UnsupportedAudioFileException, IOException {
        AudioInputStream pcm = toPcmStream(stream);
        StartupSeekResult seek = skipToCurrentPlayback(pcm, pcm.getFormat(), playbackContext, startOffsetSeconds);
        pcm = requireReadablePcm(pcm, "no decoded PCM after HTTP seek");
        StereoOpenALHandler stereo = new StereoOpenALHandler();
        stereo.setSampleRate((int) pcm.getFormat().getSampleRate());
        ClientAudioOutputRegistry.registerStereo(stereo, playbackContext.pos(), seek.timelineOffsetSeconds(),
                playbackContext.sessionId(), playbackContext.ownerId());
        LOGGER.debug(
                "HTTP 音频起播追赶完成: session={} captured={}ms effective={}ms setup={}ms passes={} offset={}s",
                playbackContext.sessionId(), playbackContext.elapsedMillis(), seek.timelineOffsetMillis(),
                AudioStartupSync.elapsedSinceCaptureMillis(playbackContext.capturedNanos(), System.nanoTime()),
                seek.passes(), startOffsetSeconds);
        return new OpenALTappedAudioInputStream(pcm, stereo, () -> {
            ClientAudioOutputRegistry.unregisterStereo(stereo);
            stereo.cleanup();
        });
    }

    private static StartupSeekResult skipToCurrentPlayback(AudioInputStream stream, AudioFormat format,
            PlaybackContext playbackContext, float initialSkipSeconds) throws IOException {
        long bytesPerSecond = Math.max(1L, Math.round(format.getSampleRate()) * (long) format.getFrameSize());
        long initialSkipBytes = skipBytes(format, initialSkipSeconds);
        long skippedBytes = 0L;
        int passes = 0;
        byte[] buffer = new byte[64 * 1024];
        while (passes < 4) {
            long setupMillis = AudioStartupSync.elapsedSinceCaptureMillis(playbackContext.capturedNanos(),
                    System.nanoTime());
            long targetBytes = saturatedAdd(initialSkipBytes, millisToBytes(bytesPerSecond, setupMillis));
            long remaining = Math.max(0L, targetBytes - skippedBytes);
            if (remaining <= bytesPerSecond / 20L) {
                break;
            }
            long skippedThisPass = skipBestEffort(stream, remaining, buffer);
            skippedBytes = saturatedAdd(skippedBytes, skippedThisPass);
            passes++;
            if (skippedThisPass < remaining) {
                break;
            }
        }
        long compensatedBytes = Math.max(0L, skippedBytes - initialSkipBytes);
        long compensatedMillis = Math.round(compensatedBytes * 1000.0D / bytesPerSecond);
        long timelineOffsetMillis = AudioStartupSync.compensatedOffsetMillis(playbackContext.elapsedMillis(),
                playbackContext.totalMillis(), compensatedMillis);
        return new StartupSeekResult(timelineOffsetMillis, passes);
    }

    private static AudioInputStream applyStartOffset(AudioInputStream stream, float startOffsetSeconds)
            throws UnsupportedAudioFileException, IOException {
        if (startOffsetSeconds <= 0f) {
            return stream;
        }
        AudioInputStream pcm = toPcmStream(stream);
        skipBestEffort(pcm, skipBytes(pcm.getFormat(), startOffsetSeconds));
        return pcm;
    }

    private static AudioInputStream requireReadablePcm(AudioInputStream stream, String message) throws IOException {
        byte[] first = new byte[32 * 1024];
        int read;
        do {
            read = stream.read(first);
        } while (read == 0);
        if (read < 0) {
            throw new EOFException(message);
        }
        return new AudioInputStream(
                new SequenceInputStream(new ByteArrayInputStream(first, 0, read), stream),
                stream.getFormat(),
                AudioSystem.NOT_SPECIFIED);
    }

    private static AudioInputStream toPcmStream(AudioInputStream stream)
            throws UnsupportedAudioFileException, IOException {
        AudioFormat sourceFormat = stream.getFormat();
        float sampleRate = sourceFormat.getSampleRate();
        int channels = Math.max(1, sourceFormat.getChannels());
        if (sampleRate <= 0.0F) {
            return stream;
        }
        AudioFormat pcmFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false);
        return AudioSystem.getAudioInputStream(pcmFormat, stream);
    }

    private static long skipBytes(AudioFormat format, float startOffsetSeconds) {
        if (startOffsetSeconds <= 0f) {
            return 0L;
        }
        return Math.round(format.getSampleRate() * startOffsetSeconds) * (long) format.getFrameSize();
    }

    private static void skipBestEffort(AudioInputStream stream, long bytesToSkip) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        skipBestEffort(stream, bytesToSkip, buffer);
    }

    private static long skipBestEffort(AudioInputStream stream, long bytesToSkip, byte[] buffer) throws IOException {
        long remaining = Math.max(0L, bytesToSkip);
        while (remaining > 0L) {
            int n = stream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                break;
            }
            remaining -= n;
        }
        return Math.max(0L, bytesToSkip) - remaining;
    }

    private static long millisToBytes(long bytesPerSecond, long millis) {
        if (bytesPerSecond <= 0L || millis <= 0L) {
            return 0L;
        }
        double bytes = bytesPerSecond * (millis / 1000.0D);
        return bytes >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(bytes);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void cleanupAllowedUrls(long now) {
        ALLOWED_URLS.forEach((key, queue) -> ALLOWED_URLS.computeIfPresent(key, (ignored, existing) -> {
            existing.removeExpired(now);
            return existing.isEmpty() ? null : existing;
        }));
    }

    private static String contextKey(String value) {
        return PlaybackSync.strip(value);
    }

    private static String contextKey(URL url) {
        return contextKey(url.toString());
    }

    private static final class PlaybackContextQueue {
        private final ArrayDeque<PlaybackContext> contexts = new ArrayDeque<>();

        private void add(PlaybackContext context) {
            contexts.offerLast(context);
        }

        private PlaybackContext poll() {
            return contexts.pollFirst();
        }

        private void removeExpired(long now) {
            while (!contexts.isEmpty() && contexts.peekFirst().expiresAtMillis() < now) {
                contexts.pollFirst();
            }
        }

        private boolean isEmpty() {
            return contexts.isEmpty();
        }
    }

    private static Fmp4StreamStart openFmp4StreamStart(URL url, PlaybackContext playbackContext,
            float startOffsetSeconds) throws IOException {
        if (playbackContext != null && startOffsetSeconds > 1.0f && playbackContext.totalMillis() > 0L) {
            Fmp4StreamStart ranged = tryOpenFmp4RangeSeek(url, playbackContext, startOffsetSeconds);
            if (ranged != null) {
                return ranged;
            }
        }
        return new Fmp4StreamStart(openPrefetchWithCdnFallback(url, 0L), startOffsetSeconds);
    }

    private static ChunkPrefetchInputStream openPrefetchWithCdnFallback(URL primary, long startByteOffset)
            throws IOException {
        List<URL> candidates = CdnUrlFallbacks.candidates(primary);
        IOException lastError = null;
        for (int i = 0; i < candidates.size(); i++) {
            URL candidate = candidates.get(i);
            try {
                return new ChunkPrefetchInputStream(candidate, startByteOffset);
            } catch (ChunkPrefetchInputStream.EmptyCdnResponseException e) {
                lastError = e;
                if (i + 1 < candidates.size()) {
                    LOGGER.warn("Audio CDN returned empty body, retrying alternate host {} -> {} offset={}: {}",
                            candidate.getHost(), candidates.get(i + 1).getHost(), startByteOffset, e.getMessage());
                    continue;
                }
            }
        }
        throw lastError != null ? lastError : new IOException("no CDN URL candidates available");
    }

    private static Fmp4StreamStart tryOpenFmp4RangeSeek(URL url, PlaybackContext playbackContext,
            float targetSeconds) {
        ChunkPrefetchInputStream lastRange = null;
        try {
            long started = System.currentTimeMillis();
            Fmp4RangeSeekSupport.InitSegment init = readFmp4InitSegment(url);
            long contentLength = init.contentLength();
            if (contentLength <= 0L) {
                return null;
            }

            Fmp4StreamStart sidxStart = tryOpenFmp4SidxSeek(url, playbackContext, init, targetSeconds);
            if (sidxStart != null) {
                LOGGER.debug("音频fMP4 seek 总耗时: mode=sidx cost={}ms target={}s host={}",
                        System.currentTimeMillis() - started, targetSeconds, url.getHost());
                return sidxStart;
            }

            long elapsedMillis = Math.max(0L, playbackContext.elapsedMillis());
            long totalMillis = Math.max(1L, playbackContext.totalMillis());
            double ratio = Math.max(0.0D, Math.min(0.98D, elapsedMillis / (double) totalMillis));
            long estimatedOffset = Math.min(contentLength - 1L, Math.max(0L, Math.round(contentLength * ratio)));
            long rangeStart = Math.max(init.bytes().length, estimatedOffset - FMP4_SEEK_PREROLL_BYTES);

            int timescale = init.timescale() > 0 ? init.timescale() : 48000;
            for (int attempt = 0; attempt < FMP4_SEEK_MAX_ATTEMPTS; attempt++) {
                ChunkPrefetchInputStream range = openPrefetchWithCdnFallback(url, rangeStart);
                lastRange = range;
                try {
                    Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(range, targetSeconds,
                            timescale, FMP4_MOOF_SCAN_BYTES, FMP4_TARGET_EPSILON_SECONDS,
                            FMP4_CLOSE_FRAGMENT_SECONDS);
                    if (probe == null) {
                        closeQuietly(range);
                        lastRange = null;
                        long nextStart = Math.min(contentLength - 1L, rangeStart + FMP4_MOOF_SCAN_BYTES);
                        if (attempt + 1 >= FMP4_SEEK_MAX_ATTEMPTS || nextStart <= rangeStart) {
                            return null;
                        }
                        rangeStart = nextStart;
                        continue;
                    }

                    byte[] probeBytes = probe.bytes();
                    Fmp4RangeSeekSupport.MoofCandidate candidate = probe.candidate();
                    long absoluteMoofOffset = rangeStart + candidate.offset();
                    if (attempt + 1 < FMP4_SEEK_MAX_ATTEMPTS
                            && Fmp4RangeSeekSupport.shouldRetry(candidate, targetSeconds,
                                    FMP4_TARGET_EPSILON_SECONDS, FMP4_CLOSE_FRAGMENT_SECONDS)) {
                        long nextStart = Fmp4RangeSeekSupport.nextRangeStart(candidate, targetSeconds,
                                totalMillis / 1000.0D, contentLength, absoluteMoofOffset, init.bytes().length,
                                FMP4_SEEK_PREROLL_BYTES);
                        if (Math.abs(nextStart - rangeStart) > FMP4_SEEK_PREROLL_BYTES) {
                            closeQuietly(range);
                            lastRange = null;
                            rangeStart = nextStart;
                            continue;
                        }
                    }

                    if (Fmp4RangeSeekSupport.isAfterTargetCandidate(candidate, targetSeconds,
                            FMP4_TARGET_EPSILON_SECONDS)) {
                        LOGGER.debug(
                                "fMP4 range seek candidate is after target: target={}s fragment={}s byte={} attemptsExhausted=true; falling back to decoded skip",
                                targetSeconds, candidate.fragmentSeconds(), absoluteMoofOffset);
                        return null;
                    }

                    float residualSeconds = Fmp4RangeSeekSupport.residualSeconds(targetSeconds, candidate,
                            totalMillis / 1000.0D,
                            contentLength, absoluteMoofOffset);
                    InputStream tail = new SequenceInputStream(
                            new ByteArrayInputStream(probeBytes, candidate.offset(),
                                    probeBytes.length - candidate.offset()),
                            range);
                    lastRange = null;
                    InputStream combined = new SequenceInputStream(new ByteArrayInputStream(init.bytes()), tail);
                    LOGGER.debug(
                            "音频fMP4 RangeSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} cost={}ms host={}",
                            targetSeconds, candidate.fragmentSeconds(), residualSeconds,
                            playbackContext.startOffsetSeconds(), absoluteMoofOffset, contentLength,
                            System.currentTimeMillis() - started, url.getHost());
                    return new Fmp4StreamStart(combined, residualSeconds);
                } finally {
                    if (lastRange == range) {
                        closeQuietly(range);
                        lastRange = null;
                    }
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("fMP4 range seek unavailable, falling back to decoded skip: {}", e.getMessage());
            closeQuietly(lastRange);
            return null;
        }
    }

    private static Fmp4StreamStart tryOpenFmp4SidxSeek(URL url, PlaybackContext playbackContext,
            Fmp4RangeSeekSupport.InitSegment init,
            float targetSeconds) {
        SegmentBaseInfo info = segmentBaseInfo(url.toString());
        if (info == null) {
            return null;
        }
        try {
            byte[] sidxBytes = readRangeBytes(url, info.indexStart(), info.indexEnd());
            Fmp4RangeSeekSupport.SidxIndex sidx = Fmp4RangeSeekSupport.parseSidx(sidxBytes, info.indexStart());
            if (sidx == null || sidx.entries().isEmpty()) {
                return null;
            }
            Fmp4RangeSeekSupport.SidxEntry selected = null;
            for (Fmp4RangeSeekSupport.SidxEntry entry : sidx.entries()) {
                if (entry.timeSeconds() > targetSeconds + FMP4_TARGET_EPSILON_SECONDS) {
                    break;
                }
                if (entry.startsWithSap()) {
                    selected = entry;
                }
            }
            if (selected == null) {
                for (Fmp4RangeSeekSupport.SidxEntry entry : sidx.entries()) {
                    if (entry.timeSeconds() > targetSeconds + FMP4_TARGET_EPSILON_SECONDS) {
                        break;
                    }
                    selected = entry;
                }
            }
            if (selected == null) {
                selected = sidx.entries().get(0);
            }
            ChunkPrefetchInputStream range = openPrefetchWithCdnFallback(url, selected.byteStart());
            try {
                int timescale = init.timescale() > 0 ? init.timescale()
                        : (int) Math.min(Integer.MAX_VALUE, sidx.timescale());
                Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(range, targetSeconds,
                        timescale > 0 ? timescale : 48000, FMP4_MOOF_SCAN_BYTES, FMP4_TARGET_EPSILON_SECONDS,
                        FMP4_CLOSE_FRAGMENT_SECONDS);
                if (probe == null) {
                    closeQuietly(range);
                    return null;
                }
                byte[] probeBytes = probe.bytes();
                Fmp4RangeSeekSupport.MoofCandidate candidate = probe.candidate();
                if (Fmp4RangeSeekSupport.isAfterTargetCandidate(candidate, targetSeconds,
                        FMP4_TARGET_EPSILON_SECONDS)) {
                    LOGGER.debug("音频fMP4 SidxSeek 命中目标之后 fragment，回退 Moof RangeSeek: target={}s fragment={}s byte={}",
                            targetSeconds, candidate.fragmentSeconds(), selected.byteStart());
                    closeQuietly(range);
                    return null;
                }
                double fragmentSeconds = !Double.isNaN(candidate.fragmentSeconds())
                        ? candidate.fragmentSeconds()
                        : selected.timeSeconds();
                float residualSeconds = (float) Math.max(0.0D,
                        Math.min(targetSeconds, targetSeconds - fragmentSeconds));
                InputStream tail = new SequenceInputStream(
                        new ByteArrayInputStream(probeBytes, candidate.offset(),
                                probeBytes.length - candidate.offset()),
                        range);
                InputStream combined = new SequenceInputStream(new ByteArrayInputStream(init.bytes()), tail);
                LOGGER.debug(
                        "音频fMP4 SidxSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} host={}",
                        targetSeconds, fragmentSeconds, residualSeconds, playbackContext.startOffsetSeconds(),
                        selected.byteStart(), init.contentLength(), url.getHost());
                LOGGER.debug("音频fMP4 SidxSeek 选择: target={}s selectedFragment={}s startsWithSap={} byte={}",
                        targetSeconds, fragmentSeconds, selected.startsWithSap(), selected.byteStart());
                return new Fmp4StreamStart(combined, residualSeconds);
            } catch (IOException | RuntimeException e) {
                closeQuietly(range);
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("fMP4 audio sidx seek unavailable, falling back to range seek: {}", e.getMessage());
            return null;
        }
    }

    private static byte[] readRangeBytes(URL url, long start, long end) throws IOException {
        return readRange(url, start, end).bytes();
    }

    private static RangeBytes readRange(URL url, long start, long end) throws IOException {
        List<URL> candidates = CdnUrlFallbacks.candidates(url);
        if (!BiliCdnSelector.hasPreferredHost(candidates) && candidates.size() > 1 && RANGE_RACE_MAX_CANDIDATES > 1) {
            RangeBytes raced = readRangeRace(candidates, start, end);
            if (raced != null) {
                return raced;
            }
        }
        return readRangeSingle(url, start, end);
    }

    private static RangeBytes readRangeRace(List<URL> candidates, long start, long end) throws IOException {
        int count = Math.min(RANGE_RACE_MAX_CANDIDATES, candidates.size());
        CompletableFuture<RangeBytes> first = new CompletableFuture<>();
        List<CompletableFuture<?>> tasks = new java.util.ArrayList<>(count);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<IOException> lastError = new AtomicReference<>();
        for (int i = 0; i < count; i++) {
            URL candidate = candidates.get(i);
            tasks.add(CompletableFuture.runAsync(() -> {
                if (first.isDone()) {
                    return;
                }
                try {
                    RangeBytes bytes = readRangeSingle(candidate, start, end, false);
                    first.complete(bytes);
                } catch (IOException e) {
                    lastError.set(e);
                    if (failures.incrementAndGet() >= count) {
                        first.completeExceptionally(lastError.get());
                    }
                }
            }, RANGE_RACE_EXECUTOR));
        }
        try {
            RangeBytes winner = first.get(RANGE_RACE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            BiliCdnSelector.recordSuccess(winner.url());
            LOGGER.debug("音频小范围 CDN 赛马完成: range={}-{} bytes={} total={} host={}",
                    start, end, winner.bytes().length, winner.totalLength(), winner.host());
            return winner;
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.debug("音频小范围 CDN 赛马超时，回退串行读取: range={}-{} timeout={}ms candidates={}",
                    start, end, RANGE_RACE_TIMEOUT_MILLIS, count);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while racing audio range", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("audio range race failed", cause);
        } finally {
            tasks.forEach(task -> task.cancel(true));
        }
    }

    private static RangeBytes readRangeSingle(URL url, long start, long end) throws IOException {
        return readRangeSingle(url, start, end, true);
    }

    private static RangeBytes readRangeSingle(URL url, long start, long end, boolean persistCdnSuccess)
            throws IOException {
        HttpRangeClient client = new HttpRangeClient();
        try (HttpRangeClient.CdnResponse response = client.getRangeDirect(url, start, end, persistCdnSuccess)) {
            int status = response.statusCode();
            if (status != 206 && status != 200) {
                throw new IOException("HTTP " + status + " while reading fMP4 range");
            }
            long maxBytes = Math.max(1L, end - start + 1L);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                    (int) Math.min(maxBytes, 1024 * 1024L));
            byte[] buffer = new byte[64 * 1024];
            long remaining = maxBytes;
            while (remaining > 0L) {
                int n = response.body().read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
            long totalLength = response.totalLength() > 0L ? response.totalLength() : response.contentLength();
            return new RangeBytes(out.toByteArray(), totalLength, url.getHost(), url.toString());
        }
    }

    private record RangeBytes(byte[] bytes, long totalLength, String host, String url) {
    }

    private static Fmp4RangeSeekSupport.InitSegment readFmp4InitSegment(URL url) throws IOException {
        SegmentBaseInfo info = segmentBaseInfo(url.toString());
        if (info != null) {
            RangeBytes initRange = readRange(url, info.initStart(), info.initEnd());
            Fmp4RangeSeekSupport.InitSegment init = Fmp4RangeSeekSupport.extractInitSegment(initRange.bytes(),
                    initRange.totalLength(), (moovPayload, moov) -> moov.timescale);
            if (init != null && init.contentLength() > 0L) {
                return init;
            }
            LOGGER.debug("fMP4 audio segment_base init unusable, falling back to probe: initRange={}-{} host={}",
                    info.initStart(), info.initEnd(), url.getHost());
        }
        HttpRangeClient client = new HttpRangeClient();
        try (HttpRangeClient.CdnResponse response = client.getRange(url, 0L, FMP4_INIT_PROBE_BYTES - 1L)) {
            int status = response.statusCode();
            if (status != 206 && status != 200) {
                throw new IOException("HTTP " + status + " while probing fMP4 init segment");
            }
            long contentLength = response.totalLength() > 0L ? response.totalLength() : response.contentLength();
            java.io.ByteArrayOutputStream prefix = new java.io.ByteArrayOutputStream();
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
                        (moovPayload, moov) -> moov.timescale);
                if (init != null) {
                    break;
                }
            }
            if (init == null) {
                throw new IOException("unable to read complete fMP4 init segment");
            }
            return init;
        }
    }

    private static void closeQuietly(InputStream stream) {
        LifecycleClose.closeQuietly(stream);
    }

    private static void streamDecode(
            URL url,
            BlockingAudioPipe fallbackPipe,
            AtomicReference<AudioDecodePipeline> pipelineRef,
            AtomicReference<Exception> errorRef,
            AtomicReference<InputStream> bodyRef,
            AtomicBoolean closed,
            CountDownLatch formatReady,
            PlaybackContext playbackContext,
            float startOffsetSeconds) {
        long[] decoded = { 0L };
        long[] mdatBytes = { 0L };

        try {
            Fmp4StreamStart streamStart = openFmp4StreamStart(url, playbackContext, startOffsetSeconds);
            final float effectiveStartOffsetSeconds = streamStart.startOffsetSeconds();
            try (InputStream body = streamStart.stream()) {
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
                                playbackContext, effectiveStartOffsetSeconds);
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
                        DolbyEc3Pipeline pipeline = createRawDolbyPipeline(closed, playbackContext,
                                effectiveStartOffsetSeconds);
                        activatePipeline(url, pipelineRef, pipeline, formatReady);
                        decoded[0] += pipeline.onRawStream(payload);
                    }
                });

                AudioDecodePipeline pipeline = pipelineRef.get();
                LOGGER.debug("Audio stream finished: decoded={} mdatBytes={} {}",
                        decoded[0], mdatBytes[0], pipeline != null ? pipeline.statsSummary() : "");
            }
        } catch (EOFException e) {
            LOGGER.debug("Audio stream EOF: decoded={} mdatBytes={}", decoded[0], mdatBytes[0]);
            if (!closed.get() && decoded[0] == 0L && mdatBytes[0] == 0L) {
                BiliPlaybackDiagnostics.markFailed(url, e);
                errorRef.set(new IOException("audio stream ended before any media bytes", e));
            } else if (!closed.get()) {
                reportRecoverableStreamFailure(url, playbackContext, e, decoded[0], mdatBytes[0]);
            }
        } catch (IOException e) {
            if (closed.get() || isStreamEndException(e)) {
                LOGGER.debug("Audio stream stopped: closed={} msg={} decoded={} mdatBytes={}",
                        closed.get(), e.getMessage(), decoded[0], mdatBytes[0]);
                LOGGER.trace("Audio stream stop stack", e);
                if (!closed.get() && (decoded[0] > 0L || mdatBytes[0] > 0L)) {
                    reportRecoverableStreamFailure(url, playbackContext, e, decoded[0], mdatBytes[0]);
                }
            } else {
                LOGGER.error("Audio stream IO failed", e);
                BiliPlaybackDiagnostics.markFailed(url, e);
                if (decoded[0] > 0L || mdatBytes[0] > 0L) {
                    reportRecoverableStreamFailure(url, playbackContext, e, decoded[0], mdatBytes[0]);
                }
                errorRef.set(e);
            }
        } catch (UnsupportedAudioFileException e) {
            if (!closed.get()) {
                LOGGER.warn("Audio stream unsupported: {}", e.getMessage());
                BiliPlaybackDiagnostics.markFailed(url, e);
                errorRef.set(e);
            }
        } catch (Exception e) {
            if (!closed.get()) {
                LOGGER.error("Audio stream decode failed", e);
                BiliPlaybackDiagnostics.markFailed(url, e);
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

    private static boolean reportRecoverableStreamFailure(URL url, PlaybackContext playbackContext, Throwable error,
            long decoded, long mdatBytes) {
        if (playbackContext == null || playbackContext.sessionId() == null || playbackContext.sessionId().isBlank()) {
            return false;
        }
        boolean scheduled = SyncedStreamRecoveryRegistry.reportFailure(playbackContext.sessionId(), url, error);
        if (scheduled) {
            LOGGER.warn("音频流播放中断，已安排自动续播: session={} decoded={} mdatBytes={} host={} reason={}",
                    playbackContext.sessionId(), decoded, mdatBytes, url.getHost(),
                    error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : "unknown");
        }
        return scheduled;
    }

    private static AudioDecodePipeline createPipeline(
            URL url,
            Fmp4ToMp4Converter.ParseResult parseResult,
            byte[] moovData,
            BlockingAudioPipe fallbackPipe,
            AtomicBoolean closed,
            PlaybackContext playbackContext,
            float startOffsetSeconds) throws IOException, UnsupportedAudioFileException {
        boolean modernTurntable = playbackContext != null;
        BlockPos sourcePos = playbackContext != null ? playbackContext.pos() : null;
        String sessionId = playbackContext != null ? playbackContext.sessionId() : "";
        UUID ownerId = playbackContext != null ? playbackContext.ownerId() : null;
        float timelineStartOffsetSeconds = playbackContext != null
                ? playbackContext.startOffsetSeconds()
                : startOffsetSeconds;
        if ("ec-3".equals(parseResult.audioCodec)) {
            if (modernTurntable && Eac3NativeDecoder.isNativeAvailable()) {
                return new DolbyEc3Pipeline("fMP4", closed, sourcePos, startOffsetSeconds,
                        timelineStartOffsetSeconds, sessionId, ownerId);
            }
            throw new UnsupportedAudioFileException(
                    "EC-3 requires modern turntable Dolby playback and native decoder support");
        }
        if (parseResult.flacDfLa != null) {
            return modernTurntable
                    ? new FlacOpenALPipeline(parseResult.flacDfLa.clone(), closed, sourcePos, startOffsetSeconds,
                            timelineStartOffsetSeconds, sessionId, ownerId)
                    : new FlacPcmPipeline(parseResult.flacDfLa.clone(), fallbackPipe);
        }
        if (parseResult.asc != null) {
            return modernTurntable
                    ? new AacOpenALPipeline(parseResult.asc.clone(), closed, sourcePos, startOffsetSeconds,
                            timelineStartOffsetSeconds, sessionId, ownerId)
                    : new AacPcmPipeline(parseResult.asc.clone(), fallbackPipe);
        }
        String codecs = Fmp4ToMp4Converter.listAudioCodecs(moovData);
        LOGGER.warn("fMP4 moov did not contain a supported audio track. found={}", codecs);
        throw new UnsupportedAudioFileException("unsupported fMP4 audio codec: " + codecs);
    }

    private static DolbyEc3Pipeline createRawDolbyPipeline(AtomicBoolean closed, PlaybackContext playbackContext,
            float startOffsetSeconds)
            throws UnsupportedAudioFileException {
        if (playbackContext == null || !Eac3NativeDecoder.isNativeAvailable()) {
            throw new UnsupportedAudioFileException("raw E-AC-3 requires Dolby playback and native decoder support");
        }
        BlockPos sourcePos = playbackContext.pos();
        return new DolbyEc3Pipeline("raw", closed, sourcePos, startOffsetSeconds,
                playbackContext.startOffsetSeconds(), playbackContext.sessionId(), playbackContext.ownerId());
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
    }

    private static AudioInputStream silentStream(
            AudioFormat format,
            AtomicBoolean closed,
            Thread worker,
            URL url,
            AtomicReference<InputStream> bodyRef,
            BlockingAudioPipe fallbackPipe,
            AudioDecodePipeline pipeline,
            ActiveStreamControl streamControl) {
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
                closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipeline, streamControl);
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
            AudioDecodePipeline pipeline,
            ActiveStreamControl streamControl) {
        return new AudioInputStream(delegate, delegate.getFormat(), AudioSystem.NOT_SPECIFIED) {
            @Override
            public void close() throws IOException {
                IOException error = null;
                try {
                    closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipeline, streamControl);
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
            AudioDecodePipeline pipeline,
            ActiveStreamControl streamControl) throws IOException {
        if (closed.compareAndSet(false, true)) {
            BiliPlaybackDiagnostics.markClosed(url);
        }
        closeBody(bodyRef);
        fallbackPipe.closeWriter();
        fallbackPipe.close();
        worker.interrupt();
        if (pipeline != null) {
            pipeline.close();
        }
        LifecycleClose.join(worker, WORKER_JOIN_TIMEOUT_MILLIS);
        if (streamControl != null) {
            streamControl.unregister();
        }
    }

    private static void closeBody(AtomicReference<InputStream> bodyRef) {
        InputStream body = bodyRef.getAndSet(null);
        LifecycleClose.closeQuietly(body);
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

    private static boolean hasAllowedContext(URL url) {
        long now = System.currentTimeMillis();
        cleanupAllowedUrls(now);
        AtomicBoolean result = new AtomicBoolean(false);
        ALLOWED_URLS.computeIfPresent(contextKey(url), (ignored, queue) -> {
            queue.removeExpired(now);
            result.set(!queue.isEmpty());
            return queue.isEmpty() ? null : queue;
        });
        return result.get();
    }

    private static boolean isBiliCdnHost(URL url) {
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("bilibili") || lower.contains("bilivideo")
                || lower.contains("hdslb") || lower.contains("mcdn");
    }

    private static final class ActiveStreamControl {
        private final URL url;
        private final BlockPos pos;
        private final String sessionId;
        private final UUID minecartUuid;
        private final AtomicBoolean closed;
        private final AtomicReference<InputStream> bodyRef;
        private final Thread worker;
        private final BlockingAudioPipe fallbackPipe;
        private final AtomicReference<AudioDecodePipeline> pipelineRef;
        private final CountDownLatch formatReady;

        private ActiveStreamControl(URL url, BlockPos pos, String sessionId, UUID minecartUuid, AtomicBoolean closed,
                AtomicReference<InputStream> bodyRef, Thread worker, BlockingAudioPipe fallbackPipe,
                AtomicReference<AudioDecodePipeline> pipelineRef, CountDownLatch formatReady) {
            this.url = url;
            this.pos = AudioUtils.copyPos(pos);
            this.sessionId = sessionId != null ? sessionId : "";
            this.minecartUuid = minecartUuid;
            this.closed = closed;
            this.bodyRef = bodyRef;
            this.worker = worker;
            this.fallbackPipe = fallbackPipe;
            this.pipelineRef = pipelineRef;
            this.formatReady = formatReady;
        }

        private void close() {
            try {
                formatReady.countDown();
                closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipelineRef.get(), this);
            } catch (IOException e) {
                LOGGER.debug("Failed to close modern audio stream during client cleanup: {}", e.getMessage());
            }
        }

        private void unregister() {
            ACTIVE_MODERN_STREAMS.remove(this);
        }
    }

    private static void closeStaleModernStreams(BlockPos pos, String sessionId, UUID minecartUuid) {
        if ((pos == null && minecartUuid == null) || sessionId == null || sessionId.isBlank()) {
            return;
        }
        for (ActiveStreamControl control : ACTIVE_MODERN_STREAMS) {
            boolean sameSource = minecartUuid != null
                    ? minecartUuid.equals(control.minecartUuid)
                    : control.minecartUuid == null && control.pos != null && control.pos.equals(pos);
            if (sameSource && !sessionId.equals(control.sessionId)) {
                LOGGER.debug("关闭旧现代音频流: pos={} oldSession={} newSession={}", pos, control.sessionId, sessionId);
                control.close();
            }
        }
    }

    private record Fmp4StreamStart(InputStream stream, float startOffsetSeconds) {
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

    private record StartupSeekResult(long timelineOffsetMillis, int passes) {
        float timelineOffsetSeconds() {
            return timelineOffsetMillis / 1000.0f;
        }
    }

    private record PlaybackContext(BlockPos pos, long expiresAtMillis, String sessionId, long elapsedMillis,
            long totalMillis, UUID ownerId, UUID minecartUuid, long capturedNanos) {
        float startOffsetSeconds() {
            return Math.max(0L, elapsedMillis) / 1000.0f;
        }
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
