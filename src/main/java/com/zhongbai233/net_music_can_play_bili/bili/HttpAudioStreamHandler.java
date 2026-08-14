package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.audio.PcmStartupSeekPolicy;
import com.zhongbai233.net_music_can_play_bili.media.sync.AudioStartupSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken;
import com.zhongbai233.net_music_can_play_bili.media.sync.OneShotRequestRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.client.api.implement.DirectHttpHandler;
import com.github.tartaricacid.netmusic.client.api.implement.NetEaseHttpHandler;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.codec.Eac3NativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AudioDecodePipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AudioPipelineFactory;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.DolbyEc3Pipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.FlacOpenALPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.FlacPcmPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeHeaders;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
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
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

public class HttpAudioStreamHandler implements IAudioStreamHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AudioStreamProperties.Http PROPERTIES = AudioStreamProperties.http();
    private static final int PIPE_BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int FORMAT_WAIT_SECONDS = PROPERTIES.formatWaitSeconds();
    private static final long WORKER_JOIN_TIMEOUT_MILLIS = 2_000L;
    private static final int MP3_SYNC_SCAN_BYTES = 512 * 1024;
    private static final int MAX_HTTP_REDIRECTS = 5;
    private static final int MP3_SEEK_FADE_MILLIS = 80;
    private static final long REQUEST_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    private static final OneShotRequestRegistry<PlaybackRequest> REQUESTS = new OneShotRequestRegistry<>();
    private static final Set<ActiveStreamControl> ACTIVE_MODERN_STREAMS = ConcurrentHashMap.newKeySet();

    public static void registerSegmentBase(String audioUrl, long initStart, long initEnd, long indexStart,
            long indexEnd) {
        Fmp4AudioStreamSeeker.registerSegmentBase(audioUrl, initStart, initEnd, indexStart, indexEnd);
    }

    /** 注册一次性强类型播放请求，返回带 opaque token 的播放 URL。 */
    public static RegisteredRequest registerRequest(PlaybackRequest request) {
        if (request == null || request.mediaUrl().isBlank()) {
            return new RegisteredRequest("", Optional.empty());
        }
        closeStaleModernStreams(request.pos(), request.playbackSessionId(), request.minecartUuid());
        long expiresAt = System.currentTimeMillis() + REQUEST_TTL_MILLIS;
        MediaRequestToken token = REQUESTS.registerToken(request, expiresAt);
        return new RegisteredRequest(PlaybackSync.withRequestToken(request.mediaUrl(), token), Optional.of(token));
    }

    public static void cancelRequest(String requestToken) {
        MediaRequestToken.parse(requestToken).ifPresent(HttpAudioStreamHandler::cancelRequest);
    }

    public static void cancelRequest(MediaRequestToken requestToken) {
        REQUESTS.cancelToken(requestToken);
    }

    /** 供其他 handler（如直播）消费自己 URL 上携带的一次性播放请求。 */
    public static PlaybackRequest consumeRegisteredRequest(String url) {
        return PlaybackSync.parseMediaRequestToken(url).map(REQUESTS::consumeToken).orElse(null);
    }

    public static void closeModernStreams() {
        for (ActiveStreamControl control : ACTIVE_MODERN_STREAMS) {
            control.close();
        }
        ACTIVE_MODERN_STREAMS.clear();
        REQUESTS.clear();
        Fmp4AudioStreamSeeker.clearSegmentBases();
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
        if (hasRequestContext(url)) {
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
        PlaybackRequest request = consumeRequest(url);
        URL requestUrl = request != null ? URI.create(request.mediaUrl()).toURL() : PlaybackSync.strip(url);

        if (request != null && isNativeNetMusicHost(requestUrl)) {
            return fallbackHttpStream(requestUrl, request, null);
        }

        try {
            return handleWithPipeline(requestUrl, request);
        } catch (UnsupportedAudioFileException e) {
            // 非 fMP4/raw EC-3 内容应回到 NetMusic 的普通 HTTP 路径处理。
            if (e instanceof NonPipelineAudioException) {
                return fallbackHttpStream(requestUrl, request, e);
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
    private AudioInputStream handleWithPipeline(URL url, PlaybackRequest request)
            throws UnsupportedAudioFileException, IOException {
        long started = System.currentTimeMillis();
        boolean modernTurntable = request != null;
        final float startOffsetSeconds = startOffsetSeconds(request);

        BlockingAudioPipe fallbackPipe = new BlockingAudioPipe(PIPE_BUFFER_SIZE);
        AtomicReference<AudioDecodePipeline> pipelineRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        AtomicReference<InputStream> bodyRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        CountDownLatch formatReady = new CountDownLatch(1);

        Thread worker = NetMusicThreadFactory.daemonThread(
                modernTurntable ? "AudioStreamWorker" : "BiliCompatAudioStreamWorker",
                () -> streamDecode(url, fallbackPipe, pipelineRef, errorRef, bodyRef, closed, formatReady,
                        request, startOffsetSeconds));
        worker.start();
        ActiveStreamControl streamControl = null;
        if (modernTurntable && request != null) {
            streamControl = new ActiveStreamControl(url, request.pos(), request.playbackSessionId(),
                    request.minecartUuid(), closed,
                    bodyRef, worker, fallbackPipe, pipelineRef, formatReady);
        }
        if (streamControl != null) {
            ACTIVE_MODERN_STREAMS.add(streamControl);
        }

        try {
            awaitFormat(url, closed, bodyRef, worker, formatReady, errorRef);
            LOGGER.debug("HTTP 音频格式就绪: cost={}ms session={} offset={}s host={}",
                    System.currentTimeMillis() - started,
                    request != null ? request.sessionId() : "", startOffsetSeconds, url.getHost());
        } catch (IOException | UnsupportedAudioFileException e) {
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
                request != null ? request.sessionId() : "",
                request != null ? request.pos() : null, startOffsetSeconds, request != null
                        ? request.totalMillis()
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

    private static PlaybackRequest consumeRequest(URL url) {
        return PlaybackSync.parseMediaRequestToken(url.toString()).map(REQUESTS::consumeToken).orElse(null);
    }

    private static float startOffsetSeconds(PlaybackRequest request) {
        return request != null ? request.startOffsetSeconds() : 0f;
    }

    private static AudioInputStream fallbackHttpStream(URL url, PlaybackRequest request,
            UnsupportedAudioFileException probeError)
            throws UnsupportedAudioFileException, IOException {
        try {
            LOGGER.debug("Falling back to NetMusic direct HTTP handler for non-fMP4 URL: {}", url);
            float startOffsetSeconds = request != null ? request.startOffsetSeconds() : 0f;
            // MP3 Layer III 帧可能依赖之前的 bit reservoir。即使 HTTP Range 的起点
            // 对齐到合法帧，缺失的历史解码状态仍可能让 MP3SPI/JLayer 持续输出白噪音。
            // 自定义 MP3 显式从文件头建解码状态；网易源则在下方通过专用 handler
            // 从头打开，再统一丢弃已播放的 PCM，保留其鉴权和请求头行为。
            if (request != null && startOffsetSeconds > 1.0f && !isNativeNetMusicHost(url)) {
                AudioInputStream strict = tryOpenSafeCustomMp3Stream(url, request, startOffsetSeconds);
                if (strict != null) {
                    return strict;
                }
            }

            NetEaseHttpHandler netEase = new NetEaseHttpHandler();
            AudioInputStream stream;
            if (netEase.canHandle(url)) {
                stream = netEase.handle(url);
            } else {
                stream = new DirectHttpHandler().handle(url);
            }
            return request != null
                    ? openModernFallbackStream(stream, request, startOffsetSeconds)
                    : applyStartOffset(stream, startOffsetSeconds);
        } catch (UnsupportedAudioFileException | IOException fallbackError) {
            if (probeError != null) {
                fallbackError.addSuppressed(probeError);
            }
            BiliPlaybackDiagnostics.markFailed(url, fallbackError);
            throw fallbackError;
        }
    }

    private static AudioInputStream tryOpenSafeCustomMp3Stream(URL url, PlaybackRequest request,
            float startOffsetSeconds) throws IOException, UnsupportedAudioFileException {
        InputStream body = null;
        InputStream aligned = null;
        AudioInputStream encoded = null;
        try {
            body = openHttpRangeStream(url, 0L);
            byte[] probe = body.readNBytes(MP3_SYNC_SCAN_BYTES);
            int sync = Mp3FrameSync.findFrameSync(probe, probe.length);
            if (sync < 0) {
                closeQuietly(body);
                body = null;
                if (isLikelyMp3Url(url)) {
                    throw new UnsupportedAudioFileException(
                            "custom MP3 did not contain consecutive valid frames near the file head");
                }
                return null;
            }
            aligned = new SequenceInputStream(
                    new ByteArrayInputStream(probe, sync, probe.length - sync), body);
            body = null;
            encoded = AudioSystem.getAudioInputStream(
                    new BufferedInputStream(aligned, MP3_SYNC_SCAN_BYTES));
            aligned = null;
            LOGGER.debug("Custom MP3 safe seek: decodeFromHead=true target={}s frameOffset={} host={}",
                    startOffsetSeconds, sync, url.getHost());
            AudioInputStream result = openModernFallbackStream(encoded, request, startOffsetSeconds);
            encoded = null;
            return result;
        } catch (UnsupportedAudioFileException | IOException e) {
            closeQuietly(encoded);
            closeQuietly(aligned);
            closeQuietly(body);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(encoded);
            closeQuietly(aligned);
            closeQuietly(body);
            throw new IOException("custom MP3 safe seek failed", e);
        }
    }

    private static boolean isLikelyMp3Url(URL url) {
        String path = url != null ? url.getPath() : null;
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3");
    }

    private static InputStream openHttpRangeStream(URL url, long rangeOffset) throws IOException {
        try {
            HttpResponse<InputStream> response = sendHttpRequest(url, rangeOffset, false, 0);
            int status = response.statusCode();
            boolean validPartialResponse = status == 206 && response.headers().firstValue("Content-Range")
                    .map(HttpRangeHeaders::parseContentRange)
                    .map(range -> range.isKnown() && range.start() == rangeOffset)
                    .orElse(false);
            if (validPartialResponse || (rangeOffset == 0L && status == 200)) {
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
        BiliRequestHeaders.recordBiliCdnResponse(url, status);
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

    private static AudioInputStream openModernFallbackStream(AudioInputStream stream, PlaybackRequest request,
            float startOffsetSeconds)
            throws UnsupportedAudioFileException, IOException {
        AudioInputStream pcm = toPcmStream(stream);
        PcmStartupSeekPolicy.Result seek = PcmStartupSeekPolicy.seekToCurrentPlayback(pcm, pcm.getFormat(), request,
                startOffsetSeconds);
        pcm = requireReadablePcm(pcm, "no decoded PCM after HTTP seek");
        if (startOffsetSeconds > 0f) {
            pcm = PcmFadeInAudioInputStream.wrap(pcm, MP3_SEEK_FADE_MILLIS);
        }
        StereoOpenALHandler stereo = new StereoOpenALHandler();
        stereo.setSampleRate((int) pcm.getFormat().getSampleRate());
        ClientAudioOutputRegistry.registerStereo(stereo, request.pos(), seek.timelineOffsetSeconds(),
                request.sessionId(), request.ownerId());
        LOGGER.debug(
                "HTTP 音频起播追赶完成: session={} captured={}ms effective={}ms setup={}ms passes={} offset={}s skippedBytes={} frameSize={} aligned={}",
                request.sessionId(), request.elapsedMillis(), seek.timelineOffsetMillis(),
                AudioStartupSync.elapsedSinceCaptureMillis(request.capturedNanos(), System.nanoTime()),
                seek.passes(), startOffsetSeconds, seek.skippedBytes(), seek.frameSize(), seek.isFrameAligned());
        return new OpenALTappedAudioInputStream(pcm, stereo, () -> {
            ClientAudioOutputRegistry.unregisterStereo(stereo);
            stereo.cleanup();
        });
    }

    private static AudioInputStream applyStartOffset(AudioInputStream stream, float startOffsetSeconds)
            throws UnsupportedAudioFileException, IOException {
        if (startOffsetSeconds <= 0f) {
            return stream;
        }
        AudioInputStream pcm = toPcmStream(stream);
        PcmStartupSeekPolicy.skipFixedOffset(pcm, pcm.getFormat(), startOffsetSeconds);
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

    private static Fmp4AudioStreamSeeker.StreamStart openFmp4StreamStart(URL url, PlaybackRequest request,
            float startOffsetSeconds) throws IOException {
        return Fmp4AudioStreamSeeker.open(url, request, startOffsetSeconds);
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
            PlaybackRequest request,
            float startOffsetSeconds) {
        long[] decoded = { 0L };
        long[] mdatBytes = { 0L };

        try {
            Fmp4AudioStreamSeeker.StreamStart streamStart = openFmp4StreamStart(url, request, startOffsetSeconds);
            final float effectiveStartOffsetSeconds = streamStart.startOffsetSeconds();
            try (InputStream body = streamStart.stream()) {
                bodyRef.set(body);
                if (closed.get()) {
                    return;
                }
                Fmp4StreamParser parser = new Fmp4StreamParser();
                Fmp4StreamParser.ContainerKind containerKind = parser.parse(body, closed,
                        new Fmp4StreamParser.Callback() {
                            @Override
                            public void onMoov(Fmp4ToMp4Converter.ParseResult parseResult, byte[] moovData)
                                    throws IOException, UnsupportedAudioFileException {
                                if (pipelineRef.get() != null) {
                                    return;
                                }
                                AudioPipelineFactory.Selection selection = AudioPipelineFactory.selectFmp4(parseResult,
                                        Fmp4ToMp4Converter.listAudioCodecs(moovData), fallbackPipe, closed, request,
                                        effectiveStartOffsetSeconds);
                                if (selection instanceof AudioPipelineFactory.Supported supported) {
                                    activatePipeline(url, pipelineRef, supported.pipeline(), formatReady);
                                } else if (selection instanceof AudioPipelineFactory.Unsupported unsupported) {
                                    throw new UnsupportedAudioFileException(unsupported.reason());
                                }
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
                            public void onRawEac3(InputStream payload)
                                    throws IOException, UnsupportedAudioFileException {
                                DolbyEc3Pipeline pipeline = createRawDolbyPipeline(closed, request,
                                        effectiveStartOffsetSeconds);
                                activatePipeline(url, pipelineRef, pipeline, formatReady);
                                decoded[0] += pipeline.onRawStream(payload);
                            }
                        });
                if (containerKind == Fmp4StreamParser.ContainerKind.OTHER_AUDIO) {
                    throw new NonPipelineAudioException();
                }

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
                reportRecoverableStreamFailure(url, request, e, decoded[0], mdatBytes[0]);
            }
        } catch (IOException e) {
            if (closed.get() || isStreamEndException(e)) {
                LOGGER.debug("Audio stream stopped: closed={} msg={} decoded={} mdatBytes={}",
                        closed.get(), e.getMessage(), decoded[0], mdatBytes[0]);
                LOGGER.trace("Audio stream stop stack", e);
                if (!closed.get() && (decoded[0] > 0L || mdatBytes[0] > 0L)) {
                    reportRecoverableStreamFailure(url, request, e, decoded[0], mdatBytes[0]);
                }
            } else {
                LOGGER.error("Audio stream IO failed", e);
                BiliPlaybackDiagnostics.markFailed(url, e);
                if (decoded[0] > 0L || mdatBytes[0] > 0L) {
                    reportRecoverableStreamFailure(url, request, e, decoded[0], mdatBytes[0]);
                }
                errorRef.set(e);
            }
        } catch (UnsupportedAudioFileException e) {
            if (!closed.get()) {
                if (!(e instanceof NonPipelineAudioException)) {
                    LOGGER.warn("Audio stream unsupported: {}", e.getMessage());
                    BiliPlaybackDiagnostics.markFailed(url, e);
                }
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

    private static boolean reportRecoverableStreamFailure(URL url, PlaybackRequest request, Throwable error,
            long decoded, long mdatBytes) {
        if (request == null || request.playbackSessionId().isEmpty()) {
            return false;
        }
        boolean scheduled = SyncedStreamRecoveryRegistry.reportFailure(
                request.playbackSessionId().orElseThrow(), url, error);
        if (scheduled) {
            LOGGER.warn("音频流播放中断，已安排自动续播: session={} decoded={} mdatBytes={} host={} reason={}",
                    request.sessionId(), decoded, mdatBytes, url.getHost(),
                    error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : "unknown");
        }
        return scheduled;
    }

    private static DolbyEc3Pipeline createRawDolbyPipeline(AtomicBoolean closed, PlaybackRequest request,
            float startOffsetSeconds)
            throws UnsupportedAudioFileException {
        if (request == null || !Eac3NativeDecoder.isNativeAvailable()) {
            throw new UnsupportedAudioFileException("raw E-AC-3 requires Dolby playback and native decoder support");
        }
        BlockPos sourcePos = request.pos();
        return new DolbyEc3Pipeline("raw", closed, sourcePos, startOffsetSeconds,
                request.startOffsetSeconds(), request.sessionId(), request.ownerId());
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
            CountDownLatch formatReady,
            AtomicReference<Exception> errorRef) throws IOException, UnsupportedAudioFileException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(FORMAT_WAIT_SECONDS);
        try {
            while (!formatReady.await(100L, TimeUnit.MILLISECONDS)) {
                Exception failure = errorRef.get();
                if (failure instanceof UnsupportedAudioFileException unsupported) {
                    throw unsupported;
                }
                if (failure instanceof IOException io) {
                    throw io;
                }
                if (failure != null) {
                    throw new IOException("Audio stream handling failed", failure);
                }
                if (System.nanoTime() >= deadlineNanos) {
                    closed.set(true);
                    closeBody(bodyRef);
                    worker.interrupt();
                    BiliPlaybackDiagnostics.markClosed(url);
                    throw new IOException("timed out waiting for audio format");
                }
            }
            throwIfFailed(errorRef);
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

    /** 后台探测确认内容应交给普通 Java Sound/NetMusic HTTP handler。 */
    private static final class NonPipelineAudioException extends UnsupportedAudioFileException {
        private NonPipelineAudioException() {
            super("audio container is not handled by the fMP4/raw E-AC-3 pipeline");
        }
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

    private static boolean hasRequestContext(URL url) {
        return PlaybackSync.parseMediaRequestToken(url.toString()).map(REQUESTS::containsToken).orElse(false);
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
        private final Optional<PlaybackSessionId> playbackSessionId;
        private final UUID minecartUuid;
        private final AtomicBoolean closed;
        private final AtomicReference<InputStream> bodyRef;
        private final Thread worker;
        private final BlockingAudioPipe fallbackPipe;
        private final AtomicReference<AudioDecodePipeline> pipelineRef;
        private final CountDownLatch formatReady;

        private ActiveStreamControl(URL url, BlockPos pos, Optional<PlaybackSessionId> playbackSessionId,
                UUID minecartUuid, AtomicBoolean closed,
                AtomicReference<InputStream> bodyRef, Thread worker, BlockingAudioPipe fallbackPipe,
                AtomicReference<AudioDecodePipeline> pipelineRef, CountDownLatch formatReady) {
            this.url = url;
            this.pos = AudioUtils.copyPos(pos);
            this.playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
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

    private static void closeStaleModernStreams(BlockPos pos, Optional<PlaybackSessionId> playbackSessionId,
            UUID minecartUuid) {
        if ((pos == null && minecartUuid == null) || playbackSessionId == null || playbackSessionId.isEmpty()) {
            return;
        }
        PlaybackSessionId currentSessionId = playbackSessionId.orElseThrow();
        for (ActiveStreamControl control : ACTIVE_MODERN_STREAMS) {
            boolean sameSource = minecartUuid != null
                    ? minecartUuid.equals(control.minecartUuid)
                    : control.minecartUuid == null && control.pos != null && control.pos.equals(pos);
            if (sameSource && !playbackSessionId.equals(control.playbackSessionId)) {
                LOGGER.debug("关闭旧现代音频流: pos={} oldSession={} newSession={}", pos,
                        control.playbackSessionId.map(session -> session.value()).orElse(""), currentSessionId);
                control.close();
            }
        }
    }

    public record RegisteredRequest(String url, Optional<MediaRequestToken> requestToken) {
        public RegisteredRequest {
            url = url == null ? "" : url;
            requestToken = requestToken == null ? Optional.empty() : requestToken;
        }
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
