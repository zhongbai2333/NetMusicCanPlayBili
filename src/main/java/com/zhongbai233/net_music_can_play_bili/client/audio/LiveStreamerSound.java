package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackRuntimeProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

import java.net.URL;

/**
 * 直播机客户端声音实例。
 *
 * <p>
 * 与唱片机不同：没有时长驱动的结束判定，也没有直链刷新式续播——直播流的断线重连
 * 由 {@code BiliLiveAudioStreamHandler} 的 LiveSession 在流内部完成。这里只负责
 * 生命周期：方块还在播、会话仍是当前会话，就活着；OpenAL 时间线长时间无进展时
 * 自杀，交给服务端每 3 秒的全量重同步重新拉起。
 * </p>
 */
public class LiveStreamerSound extends SyncedMediaSound {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BLOCK_STATE_GRACE_TICKS = 40;
    private static final int LIVE_STALL_TICKS =
            PlaybackRuntimeProperties.watchdog().liveStallTicks();

    private final BlockPos pos;
    private final PlaybackSourceId sourceId;
    private volatile int streamReadyTick = -1;
    private int lastAudioProgressTick = -1;
    private long lastObservedAudioMillis = -1L;
    private final PlaybackPresentationEnvelope presentationEnvelope = new PlaybackPresentationEnvelope();
    private boolean sessionFinished;

    public LiveStreamerSound(BlockPos pos, URL streamUrl, int timeSecond, String sessionId) {
        this(pos, streamUrl, timeSecond, sessionId,
                PlaybackSync.parsePlaybackSourceId(streamUrl.toString()).orElse(null));
    }

    public LiveStreamerSound(BlockPos pos, URL streamUrl, int timeSecond, String sessionId,
            PlaybackSourceId sourceId) {
        super(streamUrl, timeSecond, null, sessionId, 0L);
        this.pos = pos;
        this.sourceId = sourceId;
        this.x = pos.getX() + 0.5D;
        this.y = pos.getY() + 0.5D;
        this.z = pos.getZ() + 0.5D;
        this.volume = 0.0F;
        ModernTurntablePlaybackTracker.registerSound(this, pos, sessionId());
        refreshDecodeDemand();
    }

    @Override
    public void tick() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            stopAndFinish();
            return;
        }
        tick++;

        LiveStreamerBlockEntity streamer = level.getBlockEntity(pos) instanceof LiveStreamerBlockEntity live
                ? live
                : null;
        float configuredVolume = 4.0F * (streamer != null ? streamer.getVolume() : 1.0F);
        boolean audible = ClientAudioOutputRegistry.hasAudioDemand(pos, sourceId, sessionId());
        this.volume = configuredVolume
                * presentationEnvelope.gain(streamReadyTick >= 0 && audible, System.nanoTime());
        refreshDecodeDemand();

        if (!ModernTurntablePlaybackTracker.isCurrent(pos, sessionId())) {
            stopAndFinish();
            return;
        }
        // 剩余时长由服务端滚动续期；超过同步值仍未收到新会话说明服务端已不再广播
        if (tick > tickTimes + 50) {
            stopAndFinish();
            return;
        }
        if (tick > BLOCK_STATE_GRACE_TICKS && !fixedSourceAvailable(streamer)) {
            stopAndFinish();
            return;
        }

        checkLiveStallWatchdog();

        // 直播画面跟随音频会话：有链接的投影仪时启动/维持渲染会话
        if (tick % 20 == 0) {
            com.zhongbai233.net_music_can_play_bili.client.LiveStreamerVideoClient.sync(pos, sessionId());
        }

        if (level.getGameTime() % 8L == 0L) {
            var random = level.getRandom();
            for (int i = 0; i < 2; i++) {
                level.addParticle(
                        ParticleTypes.NOTE,
                        x - 0.5D + random.nextDouble(),
                        y + 1.0D + random.nextDouble() * 0.35D,
                        z - 0.5D + random.nextDouble(),
                        random.nextGaussian(),
                        random.nextGaussian(),
                        random.nextInt(3));
            }
        }
    }

    @Override
    protected void refreshDecodeDemand() {
        setDecodeDemand(ClientAudioOutputRegistry.hasPreparationDemand(pos, sourceId, sessionId()));
    }

    /**
     * 直播流卡死兜底：LiveSession 自己会重连，但如果整条会话已经僵死
     * （OpenAL 时间线长时间不前进），结束本会话，让服务端重同步重建。
     */
    private void checkLiveStallWatchdog() {
        Minecraft minecraft = Minecraft.getInstance();
        if (streamReadyTick < 0 || minecraft.isPaused() || minecraft.options == null
                || minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F
                || minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) <= 0.0F) {
            lastAudioProgressTick = tick;
            return;
        }
        ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getAudioTimeline(pos);
        boolean matchingTimeline = timeline.audioSessionId().isBlank()
                || sessionId().equals(timeline.audioSessionId());
        long observed = matchingTimeline ? Math.max(timeline.audibleMillis(), timeline.fedMillis()) : -1L;
        if (observed < 0L) {
            // 没有可观测的 OpenAL 时间线：HLS 兜底走 Minecraft 声音引擎播放，
            // 或输出尚未注册。此时无进展信号可依据，不能当作卡死。
            lastAudioProgressTick = tick;
            return;
        }
        if (observed > lastObservedAudioMillis) {
            lastObservedAudioMillis = observed;
            lastAudioProgressTick = tick;
            return;
        }
        int stalledTicks = tick - Math.max(streamReadyTick, lastAudioProgressTick);
        if (stalledTicks < LIVE_STALL_TICKS) {
            return;
        }
        LOGGER.warn("直播音频长时间无进展，结束当前会话等待服务端重新同步: pos={} session={} stalled={}ms",
                pos, sessionId(), stalledTicks * 50L);
        stopAndFinish();
    }

    @Override
    protected void onStreamReady() {
        ModernTurntablePlaybackCoordinator.markIndexedStreamPlaying(pos, sourceId, sessionId());
        ModernTurntablePlaybackTracker.markStreamStarted(pos, sessionId());
        streamReadyTick = tick;
        lastAudioProgressTick = tick;
    }

    @Override
    protected void onDemandIdle() {
        ModernTurntablePlaybackTracker.unregisterSound(this);
    }

    @Override
    protected void onStreamFailure(Exception error) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            ModernTurntablePlaybackTracker.fail(pos, sessionId());
            finishSession();
            stop();
        });
    }

    private boolean fixedSourceAvailable(LiveStreamerBlockEntity streamer) {
        return streamer != null ? streamer.isPlaying()
                : sourceId != null && ClientAudioEndpointIndex.sourcePosition(sourceId) != null;
    }

    @Override
    protected void finishSession() {
        if (sessionFinished) {
            return;
        }
        sessionFinished = true;
        com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId.parse(sessionId()).ifPresent(session ->
                com.zhongbai233.net_music_can_play_bili.client.sync.LiveRoomMetadataRegistry.remove(
                        new com.zhongbai233.net_music_can_play_bili.client.sync.LiveRoomMetadataRegistry.SourceKey(
                                pos.getX(), pos.getY(), pos.getZ()), session));
        ModernTurntablePlaybackTracker.unregisterSound(this);
        com.zhongbai233.net_music_can_play_bili.client.LiveStreamerVideoClient.forget(sessionId());
        ModernTurntablePlaybackCoordinator.finishSession(pos, sessionId());
    }

    @Override
    protected String streamDebugName() {
        return "live streamer";
    }
}
