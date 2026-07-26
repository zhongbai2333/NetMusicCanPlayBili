package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.client.api.implement.M3u8Handler;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AacOpenALPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AacPcmPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AudioDecodePipeline;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.media.stream.FlvStreamParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveReconnectPolicy;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus;
import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.LifecycleClose;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * B站直播音频播放入口。
 *
 * <p>
 * 广播喇叭里输入的 {@code live:直播间号} 会被改写成占位地址 {@code http://live/<直播间号>.m3u8}
 * 后再发往服务端，因为 NetMusic 只接受 m3u8 形式的广播地址。占位地址不会被真正请求：
 * 它一路带到客户端，由本 handler 在声音线程上解析成真实直播流。
 * </p>
 *
 * <p>
 * 播放优先走 HTTP-FLV，由本模组自己解复用并用现有 AAC 管线解码；只有直播间没有 FLV 地址时，
 * 才把解析出来的 m3u8 交回 NetMusic 的通用播放路径。
 * </p>
 */
public final class BiliLiveAudioStreamHandler implements IAudioStreamHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 必须高于 NetMusic 自带 M3u8Handler 的 100，否则占位地址会被它先接走。 */
    private static final int PRIORITY = 300;

    private static final int PIPE_INITIAL_BYTES = 512 * 1024;
    private static final int PIPE_MAX_BYTES = 4 * 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final int FORMAT_WAIT_SECONDS = Math.max(5,
            NcpbSystemProperties.intValue("ncpb.bili.live.format_wait_seconds", 20));
    private static final long WORKER_JOIN_TIMEOUT_MILLIS = 2_000L;

    @Override
    public boolean canHandle(URL url) {
        return url != null
                && !BiliLiveRoomInput.roomIdFromPlaceholder(PlaybackSync.strip(url.toString())).isEmpty();
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        String roomId = BiliLiveRoomInput.roomIdFromPlaceholder(PlaybackSync.strip(url.toString()));
        if (roomId.isEmpty()) {
            throw new UnsupportedAudioFileException("不是 B站直播占位地址: " + url);
        }
        // 直播机走现代播放链路时会携带一次性播放请求；广播喇叭没有。
        PlaybackRequest request = HttpAudioStreamHandler.consumeRegisteredRequest(url.toString());

        if (LiveOfflineBackoff.isBlocked(roomId)) {
            // 退避期内不访问 B站 API，快速失败等下一轮探测。
            throw new IOException("直播间 " + roomId + " 未开播（退避重试中）");
        }

        BiliLiveStreamResolver.LiveRoom room = BiliLiveStreamResolver.resolve(roomId);
        if (!room.isLive()) {
            LiveOfflineBackoff.recordOffline(roomId);
            LiveOfflineBackoff.recordOffline(room.roomId());
            showOfflineOverlay(roomId);
            throw new IOException("直播间 " + roomId + " " + BiliLiveStreamResolver.describeLiveStatus(
                    room.liveStatus()));
        }
        LiveOfflineBackoff.clear(roomId);
        LiveOfflineBackoff.clear(room.roomId());

        if (room.flvUrls().isEmpty()) {
            List<String> hlsUrls = room.hlsUrls();
            if (hlsUrls.isEmpty()) {
                throw new IOException("直播间 " + roomId + " 没有可用的直播流地址");
            }
            LOGGER.info("直播间 {} 没有 FLV 地址，回退到 NetMusic 的 m3u8 播放路径{}", roomId,
                    request != null ? "（音响/耳机中继在该模式下不可用）" : "");
            return new M3u8Handler().handle(URI.create(hlsUrls.get(0)).toURL());
        }

        LOGGER.info("开始播放 B站直播: room={} realRoom={} status={} mode={} flvCandidates={}",
                roomId, room.roomId(), BiliLiveStreamResolver.describeLiveStatus(room.liveStatus()),
                request != null ? "openal" : "pcm", room.flvUrls().size());
        return openFlvStream(room.roomId(), room, request);
    }

    /** 在物品栏上方提示未开播；声音线程调用，转到客户端主线程执行。 */
    private static void showOfflineOverlay(String roomId) {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            minecraft.execute(() -> {
                if (minecraft.gui != null) {
                    minecraft.gui.setOverlayMessage(net.minecraft.network.chat.Component.translatable(
                            "message.net_music_can_play_bili.live_streamer.room_offline_waiting", roomId,
                            LiveOfflineBackoff.retryMillis() / 1000L), false);
                }
            });
        } catch (RuntimeException ignored) {
            // 提示是尽力而为，不影响播放主流程。
        }
    }

    private static AudioInputStream openFlvStream(String roomId, BiliLiveStreamResolver.LiveRoom initialRoom,
            PlaybackRequest request) throws IOException {
        LiveSession session = new LiveSession(roomId, initialRoom, request);
        session.start();
        try {
            AudioFormat format = session.awaitFormat();
            LOGGER.debug("B站直播音频格式就绪: room={} format={}Hz/{}ch/{}bit",
                    roomId, format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits());
            if (session.usesOpenAlOutput()) {
                return silentStream(session, format);
            }
            return new AudioInputStream(session.pipe, format, AudioSystem.NOT_SPECIFIED) {
                @Override
                public void close() throws IOException {
                    session.close();
                    super.close();
                }
            };
        } catch (IOException e) {
            session.close();
            throw e;
        }
    }

    /** OpenAL 模式下 Minecraft 声音引擎只消费静音数据，真实音频由空间音频输出。 */
    private static AudioInputStream silentStream(LiveSession session, AudioFormat format) {
        return new AudioInputStream(session.pipe, format, AudioSystem.NOT_SPECIFIED) {
            @Override
            public int read() {
                return session.closed.get() ? -1 : 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (session.closed.get()) {
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
                session.close();
                super.close();
            }
        };
    }

    /** 一次直播播放的完整生命周期：解析、连接、解复用、断线重连。 */
    private static final class LiveSession {
        private final String roomId;
        private final PlaybackRequest request;
        /** OpenAL 模式下由同一条 FLV 连接向直播视频解码器供样本；PCM 模式为 null。 */
        private final LiveVideoSampleBus videoBus;
        private final BlockingAudioPipe pipe = new BlockingAudioPipe(PIPE_INITIAL_BYTES, PIPE_MAX_BYTES);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<AudioDecodePipeline> pipelineRef = new AtomicReference<>();
        private final AtomicReference<byte[]> ascRef = new AtomicReference<>();
        private final AtomicReference<InputStream> bodyRef = new AtomicReference<>();
        private final AtomicReference<Exception> errorRef = new AtomicReference<>();
        private final CountDownLatch formatReady = new CountDownLatch(1);
        private final Thread worker;

        private volatile BiliLiveStreamResolver.LiveRoom room;

        private LiveSession(String roomId, BiliLiveStreamResolver.LiveRoom initialRoom, PlaybackRequest request) {
            this.roomId = roomId;
            this.room = initialRoom;
            this.request = request;
            this.videoBus = request != null && !request.sessionId().isBlank()
                    ? LiveVideoSampleBus.register(request.sessionId())
                    : null;
            if (this.videoBus != null) {
                LOGGER.debug("直播视频样本总线注册: session={}", this.videoBus.key());
            }
            this.worker = NetMusicThreadFactory.daemonThread("BiliLiveAudio-" + roomId, this::runLiveLoop);
        }

        private boolean usesOpenAlOutput() {
            AudioDecodePipeline pipeline = pipelineRef.get();
            return pipeline != null && pipeline.usesOpenAlOutput();
        }

        private void start() {
            worker.start();
        }

        private AudioFormat awaitFormat() throws IOException {
            try {
                if (!formatReady.await(FORMAT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IOException("等待直播音频格式超时: room=" + roomId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待直播音频格式时被中断", e);
            }

            AudioDecodePipeline pipeline = pipelineRef.get();
            if (pipeline != null) {
                return pipeline.format();
            }
            Exception failure = errorRef.get();
            if (failure instanceof IOException io) {
                throw io;
            }
            throw new IOException("无法打开直播音频流: room=" + roomId, failure);
        }

        private void runLiveLoop() {
            LiveReconnectPolicy policy = new LiveReconnectPolicy();
            try {
                while (!closed.get()) {
                    long startedAt = System.nanoTime();
                    boolean fatal = connectOnce();
                    if (fatal || closed.get()) {
                        break;
                    }
                    // 直播地址带有有效期，重连前一律重新解析。
                    room = null;
                    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                    long delay = policy.onStreamEnded(elapsedMillis);
                    if (delay == LiveReconnectPolicy.GIVE_UP) {
                        LOGGER.warn("B站直播连续 {} 次连接失败，停止重连: room={}",
                                policy.consecutiveFailures(), roomId);
                        break;
                    }
                    LOGGER.info("B站直播流中断，{}ms 后重连: room={} 本次连接时长={}ms", delay, roomId, elapsedMillis);
                    if (!sleepQuietly(delay)) {
                        break;
                    }
                }
            } finally {
                formatReady.countDown();
                pipe.closeWriter();
                if (videoBus != null) {
                    videoBus.close();
                }
                finishPipeline();
            }
        }

        /** 直播循环退出后冲刷并释放解码管线（OpenAL 模式需要注销空间音频输出）。 */
        private void finishPipeline() {
            AudioDecodePipeline pipeline = pipelineRef.get();
            if (pipeline == null) {
                return;
            }
            try {
                pipeline.finish();
            } catch (IOException e) {
                LOGGER.debug("直播音频管线冲刷失败: room={} reason={}", roomId, e.getMessage());
            }
            pipeline.close();
        }

        /** @return true 表示遇到了不该重连的错误 */
        private boolean connectOnce() {
            try {
                BiliLiveStreamResolver.LiveRoom current = room;
                if (current == null) {
                    current = BiliLiveStreamResolver.resolve(roomId);
                    room = current;
                }
                if (!current.isLive()) {
                    LOGGER.info("B站直播已结束: room={} status={}", roomId,
                            BiliLiveStreamResolver.describeLiveStatus(current.liveStatus()));
                    LiveOfflineBackoff.recordOffline(roomId);
                    showOfflineOverlay(roomId);
                    return true;
                }
                List<String> candidates = current.flvUrls();
                if (candidates.isEmpty()) {
                    recordFailure(new IOException("直播间 " + roomId + " 没有可用的 FLV 地址"));
                    return pipelineRef.get() == null;
                }
                streamCandidates(candidates);
                return false;
            } catch (UnsupportedAudioFileException e) {
                recordFailure(e);
                return true;
            } catch (IOException e) {
                recordFailure(e);
                return false;
            } catch (RuntimeException e) {
                recordFailure(e);
                return true;
            }
        }

        /** 依次尝试各 CDN，直到某一路真正解出音频帧。 */
        private void streamCandidates(List<String> candidates) throws IOException, UnsupportedAudioFileException {
            IOException lastError = null;
            for (String candidate : candidates) {
                if (closed.get()) {
                    return;
                }
                URI uri;
                try {
                    uri = URI.create(candidate);
                } catch (IllegalArgumentException e) {
                    lastError = new IOException("直播流地址无法解析: " + candidate, e);
                    continue;
                }
                try {
                    long frames = streamOnce(uri);
                    if (frames > 0L || closed.get()) {
                        return;
                    }
                    lastError = new IOException("直播流没有产生任何音频帧: host=" + uri.getHost());
                } catch (IOException e) {
                    lastError = e;
                    LOGGER.debug("B站直播 CDN 连接失败，尝试下一个: room={} host={} reason={}",
                            roomId, uri.getHost(), e.getMessage());
                }
            }
            if (lastError != null) {
                throw lastError;
            }
        }

        private long streamOnce(URI uri) throws IOException, UnsupportedAudioFileException {
            InputStream body = openLiveBody(uri);
            bodyRef.set(body);
            if (videoBus != null) {
                // 新连接的 FLV 时间戳基准可能跳变：作废旧锚点，视频端重新等关键帧。
                videoBus.beginConnection();
            }
            try {
                return new FlvStreamParser().parse(body, closed::get, new FlvStreamParser.Callback() {
                    @Override
                    public void onAacSequenceHeader(byte[] audioSpecificConfig)
                            throws UnsupportedAudioFileException {
                        acceptSequenceHeader(audioSpecificConfig);
                    }

                    @Override
                    public void onAacFrame(byte[] frame, long timestampMillis) throws IOException {
                        AudioDecodePipeline pipeline = pipelineRef.get();
                        if (pipeline == null) {
                            return;
                        }
                        if (videoBus != null && !videoBus.hasAudioAnchor()) {
                            long fedMillis = ClientAudioOutputRegistry.getAudioTimeline(request.pos()).fedMillis();
                            videoBus.setAudioAnchor(timestampMillis, Math.max(0L, fedMillis));
                            LOGGER.debug("直播视频时间锚已建立: session={} flvTs={}ms fed={}ms",
                                    videoBus.key(), timestampMillis, Math.max(0L, fedMillis));
                        }
                        pipeline.onAudioFrame(frame);
                    }

                    @Override
                    public boolean wantsVideo() {
                        return videoBus != null;
                    }

                    @Override
                    public void onAvcSequenceHeader(byte[] avcConfig) {
                        videoBus.publishConfig(avcConfig);
                    }

                    @Override
                    public void onAvcSample(byte[] sample, long dtsMillis, int compositionTimeMillis,
                            boolean keyframe) {
                        videoBus.pushSample(sample, dtsMillis, compositionTimeMillis, keyframe);
                    }
                });
            } finally {
                bodyRef.compareAndSet(body, null);
                LifecycleClose.closeQuietly(body);
            }
        }

        private void acceptSequenceHeader(byte[] audioSpecificConfig) throws UnsupportedAudioFileException {
            byte[] asc = audioSpecificConfig.clone();
            byte[] known = ascRef.get();
            if (known != null) {
                if (!Arrays.equals(known, asc)) {
                    throw new UnsupportedAudioFileException("直播音频参数发生变化，需要重新开始播放");
                }
                return;
            }
            AudioDecodePipeline pipeline = request != null
                    ? new AacOpenALPipeline(asc, closed, request.pos(), 0f, 0f,
                            request.sessionId(), request.ownerId())
                    : new AacPcmPipeline(asc, pipe);
            ascRef.set(asc);
            pipelineRef.set(pipeline);
            formatReady.countDown();
        }

        private void recordFailure(Exception error) {
            if (closed.get()) {
                return;
            }
            if (pipelineRef.get() == null) {
                errorRef.compareAndSet(null, error);
                LOGGER.warn("B站直播音频打开失败: room={} reason={}", roomId, error.toString());
            } else {
                LOGGER.debug("B站直播音频流中断: room={} reason={}", roomId, error.toString());
            }
        }

        private boolean sleepQuietly(long delayMillis) {
            if (delayMillis <= 0L) {
                return true;
            }
            try {
                Thread.sleep(delayMillis);
                return !closed.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void close() {
            closed.set(true);
            formatReady.countDown();
            if (videoBus != null) {
                videoBus.close();
            }
            LifecycleClose.closeQuietly(bodyRef.getAndSet(null));
            pipe.closeWriter();
            pipe.close();
            LifecycleClose.interruptAndJoin(worker, WORKER_JOIN_TIMEOUT_MILLIS);
            AudioDecodePipeline pipeline = pipelineRef.get();
            if (pipeline != null) {
                pipeline.close();
            }
        }
    }

    private static InputStream openLiveBody(URI uri) throws IOException {
        URL target = uri.toURL();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET();
        BiliRequestHeaders.applyLiveHeaders(builder, target);
        try {
            // 直播是长连接，只保留连接超时，不设置整体响应超时。
            HttpResponse<InputStream> response = BiliWbiSigner.HTTP.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            BiliRequestHeaders.recordBiliCdnResponse(target, response.statusCode());
            InputStream body = response.body();
            if (response.statusCode() != 200) {
                LifecycleClose.closeQuietly(body);
                throw new IOException("直播流 HTTP " + response.statusCode() + " host=" + target.getHost());
            }
            if (body == null) {
                throw new IOException("直播流响应为空: host=" + target.getHost());
            }
            return new BufferedInputStream(body, READ_BUFFER_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("打开直播流时被中断", e);
        }
    }
}
