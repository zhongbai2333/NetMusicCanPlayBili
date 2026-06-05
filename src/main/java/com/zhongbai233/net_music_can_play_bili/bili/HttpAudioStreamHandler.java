package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.client.api.implement.DirectHttpHandler;
import com.github.tartaricacid.netmusic.client.api.implement.NetEaseHttpHandler;
import com.github.tartaricacid.netmusic.NetMusic;
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
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeClient;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpAudioStreamHandler implements IAudioStreamHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PIPE_BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int FORMAT_WAIT_SECONDS = 15;
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
    private static final int MAX_SEGMENT_BASE_ENTRIES = Integer.getInteger(
            "bili.audio.segment_base_cache.max_entries", 512);
    private static final int[] MP3_MPEG1_LAYER1_BITRATES = { 0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384,
            416, 448, 0 };
    private static final int[] MP3_MPEG1_LAYER2_BITRATES = { 0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256,
            320, 384, 0 };
    private static final int[] MP3_MPEG1_LAYER3_BITRATES = { 0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224,
            256, 320, 0 };
    private static final int[] MP3_MPEG2_LAYER1_BITRATES = { 0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192,
            224, 256, 0 };
    private static final int[] MP3_MPEG2_LAYER23_BITRATES = { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144,
            160, 0 };
    private static final int[] MP3_MPEG1_SAMPLE_RATES = { 44100, 48000, 32000, 0 };
    private static final int[] MP3_MPEG2_SAMPLE_RATES = { 22050, 24000, 16000, 0 };
    private static final int[] MP3_MPEG25_SAMPLE_RATES = { 11025, 12000, 8000, 0 };
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("bytes\\s+\\d+-\\d+/(\\d+|\\*)",
            Pattern.CASE_INSENSITIVE);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
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
        if (url == null || url.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanupAllowedUrls(now);
        PlaybackSync.Metadata sync = PlaybackSync.parse(url);
        PlaybackContext context = new PlaybackContext(
                AudioUtils.copyPos(pos),
                now + ALLOWED_URL_TTL_MILLIS,
                sync.sessionId(),
                sync.elapsedMillis(),
                sync.totalMillis());
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
                        fallbackSync.elapsedMillis(), fallbackSync.totalMillis());
            }
        }

        if (playbackContext != null && isNativeNetMusicHost(requestUrl)) {
            return fallbackHttpStream(requestUrl, playbackContext, null);
        }

        try {
            return handleWithPipeline(requestUrl, playbackContext);
        } catch (UnsupportedAudioFileException e) {
            // Non fMP4/raw EC-3 content should go back through NetMusic's normal HTTP path.
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
        boolean modernTurntable = playbackContext != null;
        final float startOffsetSeconds = startOffsetSeconds(playbackContext);

        BlockingAudioPipe fallbackPipe = new BlockingAudioPipe(PIPE_BUFFER_SIZE);
        AtomicReference<AudioDecodePipeline> pipelineRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        AtomicReference<InputStream> bodyRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        CountDownLatch formatReady = new CountDownLatch(1);

        Thread worker = new Thread(
                () -> streamDecode(url, fallbackPipe, pipelineRef, errorRef, bodyRef, closed, formatReady,
                        playbackContext, startOffsetSeconds),
                modernTurntable ? "AudioStreamWorker" : "BiliCompatAudioStreamWorker");
        worker.setDaemon(true);
        worker.start();
        ActiveStreamControl streamControl = modernTurntable
                ? new ActiveStreamControl(url, closed, bodyRef, worker, fallbackPipe, pipelineRef, formatReady)
                : null;
        if (streamControl != null) {
            ACTIVE_MODERN_STREAMS.add(streamControl);
        }

        try {
            awaitFormat(url, closed, bodyRef, worker, formatReady);
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
                        .flatMap(HttpAudioStreamHandler::parseContentRangeTotal);
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

    private static java.util.Optional<Long> parseContentRangeTotal(String value) {
        Matcher matcher = CONTENT_RANGE_TOTAL.matcher(value);
        if (!matcher.find() || "*".equals(matcher.group(1))) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
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
        if (!isHttpRedirect(status)) {
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

    private static boolean isHttpRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    private static HttpRequest.Builder requestBuilder(URL url, long rangeOffset, boolean probe) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(java.time.Duration.ofSeconds(20))
                .GET()
                .header("Range", probe ? "bytes=0-0" : "bytes=%d-".formatted(Math.max(0L, rangeOffset)));
        NetMusic.NET_EASE_WEB_API.getRequestPropertyData()
                .forEach((key, value) -> {
                    String header = String.valueOf(key);
                    if (!isRestrictedHttpClientHeader(header)) {
                        builder.header(header, String.valueOf(value));
                    }
                });
        return builder;
    }

    private static boolean isRestrictedHttpClientHeader(String header) {
        String lower = header.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("connection") || lower.equals("content-length") || lower.equals("expect")
                || lower.equals("host") || lower.equals("upgrade");
    }

    private static InputStream alignMp3Frame(InputStream stream, long rangeOffset)
            throws IOException, UnsupportedAudioFileException {
        if (rangeOffset <= 0L) {
            return stream;
        }
        byte[] probe = stream.readNBytes(MP3_SYNC_SCAN_BYTES);
        int sync = findMp3FrameSync(probe, probe.length);
        if (sync < 0) {
            stream.close();
            throw new UnsupportedAudioFileException("unable to find MP3 frame after range seek");
        }
        LOGGER.debug("MP3 range aligned: byte={} frameOffset={}", rangeOffset, sync);
        return new SequenceInputStream(new ByteArrayInputStream(probe, sync, probe.length - sync), stream);
    }

    private static int findMp3FrameSync(byte[] bytes, int length) {
        for (int i = 0; i + 3 < length; i++) {
            Mp3Frame first = parseMp3Frame(bytes, i, length);
            if (first == null) {
                continue;
            }

            int pos = i;
            int validFrames = 0;
            while (validFrames < 4) {
                Mp3Frame frame = parseMp3Frame(bytes, pos, length);
                if (frame == null || !frame.isCompatibleWith(first)) {
                    break;
                }
                validFrames++;
                pos += frame.frameLength();
            }
            if (validFrames >= 3) {
                return i;
            }
        }
        return -1;
    }

    private static Mp3Frame parseMp3Frame(byte[] bytes, int offset, int length) {
        if (offset < 0 || offset + 4 > length) {
            return null;
        }
        int b0 = bytes[offset] & 0xFF;
        int b1 = bytes[offset + 1] & 0xFF;
        int b2 = bytes[offset + 2] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
            return null;
        }

        int version = (b1 >> 3) & 0x03;
        int layer = (b1 >> 1) & 0x03;
        int bitrateIndex = (b2 >> 4) & 0x0F;
        int sampleRateIndex = (b2 >> 2) & 0x03;
        int padding = (b2 >> 1) & 0x01;
        if (version == 0x01 || layer == 0x00 || bitrateIndex == 0x00 || bitrateIndex == 0x0F
                || sampleRateIndex == 0x03) {
            return null;
        }

        int bitrateKbps = mp3BitrateKbps(version, layer, bitrateIndex);
        int sampleRate = mp3SampleRate(version, sampleRateIndex);
        if (bitrateKbps <= 0 || sampleRate <= 0) {
            return null;
        }

        int frameLength;
        if (layer == 0x03) {
            frameLength = ((12 * bitrateKbps * 1000) / sampleRate + padding) * 4;
        } else if (layer == 0x02) {
            frameLength = (144 * bitrateKbps * 1000) / sampleRate + padding;
        } else {
            int coefficient = version == 0x03 ? 144 : 72;
            frameLength = (coefficient * bitrateKbps * 1000) / sampleRate + padding;
        }
        if (frameLength < 4 || offset + frameLength > length) {
            return null;
        }
        return new Mp3Frame(version, layer, sampleRate, frameLength);
    }

    private static int mp3BitrateKbps(int version, int layer, int index) {
        if (version == 0x03) {
            if (layer == 0x03) {
                return MP3_MPEG1_LAYER1_BITRATES[index];
            }
            if (layer == 0x02) {
                return MP3_MPEG1_LAYER2_BITRATES[index];
            }
            return MP3_MPEG1_LAYER3_BITRATES[index];
        }
        if (layer == 0x03) {
            return MP3_MPEG2_LAYER1_BITRATES[index];
        }
        return MP3_MPEG2_LAYER23_BITRATES[index];
    }

    private static int mp3SampleRate(int version, int index) {
        if (version == 0x03) {
            return MP3_MPEG1_SAMPLE_RATES[index];
        }
        if (version == 0x02) {
            return MP3_MPEG2_SAMPLE_RATES[index];
        }
        return MP3_MPEG25_SAMPLE_RATES[index];
    }

    private record Mp3Frame(int version, int layer, int sampleRate, int frameLength) {
        boolean isCompatibleWith(Mp3Frame other) {
            return version == other.version && layer == other.layer && sampleRate == other.sampleRate;
        }
    }

    private static AudioInputStream openModernFallbackStream(AudioInputStream stream, PlaybackContext playbackContext,
            float startOffsetSeconds)
            throws UnsupportedAudioFileException, IOException {
        AudioInputStream pcm = toPcmStream(stream);
        skipBestEffort(pcm, skipBytes(pcm.getFormat(), startOffsetSeconds));
        pcm = requireReadablePcm(pcm, "no decoded PCM after HTTP seek");
        StereoOpenALHandler stereo = new StereoOpenALHandler();
        stereo.setSampleRate((int) pcm.getFormat().getSampleRate());
        DolbyAudioRegistry.registerStereo(stereo, playbackContext.pos(), playbackContext.startOffsetSeconds());
        return new OpenALTappedAudioInputStream(pcm, stereo, () -> {
            DolbyAudioRegistry.unregisterStereo(stereo);
            stereo.cleanup();
        });
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
        long remaining = Math.max(0L, bytesToSkip);
        while (remaining > 0L) {
            int n = stream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                return;
            }
            remaining -= n;
        }
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
        return new Fmp4StreamStart(new ChunkPrefetchInputStream(url), startOffsetSeconds);
    }

    private static Fmp4StreamStart tryOpenFmp4RangeSeek(URL url, PlaybackContext playbackContext,
            float targetSeconds) {
        ChunkPrefetchInputStream lastRange = null;
        try {
            Fmp4InitSegment init = readFmp4InitSegment(url);
            long contentLength = init.contentLength();
            if (contentLength <= 0L) {
                return null;
            }

            Fmp4StreamStart sidxStart = tryOpenFmp4SidxSeek(url, playbackContext, init, targetSeconds);
            if (sidxStart != null) {
                return sidxStart;
            }

            long elapsedMillis = Math.max(0L, playbackContext.elapsedMillis());
            long totalMillis = Math.max(1L, playbackContext.totalMillis());
            double ratio = Math.max(0.0D, Math.min(0.98D, elapsedMillis / (double) totalMillis));
            long estimatedOffset = Math.min(contentLength - 1L, Math.max(0L, Math.round(contentLength * ratio)));
            long rangeStart = Math.max(init.bytes().length, estimatedOffset - FMP4_SEEK_PREROLL_BYTES);

            int timescale = init.timescale() > 0 ? init.timescale() : 48000;
            for (int attempt = 0; attempt < FMP4_SEEK_MAX_ATTEMPTS; attempt++) {
                ChunkPrefetchInputStream range = new ChunkPrefetchInputStream(url, rangeStart);
                lastRange = range;
                try {
                    MoofProbe probe = readMoofProbe(range, targetSeconds, timescale);
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
                    MoofCandidate candidate = probe.candidate();
                    long absoluteMoofOffset = rangeStart + candidate.offset();
                    if (attempt + 1 < FMP4_SEEK_MAX_ATTEMPTS
                            && shouldRetryFmp4RangeSeek(candidate, targetSeconds)) {
                        long nextStart = nextFmp4RangeStart(candidate, targetSeconds, totalMillis,
                                contentLength, absoluteMoofOffset, init.bytes().length);
                        if (Math.abs(nextStart - rangeStart) > FMP4_SEEK_PREROLL_BYTES) {
                            closeQuietly(range);
                            lastRange = null;
                            rangeStart = nextStart;
                            continue;
                        }
                    }

                    if (isAfterTargetMoofCandidate(candidate, targetSeconds)) {
                        LOGGER.debug(
                                "fMP4 range seek candidate is after target: target={}s fragment={}s byte={} attemptsExhausted=true; falling back to decoded skip",
                                targetSeconds, candidate.fragmentSeconds(), absoluteMoofOffset);
                        return null;
                    }

                    float residualSeconds = residualStartOffsetSeconds(targetSeconds, candidate, totalMillis,
                            contentLength, absoluteMoofOffset);
                    InputStream tail = new SequenceInputStream(
                            new ByteArrayInputStream(probeBytes, candidate.offset(),
                                    probeBytes.length - candidate.offset()),
                            range);
                    lastRange = null;
                    InputStream combined = new SequenceInputStream(new ByteArrayInputStream(init.bytes()), tail);
                    LOGGER.info(
                            "音频fMP4 RangeSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} host={}",
                            targetSeconds, candidate.fragmentSeconds(), residualSeconds,
                            playbackContext.startOffsetSeconds(), absoluteMoofOffset, contentLength, url.getHost());
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

    private static Fmp4StreamStart tryOpenFmp4SidxSeek(URL url, PlaybackContext playbackContext, Fmp4InitSegment init,
            float targetSeconds) {
        SegmentBaseInfo info = segmentBaseInfo(url.toString());
        if (info == null) {
            return null;
        }
        try {
            byte[] sidxBytes = readRangeBytes(url, info.indexStart(), info.indexEnd());
            SidxIndex sidx = parseSidx(sidxBytes, info.indexStart());
            if (sidx == null || sidx.entries().isEmpty()) {
                return null;
            }
            SidxEntry selected = null;
            for (SidxEntry entry : sidx.entries()) {
                if (entry.timeSeconds() <= targetSeconds + FMP4_TARGET_EPSILON_SECONDS) {
                    selected = entry;
                } else {
                    break;
                }
            }
            if (selected == null) {
                selected = sidx.entries().get(0);
            }
            ChunkPrefetchInputStream range = new ChunkPrefetchInputStream(url, selected.byteStart());
            try {
                int timescale = init.timescale() > 0 ? init.timescale()
                        : (int) Math.min(Integer.MAX_VALUE, sidx.timescale());
                MoofProbe probe = readMoofProbe(range, targetSeconds, timescale > 0 ? timescale : 48000);
                if (probe == null) {
                    closeQuietly(range);
                    return null;
                }
                byte[] probeBytes = probe.bytes();
                MoofCandidate candidate = probe.candidate();
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
                LOGGER.info(
                        "音频fMP4 SidxSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} host={}",
                        targetSeconds, fragmentSeconds, residualSeconds, playbackContext.startOffsetSeconds(),
                        selected.byteStart(), init.contentLength(), url.getHost());
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
        HttpRangeClient client = new HttpRangeClient();
        try (HttpRangeClient.CdnResponse response = client.getRange(url, start, end)) {
            int status = response.statusCode();
            if (status != 206 && status != 200) {
                throw new IOException("HTTP " + status + " while reading fMP4 sidx");
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
            return out.toByteArray();
        }
    }

    private static Fmp4InitSegment readFmp4InitSegment(URL url) throws IOException {
        HttpRangeClient client = new HttpRangeClient();
        try (HttpRangeClient.CdnResponse response = client.getRange(url, 0L, FMP4_INIT_PROBE_BYTES - 1L)) {
            int status = response.statusCode();
            if (status != 206 && status != 200) {
                throw new IOException("HTTP " + status + " while probing fMP4 init segment");
            }
            long contentLength = response.totalLength() > 0L ? response.totalLength() : response.contentLength();
            java.io.ByteArrayOutputStream prefix = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            Fmp4InitSegment init = null;
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
                init = extractFmp4InitSegment(prefix.toByteArray(), contentLength);
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

    private static Fmp4InitSegment extractFmp4InitSegment(byte[] prefix, long contentLength) {
        int pos = 0;
        while (pos + 8 <= prefix.length) {
            Mp4Box box = readCompleteMp4Box(prefix, pos, prefix.length);
            if (box == null) {
                return null;
            }
            if (isBoxType(prefix, pos + 4, 'm', 'o', 'o', 'v')) {
                byte[] initBytes = Arrays.copyOf(prefix, pos + (int) box.size());
                byte[] moovPayload = Arrays.copyOfRange(prefix, pos + box.headerSize(), pos + (int) box.size());
                Fmp4ToMp4Converter.ParseResult moov = Fmp4ToMp4Converter.parseMoov(moovPayload);
                return new Fmp4InitSegment(initBytes, contentLength, moov.timescale);
            }
            pos += (int) box.size();
        }
        return null;
    }

    private static MoofProbe readMoofProbe(InputStream range, float targetSeconds, int timescale) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[256 * 1024];
        MoofCandidate best = null;
        while (out.size() < FMP4_MOOF_SCAN_BYTES) {
            int request = Math.min(buffer.length, FMP4_MOOF_SCAN_BYTES - out.size());
            int n = range.read(buffer, 0, request);
            if (n < 0) {
                break;
            }
            if (n == 0) {
                continue;
            }
            out.write(buffer, 0, n);
            byte[] data = out.toByteArray();
            best = findBestMoofCandidate(data, data.length, targetSeconds, timescale);
            if (isCloseMoofCandidate(best, targetSeconds)) {
                return new MoofProbe(data, best);
            }
        }
        if (best == null) {
            byte[] data = out.toByteArray();
            best = findBestMoofCandidate(data, data.length, targetSeconds, timescale);
            return best != null ? new MoofProbe(data, best) : null;
        }
        return new MoofProbe(out.toByteArray(), best);
    }

    private static boolean isCloseMoofCandidate(MoofCandidate candidate, float targetSeconds) {
        if (candidate == null || Double.isNaN(candidate.fragmentSeconds())) {
            return false;
        }
        double delta = targetSeconds - candidate.fragmentSeconds();
        return delta >= -FMP4_TARGET_EPSILON_SECONDS && delta <= FMP4_CLOSE_FRAGMENT_SECONDS;
    }

    private static boolean shouldRetryFmp4RangeSeek(MoofCandidate candidate, float targetSeconds) {
        if (candidate == null || Double.isNaN(candidate.fragmentSeconds())) {
            return false;
        }
        double delta = targetSeconds - candidate.fragmentSeconds();
        return delta < -FMP4_TARGET_EPSILON_SECONDS || delta > FMP4_CLOSE_FRAGMENT_SECONDS;
    }

    private static boolean isAfterTargetMoofCandidate(MoofCandidate candidate, float targetSeconds) {
        return candidate != null
                && !Double.isNaN(candidate.fragmentSeconds())
                && candidate.fragmentSeconds() > targetSeconds + FMP4_TARGET_EPSILON_SECONDS;
    }

    private static long nextFmp4RangeStart(MoofCandidate candidate, float targetSeconds, long totalMillis,
            long contentLength, long absoluteMoofOffset, int initLength) {
        double bytesPerSecond = contentLength / Math.max(1.0D, totalMillis / 1000.0D);
        double deltaSeconds = targetSeconds - candidate.fragmentSeconds();
        long adjusted = absoluteMoofOffset + Math.round(deltaSeconds * bytesPerSecond) - FMP4_SEEK_PREROLL_BYTES;
        return Math.max(initLength, Math.min(contentLength - 1L, adjusted));
    }

    private static MoofCandidate findBestMoofCandidate(byte[] probe, int length, float targetSeconds, int timescale) {
        MoofCandidate first = null;
        MoofCandidate bestBeforeTarget = null;
        for (int i = 0; i + 8 <= length; i++) {
            if (!isBoxType(probe, i + 4, 'm', 'o', 'o', 'f')) {
                continue;
            }
            MoofCandidate candidate = readMoofCandidate(probe, i, length, timescale);
            if (candidate == null) {
                continue;
            }
            if (first == null) {
                first = candidate;
            }
            if (!Double.isNaN(candidate.fragmentSeconds())) {
                if (candidate.fragmentSeconds() <= targetSeconds + 0.05D) {
                    bestBeforeTarget = candidate;
                } else if (bestBeforeTarget != null) {
                    break;
                }
            }
            Mp4Box box = readCompleteMp4Box(probe, i, length);
            if (box != null && box.size() <= Integer.MAX_VALUE) {
                i += Math.max(0, (int) box.size() - 1);
            }
        }
        return bestBeforeTarget != null ? bestBeforeTarget : first;
    }

    private static MoofCandidate readMoofCandidate(byte[] probe, int offset, int length, int timescale) {
        Mp4Box box = readCompleteMp4Box(probe, offset, length);
        if (box == null || box.size() > Integer.MAX_VALUE || box.size() < box.headerSize()) {
            return null;
        }
        byte[] moofPayload = Arrays.copyOfRange(probe, offset + box.headerSize(), offset + (int) box.size());
        Fmp4ToMp4Converter.ParseResult moof = Fmp4ToMp4Converter.parseMoof(moofPayload);
        if (moof.sampleCount <= 0 && moof.baseMediaDecodeTime < 0L) {
            return null;
        }
        double fragmentSeconds = moof.baseMediaDecodeTime >= 0L && timescale > 0
                ? moof.baseMediaDecodeTime / (double) timescale
                : Double.NaN;
        return new MoofCandidate(offset, fragmentSeconds);
    }

    private static float residualStartOffsetSeconds(float targetSeconds, MoofCandidate candidate, long totalMillis,
            long contentLength, long absoluteMoofOffset) {
        double fragmentSeconds = candidate.fragmentSeconds();
        if (Double.isNaN(fragmentSeconds) && contentLength > 0L && totalMillis > 0L) {
            fragmentSeconds = (totalMillis / 1000.0D) * (absoluteMoofOffset / (double) contentLength);
        }
        if (Double.isNaN(fragmentSeconds)) {
            return targetSeconds;
        }
        return (float) Math.max(0.0D, Math.min(targetSeconds, targetSeconds - fragmentSeconds));
    }

    private static SidxIndex parseSidx(byte[] data, long absoluteStart) {
        int sidxOffset = -1;
        Mp4Box sidxBox = null;
        for (int pos = 0; pos + 8 <= data.length;) {
            Mp4Box box = readCompleteMp4Box(data, pos, data.length);
            if (box == null || box.size() <= 0L || box.size() > Integer.MAX_VALUE) {
                break;
            }
            if (isBoxType(data, pos + 4, 's', 'i', 'd', 'x')) {
                sidxOffset = pos;
                sidxBox = box;
                break;
            }
            pos += (int) box.size();
        }
        if (sidxOffset < 0 || sidxBox == null) {
            return null;
        }
        int p = sidxOffset + sidxBox.headerSize();
        int end = sidxOffset + (int) sidxBox.size();
        if (p + 12 > end) {
            return null;
        }
        int version = data[p] & 0xFF;
        p += 4; // version + flags
        p += 4; // reference_ID
        long timescale = readUInt32(data, p);
        p += 4;
        long earliestPresentationTime;
        long firstOffset;
        if (version == 0) {
            if (p + 8 > end) {
                return null;
            }
            earliestPresentationTime = readUInt32(data, p);
            p += 4;
            firstOffset = readUInt32(data, p);
            p += 4;
        } else {
            if (p + 16 > end) {
                return null;
            }
            earliestPresentationTime = readUInt64(data, p);
            p += 8;
            firstOffset = readUInt64(data, p);
            p += 8;
        }
        if (p + 4 > end || timescale <= 0L) {
            return null;
        }
        p += 2; // reserved
        int referenceCount = ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
        p += 2;
        long currentTime = earliestPresentationTime;
        long currentByte = absoluteStart + sidxOffset + sidxBox.size() + firstOffset;
        java.util.ArrayList<SidxEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < referenceCount && p + 12 <= end; i++) {
            long ref = readUInt32(data, p);
            p += 4;
            boolean referenceType = (ref & 0x8000_0000L) != 0L;
            long size = ref & 0x7FFF_FFFFL;
            long duration = readUInt32(data, p);
            p += 4;
            p += 4; // SAP
            if (!referenceType && size > 0L) {
                entries.add(new SidxEntry(currentTime / (double) timescale, currentByte, currentByte + size - 1L));
            }
            currentTime += duration;
            currentByte += size;
        }
        return entries.isEmpty() ? null : new SidxIndex(timescale, entries);
    }

    private static Mp4Box readCompleteMp4Box(byte[] data, int offset, int length) {
        if (offset < 0 || offset + 8 > length) {
            return null;
        }
        long size = readUInt32(data, offset);
        int headerSize = 8;
        if (size == 1L) {
            if (offset + 16 > length) {
                return null;
            }
            size = readUInt64(data, offset + 8);
            headerSize = 16;
        }
        if (size < headerSize || size > length - offset) {
            return null;
        }
        return new Mp4Box(size, headerSize);
    }

    private static long readUInt32(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF) << 24
                | ((long) data[offset + 1] & 0xFF) << 16
                | ((long) data[offset + 2] & 0xFF) << 8
                | ((long) data[offset + 3] & 0xFF);
    }

    private static long readUInt64(byte[] data, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    private static boolean isBoxType(byte[] data, int offset, char a, char b, char c, char d) {
        return offset >= 0 && offset + 4 <= data.length
                && data[offset] == (byte) a
                && data[offset + 1] == (byte) b
                && data[offset + 2] == (byte) c
                && data[offset + 3] == (byte) d;
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
        } catch (IOException e) {
            if (closed.get() || isStreamEndException(e)) {
                LOGGER.debug("Audio stream stopped: closed={} msg={} decoded={} mdatBytes={}",
                        closed.get(), e.getMessage(), decoded[0], mdatBytes[0]);
                LOGGER.trace("Audio stream stop stack", e);
            } else {
                LOGGER.error("Audio stream IO failed", e);
                BiliPlaybackDiagnostics.markFailed(url, e);
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
        float timelineStartOffsetSeconds = playbackContext != null
                ? playbackContext.startOffsetSeconds()
                : startOffsetSeconds;
        if ("ec-3".equals(parseResult.audioCodec)) {
            if (modernTurntable && Eac3NativeDecoder.isNativeAvailable()) {
                return new DolbyEc3Pipeline("fMP4", closed, sourcePos, startOffsetSeconds,
                        timelineStartOffsetSeconds);
            }
            throw new UnsupportedAudioFileException(
                    "EC-3 requires modern turntable Dolby playback and native decoder support");
        }
        if (parseResult.flacDfLa != null) {
            return modernTurntable
                    ? new FlacOpenALPipeline(parseResult.flacDfLa.clone(), closed, sourcePos, startOffsetSeconds,
                            timelineStartOffsetSeconds)
                    : new FlacPcmPipeline(parseResult.flacDfLa.clone(), fallbackPipe);
        }
        if (parseResult.asc != null) {
            return modernTurntable
                    ? new AacOpenALPipeline(parseResult.asc.clone(), closed, sourcePos, startOffsetSeconds,
                            timelineStartOffsetSeconds)
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
                playbackContext.startOffsetSeconds());
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
        joinWorker(worker);
        if (streamControl != null) {
            streamControl.unregister();
        }
    }

    private static void joinWorker(Thread worker) {
        if (worker == null || worker == Thread.currentThread() || !worker.isAlive()) {
            return;
        }
        try {
            worker.join(WORKER_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        private final AtomicBoolean closed;
        private final AtomicReference<InputStream> bodyRef;
        private final Thread worker;
        private final BlockingAudioPipe fallbackPipe;
        private final AtomicReference<AudioDecodePipeline> pipelineRef;
        private final CountDownLatch formatReady;

        private ActiveStreamControl(URL url, AtomicBoolean closed, AtomicReference<InputStream> bodyRef, Thread worker,
                BlockingAudioPipe fallbackPipe, AtomicReference<AudioDecodePipeline> pipelineRef,
                CountDownLatch formatReady) {
            this.url = url;
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

    private record Fmp4StreamStart(InputStream stream, float startOffsetSeconds) {
    }

    private record Fmp4InitSegment(byte[] bytes, long contentLength, int timescale) {
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

    private record SidxIndex(long timescale, java.util.List<SidxEntry> entries) {
    }

    private record SidxEntry(double timeSeconds, long byteStart, long byteEnd) {
    }

    private record MoofCandidate(int offset, double fragmentSeconds) {
    }

    private record MoofProbe(byte[] bytes, MoofCandidate candidate) {
    }

    private record Mp4Box(long size, int headerSize) {
    }

    private record PlaybackContext(BlockPos pos, long expiresAtMillis, String sessionId, long elapsedMillis,
            long totalMillis) {
        float startOffsetSeconds() {
            return Math.max(0L, elapsedMillis) / 1000.0f;
        }
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
