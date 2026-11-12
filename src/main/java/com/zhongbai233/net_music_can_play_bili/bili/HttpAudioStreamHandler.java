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
    private static final Set<RequestControl> ACTIVE_REQUEST_CONTROLS = ConcurrentHashMap.newKeySet();
    private static final Object REQUEST_LIFECYCLE_LOCK = new Object();

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
        java.util.List<RequestControl> matching;
        synchronized(REQUEST_LIFECYCLE_LOCK) {
            REQUESTS.cancelToken(requestToken);
            BiliPlaybackDiagnostics.cancelRequest(requestToken);
            matching=ACTIVE_REQUEST_CONTROLS.stream().filter(c->requestToken!=null&&requestToken.equals(c.token)).toList();
        }
        for(RequestControl control:matching) control.cancel();
    }

    /** 供其他 handler（如直播）消费自己 URL 上携带的一次性播放请求。 */
    public static PlaybackRequest consumeRegisteredRequest(String url) {
        synchronized (REQUEST_LIFECYCLE_LOCK) {
            return PlaybackSync.parseMediaRequestToken(url).map(REQUESTS::consumeToken).orElse(null);
        }
    }

    public static void closeModernStreams() {
        java.util.List<RequestControl> requests;
        synchronized(REQUEST_LIFECYCLE_LOCK) {
            BiliPlaybackDiagnostics.closePendingRequest(REQUESTS::containsToken);
            requests=java.util.List.copyOf(ACTIVE_REQUEST_CONTROLS);
            REQUESTS.clear();
        }
        for(RequestControl control:requests) control.cancel();
        for (ActiveStreamControl control : ACTIVE_MODERN_STREAMS) {
            control.close();
        }
        ACTIVE_MODERN_STREAMS.clear();
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
        Optional<MediaRequestToken> token = PlaybackSync.parseMediaRequestToken(url.toString());
        PlaybackRequest request;
        RequestControl control;
        synchronized(REQUEST_LIFECYCLE_LOCK) {
            request=consumeRequest(url);
            if(token.isPresent() && request==null) {
                IOException failure=new IOException("playback request expired, cancelled, or already consumed");
                throw failure;
            }
            var diagnosis=BiliPlaybackDiagnostics.beginHttpWorker(url,
                    request!=null?request.sessionId():"",
                    request!=null&&request.pos()!=null?request.pos().toShortString():"",
                    request!=null&&request.ownerId()!=null?request.ownerId().toString():"",
                    request!=null&&request.minecartUuid()!=null?request.minecartUuid().toString():"");
            control=new RequestControl(token.orElse(null),diagnosis);
            if(token.isPresent()) ACTIVE_REQUEST_CONTROLS.add(control);
        }
        try {
            control.checkOpen();
            URL requestUrl=request!=null?URI.create(request.mediaUrl()).toURL():PlaybackSync.strip(url);
            if (request != null && isNativeNetMusicHost(requestUrl)) {
                control.beginFallback();
                return control.attach(fallbackHttpStream(requestUrl,request,null,control),requestUrl,true);
            }
            try {
                return control.attach(handleWithPipeline(requestUrl,request,control),requestUrl,false);
            } catch (NonPipelineAudioException probe) {
                control.beginFallback();
                return control.attach(fallbackHttpStream(requestUrl,request,probe,control),requestUrl,true);
            }
        } catch (UnsupportedAudioFileException e) {
            BiliPlaybackDiagnostics.markFailed(control.diagnosis,e);
            control.cancel();
            throw e;
        } catch (IOException | RuntimeException e) {
            BiliPlaybackDiagnostics.markFailed(control.diagnosis,e);
            control.cancel();
            throw e;
        }
    }

    /**
     * 用 fMP4/EC-3 管线处理音频
     * 现代化唱片机 → OpenAL；普通唱片机 → PcmPipeline
     */
    private AudioInputStream handleWithPipeline(URL url, PlaybackRequest request, RequestControl control)
            throws UnsupportedAudioFileException, IOException {
        final var diagnosis = control.diagnosis;
        long started = System.currentTimeMillis();
        boolean modernTurntable = request != null;
        final float startOffsetSeconds = startOffsetSeconds(request);

        BlockingAudioPipe fallbackPipe = new BlockingAudioPipe(PIPE_BUFFER_SIZE);
        AtomicReference<AudioDecodePipeline> pipelineRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        AtomicReference<InputStream> bodyRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean naturalInputEnd = new AtomicBoolean(false);
        CountDownLatch formatReady = new CountDownLatch(1);

        Thread worker = NetMusicThreadFactory.daemonThread(
                modernTurntable ? "AudioStreamWorker" : "BiliCompatAudioStreamWorker",
                () -> streamDecode(url, fallbackPipe, pipelineRef, errorRef, bodyRef, closed, formatReady,
                        request, startOffsetSeconds, naturalInputEnd,diagnosis));
        ActiveStreamControl streamControl = null;
        if (modernTurntable && request != null) {
            streamControl = new ActiveStreamControl(url, request.pos(), request.playbackSessionId(),
                    request.minecartUuid(), closed,
                    bodyRef, worker, fallbackPipe, pipelineRef, formatReady,diagnosis);
        }
        if (streamControl != null) {
            ACTIVE_MODERN_STREAMS.add(streamControl);
        }
        final ActiveStreamControl ownedStreamControl = streamControl;
        control.startPreparation(() -> {
            formatReady.countDown();
            closeWorker(url,closed,bodyRef,worker,fallbackPipe,pipelineRef.get(),ownedStreamControl,diagnosis,true);
        },worker);

        try {
            awaitFormat(url, closed, bodyRef, worker, formatReady, errorRef,diagnosis);
            LOGGER.debug("HTTP 音频格式就绪: cost={}ms session={} offset={}s host={}",
                    System.currentTimeMillis() - started,
                    request != null ? request.sessionId() : "", startOffsetSeconds, url.getHost());
        } catch (IOException | UnsupportedAudioFileException e) {
            boolean terminal = !(e instanceof NonPipelineAudioException);
            if (terminal) BiliPlaybackDiagnostics.markFailed(diagnosis,e);
            closeWorker(url,closed,bodyRef,worker,fallbackPipe,pipelineRef.get(),streamControl,diagnosis,terminal);
            throw e;
        }
        try {
            throwIfFailed(errorRef);
        } catch (IOException | UnsupportedAudioFileException e) {
            boolean terminal = !(e instanceof NonPipelineAudioException);
            if (terminal) BiliPlaybackDiagnostics.markFailed(diagnosis,e);
            closeWorker(url,closed,bodyRef,worker,fallbackPipe,pipelineRef.get(),streamControl,diagnosis,terminal);
            throw e;
        }

        AudioDecodePipeline pipeline = pipelineRef.get();
        if (pipeline == null) {
            BiliPlaybackDiagnostics.markFailed(diagnosis,new IOException("unable to detect audio format"));
            closeWorker(url,closed,bodyRef,worker,fallbackPipe,null,streamControl,diagnosis,true);
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
            return managedStream(decoded,closed,worker,url,bodyRef,fallbackPipe,pipeline,streamControl,errorRef,diagnosis,control);
        }
        if (pipeline instanceof FlacOpenALPipeline flacPipeline) {
            AudioInputStream tapped = flacPipeline.openTappedStream();
            return managedStream(tapped,closed,worker,url,bodyRef,fallbackPipe,pipeline,streamControl,errorRef,diagnosis,control);
        }
        if (pipeline.usesOpenAlOutput()) {
            return silentStream(format, closed, worker, url, bodyRef, fallbackPipe, pipeline, streamControl,
                    errorRef,naturalInputEnd,diagnosis,control);
        }
        return managedStream(
                new AudioInputStream(fallbackPipe, format, AudioSystem.NOT_SPECIFIED),
                closed,worker,url,bodyRef,fallbackPipe,pipeline,streamControl,errorRef,diagnosis,control);
    }

    private static PlaybackRequest consumeRequest(URL url) {
        return PlaybackSync.parseMediaRequestToken(url.toString()).map(REQUESTS::consumeToken).orElse(null);
    }

    private static float startOffsetSeconds(PlaybackRequest request) {
        return request != null ? request.startOffsetSeconds() : 0f;
    }

    private static AudioInputStream fallbackHttpStream(URL url, PlaybackRequest request,
            UnsupportedAudioFileException probeError,RequestControl control)
            throws UnsupportedAudioFileException, IOException {
        try {
            control.checkOpen();
            LOGGER.debug("Falling back to NetMusic direct HTTP handler for non-fMP4 URL: {}", url);
            float startOffsetSeconds = request != null ? request.startOffsetSeconds() : 0f;
            // MP3 Layer III 帧可能依赖之前的 bit reservoir。即使 HTTP Range 的起点
            // 对齐到合法帧，缺失的历史解码状态仍可能让 MP3SPI/JLayer 持续输出白噪音。
            // 自定义 MP3 显式从文件头建解码状态；网易源则在下方通过专用 handler
            // 从头打开，再统一丢弃已播放的 PCM，保留其鉴权和请求头行为。
            if (request != null && startOffsetSeconds > 1.0f && !isNativeNetMusicHost(url)) {
                AudioInputStream strict = tryOpenSafeCustomMp3Stream(url,request,startOffsetSeconds,control);
                if (strict != null) {
                    return strict;
                }
            }

            NetEaseHttpHandler netEase = new NetEaseHttpHandler();
            control.checkOpen();
            AudioInputStream stream;
            if (netEase.canHandle(url)) {
                stream = netEase.handle(url);
            } else {
                stream = new DirectHttpHandler().handle(url);
            }
            stream=diagnosticStream(stream,control,true);
            final AudioInputStream preparing=stream;
            control.prepareResource(preparing::close);
            return request != null
                    ? openModernFallbackStream(stream,request,startOffsetSeconds,control)
                    : diagnosticStream(applyStartOffset(stream,startOffsetSeconds),control,false);
        } catch (UnsupportedAudioFileException | IOException fallbackError) {
            if (probeError != null) {
                fallbackError.addSuppressed(probeError);
            }
            BiliPlaybackDiagnostics.markFailed(control.diagnosis,fallbackError);
            throw fallbackError;
        }
    }

    private static AudioInputStream tryOpenSafeCustomMp3Stream(URL url, PlaybackRequest request,
            float startOffsetSeconds,RequestControl control) throws IOException, UnsupportedAudioFileException {
        InputStream body = null;
        InputStream aligned = null;
        AudioInputStream encoded = null;
        try {
            body = openHttpRangeStream(url, 0L);
            final InputStream preparing=body;
            control.prepareResource(preparing::close);
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
            AudioInputStream result = openModernFallbackStream(encoded,request,startOffsetSeconds,control);
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
            float startOffsetSeconds,RequestControl control)
            throws UnsupportedAudioFileException, IOException {
        final var diagnosis=control.diagnosis;
        AudioInputStream pcm = stream;
        StereoOpenALHandler allocatedOutput = null;
        try {
            control.checkOpen();
            pcm = toPcmStream(pcm);
            PcmStartupSeekPolicy.Result seek = PcmStartupSeekPolicy.seekToCurrentPlayback(pcm, pcm.getFormat(), request,
                    startOffsetSeconds);
            pcm = requireReadablePcm(pcm, "no decoded PCM after HTTP seek");
            if (startOffsetSeconds > 0f) {
                pcm = PcmFadeInAudioInputStream.wrap(pcm, MP3_SEEK_FADE_MILLIS);
            }
            control.checkOpen();
            pcm = diagnosticStream(pcm,control,true);
            StereoOpenALHandler stereo = new StereoOpenALHandler();
            allocatedOutput = stereo;
            stereo.setSampleRate((int) pcm.getFormat().getSampleRate());
            if (request.ownerId() != null && request.pos() == null && request.minecartUuid() == null
                    && request.totalMillis() > 0L) {
                stereo.configureStartupPacing(request.elapsedMillis(), request.totalMillis(), request.capturedNanos(),
                        seek.skippedBytes() / Math.max(1, seek.frameSize()));
            }
            ClientAudioOutputRegistry.registerStereo(stereo, request.pos(), seek.timelineOffsetSeconds(),
                    request.sessionId(), request.ownerId());
            LOGGER.debug(
                    "HTTP 音频起播追赶完成: session={} captured={}ms effective={}ms setup={}ms passes={} offset={}s skippedBytes={} frameSize={} aligned={}",
                    request.sessionId(), request.elapsedMillis(), seek.timelineOffsetMillis(),
                    AudioStartupSync.elapsedSinceCaptureMillis(request.capturedNanos(), System.nanoTime()),
                    seek.passes(), startOffsetSeconds, seek.skippedBytes(), seek.frameSize(), seek.isFrameAligned());
            return new OpenALTappedAudioInputStream(pcm, stereo, () -> {
                try {
                    ClientAudioOutputRegistry.unregisterStereo(stereo);
                    stereo.cleanup();
                } catch(RuntimeException failure) {
                    BiliPlaybackDiagnostics.markFailed(diagnosis,failure);throw failure;
                } finally { control.finished(diagnosis); }
            });
        } catch (IOException | UnsupportedAudioFileException | RuntimeException failure) {
            BiliPlaybackDiagnostics.markFailed(diagnosis, failure);
            if (allocatedOutput != null) {
                try {
                    ClientAudioOutputRegistry.unregisterStereo(allocatedOutput);
                    allocatedOutput.cleanup();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                pcm.close();
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
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
        ByteArrayInputStream prefix = new ByteArrayInputStream(first, 0, read);
        InputStream prefixed = new InputStream() {
            private final AtomicBoolean closed = new AtomicBoolean();

            private void requireOpen() throws IOException {
                if (closed.get()) {
                    throw new IOException("PCM stream closed");
                }
            }

            @Override
            public int read() throws IOException {
                requireOpen();
                int value = prefix.read();
                return value < 0 ? stream.read() : value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                requireOpen();
                java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
                if (length == 0) {
                    return 0;
                }
                int count = prefix.read(buffer, offset, length);
                // AudioInputStream can return zero while assembling a complete PCM frame.
                // Keep that retryable result instead of letting SequenceInputStream close the tail.
                return count < 0 ? stream.read(buffer, offset, length) : count;
            }

            @Override
            public int available() throws IOException {
                requireOpen();
                return (int) Math.min(Integer.MAX_VALUE, (long) prefix.available() + stream.available());
            }

            @Override
            public void close() throws IOException {
                if (closed.compareAndSet(false, true)) {
                    stream.close();
                }
            }
        };
        return new AudioInputStream(prefixed, stream.getFormat(), AudioSystem.NOT_SPECIFIED);
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
            float startOffsetSeconds,AtomicBoolean naturalInputEnd,BiliPlaybackDiagnostics.Worker diagnosis) {
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
                                    activatePipeline(url,pipelineRef,supported.pipeline(),formatReady,diagnosis,closed);
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
                                activatePipeline(url,pipelineRef,pipeline,formatReady,diagnosis,closed);
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
            if (!closed.get()) {
                if (decoded[0] > 0L) naturalInputEnd.set(true);
                else errorRef.compareAndSet(null, new IOException("audio ended without decoded media"));
            }
        } catch (EOFException e) {
            LOGGER.debug("Audio stream EOF: decoded={} mdatBytes={}", decoded[0], mdatBytes[0]);
            if (!closed.get()) errorRef.compareAndSet(null, new IOException("truncated audio stream", e));
            if (!closed.get() && decoded[0] == 0L && mdatBytes[0] == 0L) {
                BiliPlaybackDiagnostics.markFailed(diagnosis,e);
                errorRef.set(new IOException("audio stream ended before any media bytes", e));
            } else if (!closed.get()) {
                reportRecoverableStreamFailure(url, request, e, decoded[0], mdatBytes[0]);
            }
        } catch (IOException e) {
            if (!closed.get()) errorRef.compareAndSet(null, e);
            if (closed.get() || isStreamEndException(e)) {
                LOGGER.debug("Audio stream stopped: closed={} msg={} decoded={} mdatBytes={}",
                        closed.get(), e.getMessage(), decoded[0], mdatBytes[0]);
                LOGGER.trace("Audio stream stop stack", e);
                if (!closed.get() && (decoded[0] > 0L || mdatBytes[0] > 0L)) {
                    reportRecoverableStreamFailure(url, request, e, decoded[0], mdatBytes[0]);
                }
            } else {
                LOGGER.error("Audio stream IO failed", e);
                BiliPlaybackDiagnostics.markFailed(diagnosis,e);
                if (decoded[0] > 0L || mdatBytes[0] > 0L) {
                    reportRecoverableStreamFailure(url, request, e, decoded[0], mdatBytes[0]);
                }
                errorRef.set(e);
            }
        } catch (UnsupportedAudioFileException e) {
            if (!closed.get()) {
                if (!(e instanceof NonPipelineAudioException)) {
                    LOGGER.warn("Audio stream unsupported: {}", e.getMessage());
                    BiliPlaybackDiagnostics.markFailed(diagnosis,e);
                }
                errorRef.set(e);
            }
        } catch (Exception e) {
            if (!closed.get()) {
                LOGGER.error("Audio stream decode failed", e);
                BiliPlaybackDiagnostics.markFailed(diagnosis,e);
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
                        errorRef.compareAndSet(null, e);
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
            CountDownLatch formatReady,BiliPlaybackDiagnostics.Worker diagnosis,AtomicBoolean closed) {
        if (closed.get() || BiliPlaybackDiagnostics.terminalOrRetired(diagnosis) || !pipelineRef.compareAndSet(null,pipeline)) {
            pipeline.close();
            return;
        }
        if(closed.get() || BiliPlaybackDiagnostics.terminalOrRetired(diagnosis)) {
            if(pipelineRef.compareAndSet(pipeline,null)) pipeline.close();
            return;
        }
        BiliPlaybackDiagnostics.updateFormat(diagnosis,url,pipeline.container(),pipeline.codec(),
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
            ActiveStreamControl streamControl, AtomicReference<Exception> errorRef,
            AtomicBoolean naturalInputEnd,BiliPlaybackDiagnostics.Worker diagnosis,RequestControl control) {
        return new AudioInputStream(fallbackPipe, format, AudioSystem.NOT_SPECIFIED) {
            @Override
            public int read() throws IOException {
                byte[] single = new byte[1];
                return read(single, 0, 1) < 0 ? -1 : 0;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    int n=com.zhongbai233.net_music_can_play_bili.media.stream.TerminalSilence.read(b,off,len,
                            closed.get(),errorRef.get(),naturalInputEnd.get(),pipeline.drainStatus());
                    if(n<0) BiliPlaybackDiagnostics.markClosed(diagnosis);
                    return n;
                } catch(IOException | RuntimeException e) {
                    BiliPlaybackDiagnostics.markFailed(diagnosis,e);throw e;
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    closeWorker(url,closed,bodyRef,worker,fallbackPipe,pipeline,streamControl,diagnosis,true);
                    super.close();
                } finally { control.finished(diagnosis); }
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
            ActiveStreamControl streamControl,AtomicReference<Exception> errorRef,
            BiliPlaybackDiagnostics.Worker diagnosis,RequestControl control) {
        return new AudioInputStream(delegate, delegate.getFormat(), AudioSystem.NOT_SPECIFIED) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    if(closed.get()) throw new com.zhongbai233.net_music_can_play_bili.media.stream.PlaybackStreamCancelledException();
                    if(errorRef.get()!=null) throw new IOException("media producer failed",errorRef.get());
                    int read=delegate.read(b,off,len);
                    if(errorRef.get()!=null) throw new IOException("media producer failed",errorRef.get());
                    if(read<0) BiliPlaybackDiagnostics.markClosed(diagnosis);
                    return read;
                } catch(IOException | RuntimeException e) {
                    BiliPlaybackDiagnostics.markFailed(diagnosis,e);throw e;
                }
            }

            @Override
            public void close() throws IOException {
                IOException error = null;
                try {
                    closeWorker(url,closed,bodyRef,worker,fallbackPipe,pipeline,streamControl,diagnosis,true);
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
                control.finished(diagnosis);
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
            AtomicReference<Exception> errorRef,BiliPlaybackDiagnostics.Worker diagnosis) throws IOException, UnsupportedAudioFileException {
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
                    IOException timeout=new IOException("timed out waiting for audio format");
                    BiliPlaybackDiagnostics.markFailed(diagnosis,timeout);
                    throw timeout;
                }
            }
            throwIfFailed(errorRef);
        } catch (InterruptedException e) {
            closed.set(true);
            closeBody(bodyRef);
            worker.interrupt();
            Thread.currentThread().interrupt();
            IOException failure=new IOException("interrupted while loading audio stream",e);
            BiliPlaybackDiagnostics.markFailed(diagnosis,failure);
            throw failure;
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
            ActiveStreamControl streamControl,BiliPlaybackDiagnostics.Worker diagnosis,boolean terminal) throws IOException {
        if (closed.compareAndSet(false,true) && terminal) {
            BiliPlaybackDiagnostics.markClosed(diagnosis);
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

    /** Keep the tap outermost: source EOF is not output EOF while OpenAL still has queued PCM. */
    private static AudioInputStream diagnosticStream(AudioInputStream delegate,RequestControl control,boolean belowTap) {
        final var diagnosis=control.diagnosis;
        return new AudioInputStream(delegate,delegate.getFormat(),delegate.getFrameLength()) {
            private final AtomicBoolean closed=new AtomicBoolean();
            @Override public int read() throws IOException {
                try {
                    if(control.isCancelled()) throw new com.zhongbai233.net_music_can_play_bili.media.stream.PlaybackStreamCancelledException();
                    int n=delegate.read();
                    if(n<0 && !belowTap) BiliPlaybackDiagnostics.markClosed(diagnosis);
                    return n;
                } catch(IOException | RuntimeException e) {BiliPlaybackDiagnostics.markFailed(diagnosis,e);throw e;}
            }
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                try {
                    if(control.isCancelled()) throw new com.zhongbai233.net_music_can_play_bili.media.stream.PlaybackStreamCancelledException();
                    int n=delegate.read(bytes,offset,length);
                    if(n<0 && !belowTap) BiliPlaybackDiagnostics.markClosed(diagnosis);
                    return n;
                } catch(IOException | RuntimeException e) {BiliPlaybackDiagnostics.markFailed(diagnosis,e);throw e;}
            }
            @Override public long skip(long amount) throws IOException {return delegate.skip(amount);}
            @Override public int available() throws IOException {return delegate.available();}
            @Override public boolean markSupported() {return delegate.markSupported();}
            @Override public void mark(int limit) {delegate.mark(limit);}
            @Override public void reset() throws IOException {delegate.reset();}
            @Override public void close() throws IOException {
                if(!closed.compareAndSet(false,true)) return;
                try {delegate.close();}
                catch(IOException | RuntimeException e) {BiliPlaybackDiagnostics.markFailed(diagnosis,e);throw e;}
                finally {if(!belowTap) control.finished(diagnosis);}
            }
        };
    }

    @FunctionalInterface private interface CloseAction {void close() throws IOException;}

    /** One physical request owns preparation and its final stream, independently of the latest displayed song. */
    private static final class RequestControl {
        private final MediaRequestToken token;
        private volatile BiliPlaybackDiagnostics.Worker diagnosis;
        private boolean cancelled,finished;
        private CloseAction preparation;
        private AudioInputStream attached;
        RequestControl(MediaRequestToken token,BiliPlaybackDiagnostics.Worker diagnosis) {
            this.token=token;this.diagnosis=diagnosis;
        }
        synchronized void checkOpen() throws IOException {
            Throwable failure=BiliPlaybackDiagnostics.failure(diagnosis);
            if(failure instanceof IOException io) throw io;
            if(failure!=null) throw new IOException("playback request failed",failure);
            if(cancelled || finished || BiliPlaybackDiagnostics.terminalOrRetired(diagnosis)) {
                throw new com.zhongbai233.net_music_can_play_bili.media.stream.PlaybackStreamCancelledException();
            }
        }
        synchronized boolean isCancelled() {return cancelled;}
        synchronized void startPreparation(CloseAction close,Thread worker) throws IOException {
            checkOpen();preparation=close;worker.start();
        }
        synchronized void prepareResource(CloseAction close) throws IOException {
            try {checkOpen();}
            catch(IOException stopped) {
                try {close.close();}catch(IOException | RuntimeException cleanup) {stopped.addSuppressed(cleanup);}
                throw stopped;
            }
            preparation=close;
        }
        synchronized void beginFallback() throws IOException {
            checkOpen();preparation=null;diagnosis=BiliPlaybackDiagnostics.beginFallback(diagnosis);checkOpen();
        }
        synchronized AudioInputStream attach(AudioInputStream stream,URL url,boolean fallback) throws IOException {
            try {checkOpen();}
            catch(IOException stopped) {
                try {stream.close();}catch(IOException | RuntimeException cleanup) {stopped.addSuppressed(cleanup);}
                throw stopped;
            }
            attached=stream;preparation=null;
            if(fallback) {
                BiliPlaybackDiagnostics.updateFormat(diagnosis,url,"unknown",stream.getFormat().getEncoding().toString(),
                        stream.getFormat(),"NetMusic HTTP fallback; output audio format");
            }
            return stream;
        }
        synchronized void finished(BiliPlaybackDiagnostics.Worker owner) {
            if(owner!=diagnosis) return;
            finished=true;attached=null;preparation=null;
            BiliPlaybackDiagnostics.markClosed(owner);ACTIVE_REQUEST_CONTROLS.remove(this);
        }
        void cancel() {
            AudioInputStream stream;CloseAction preparing;
            synchronized(this) {
                if(cancelled || finished) return;
                cancelled=true;stream=attached;attached=null;preparing=preparation;preparation=null;
                BiliPlaybackDiagnostics.markClosed(diagnosis);ACTIVE_REQUEST_CONTROLS.remove(this);
            }
            try {
                if(stream!=null) stream.close();
                else if(preparing!=null) preparing.close();
            } catch(IOException | RuntimeException e) {LOGGER.debug("Failed to close owned HTTP playback request",e);}
        }
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
        private final BiliPlaybackDiagnostics.Worker diagnosis;

        private ActiveStreamControl(URL url, BlockPos pos, Optional<PlaybackSessionId> playbackSessionId,
                UUID minecartUuid, AtomicBoolean closed,
                AtomicReference<InputStream> bodyRef, Thread worker, BlockingAudioPipe fallbackPipe,
                AtomicReference<AudioDecodePipeline> pipelineRef,CountDownLatch formatReady,BiliPlaybackDiagnostics.Worker diagnosis) {
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
            this.diagnosis=diagnosis;
        }

        private void close() {
            try {
                formatReady.countDown();
                closeWorker(url,closed,bodyRef,worker,fallbackPipe,pipelineRef.get(),this,diagnosis,true);
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
