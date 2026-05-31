package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.client.api.implement.DirectHttpHandler;
import com.github.tartaricacid.netmusic.client.api.implement.NetEaseHttpHandler;
import com.github.tartaricacid.netmusic.NetMusic;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.codec.Eac3NativeDecoder;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.AacOpenALPipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.AacPcmPipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.AudioDecodePipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.DolbyEc3Pipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.FlacOpenALPipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.FlacPcmPipeline;
import com.zhongbai233.net_music_can_play_bili.bili.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.bili.stream.ChunkPrefetchInputStream;
import com.zhongbai233.net_music_can_play_bili.bili.stream.Fmp4StreamParser;
import com.zhongbai233.net_music_can_play_bili.bili.stream.BlockingAudioPipe;
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
    private static final int MP3_SYNC_SCAN_BYTES = 512 * 1024;
    private static final int MAX_HTTP_REDIRECTS = 5;
    private static final long RANGE_SEEK_PREROLL_BYTES = 256 * 1024L;
    private static final long ALLOWED_URL_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
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

    public static void allowUrl(String url) {
        allowUrl(url, null);
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
        if (!key.equals(url)) {
            LOGGER.debug("Registered modern audio context: strippedSync=true offset={}s", context.startOffsetSeconds());
        }
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

        if (playbackContext != null && isNativeNetMusicHost(requestUrl)) {
            LOGGER.debug("HTTP audio handler using NetMusic fallback host={} startOffset={}s",
                    requestUrl.getHost(), playbackContext.startOffsetSeconds());
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
        LOGGER.debug("HTTP audio handler probing {}://{}/... mode={} startOffset={}s",
                url.getProtocol(), url.getHost(), modernTurntable ? "modern-turntable" : "netmusic-compatible",
                startOffsetSeconds);

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
                LOGGER.debug("FLAC Hi-Res enabled TPDF dither {}bit -> 16bit: {}Hz/{}ch",
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
        DolbyAudioRegistry.registerStereo(stereo, playbackContext.pos());
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
                            playbackContext, startOffsetSeconds);
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
                    DolbyEc3Pipeline pipeline = createRawDolbyPipeline(closed, playbackContext, startOffsetSeconds);
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
        if ("ec-3".equals(parseResult.audioCodec)) {
            if (modernTurntable && Eac3NativeDecoder.isNativeAvailable()) {
                return new DolbyEc3Pipeline("fMP4", closed, sourcePos, startOffsetSeconds);
            }
            throw new UnsupportedAudioFileException(
                    "EC-3 requires modern turntable Dolby playback and native decoder support");
        }
        if (parseResult.flacDfLa != null) {
            return modernTurntable
                    ? new FlacOpenALPipeline(parseResult.flacDfLa.clone(), closed, sourcePos, startOffsetSeconds)
                    : new FlacPcmPipeline(parseResult.flacDfLa.clone(), fallbackPipe);
        }
        if (parseResult.asc != null) {
            return modernTurntable
                    ? new AacOpenALPipeline(parseResult.asc.clone(), closed, sourcePos, startOffsetSeconds)
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
        return new DolbyEc3Pipeline("raw", closed, sourcePos, startOffsetSeconds);
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
