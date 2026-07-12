package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntablePlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntableTimeline;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ModernTurntableSound extends SyncedMediaSound {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BLOCK_STATE_GRACE_TICKS = 40;
    private static final int MINECART_MISSING_GRACE_TICKS = 200;
    private static final long STREAM_RETRY_DELAY_MILLIS = 750L;

    /** 客户端全局音量倍率，由 GUI 音量滑块控制。范围 [0, 2]，默认 1.0 */
    public static volatile float clientVolume = 1.0f;

    private final BlockPos pos;
    private final String rawUrl;
    private final String songName;
    private final long totalMillis;
    private boolean sessionFinished;
    private int minecartMissingTicks;

    public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord) {
        this(pos, songUrl, timeSecond, lyricRecord, "", 0L);
    }

    public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId) {
        this(pos, songUrl, timeSecond, lyricRecord, sessionId, 0L);
    }

    public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId,
            long startOffsetMillis) {
        this(pos, songUrl, timeSecond, lyricRecord, sessionId, startOffsetMillis, "", "",
                Math.max(0, timeSecond) * 1000L);
    }

    public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId,
            long startOffsetMillis, String rawUrl, String songName, long totalMillis) {
        super(songUrl, timeSecond, lyricRecord, sessionId, startOffsetMillis);
        this.pos = pos;
        this.rawUrl = rawUrl != null ? rawUrl : "";
        this.songName = songName != null ? songName : "";
        this.totalMillis = Math.max(0L, totalMillis);
        this.x = pos.getX() + 0.5D;
        this.y = pos.getY() + 0.5D;
        this.z = pos.getZ() + 0.5D;
        this.volume = Math.max(0.01F, 4.0F * clientVolume);
        ModernTurntablePlaybackTracker.registerSound(this);
        SyncedStreamRecoveryRegistry.register(this.sessionId, this::recoverStream);
    }

    @Override
    public void tick() {
        this.volume = Math.max(0.01F, 4.0F * clientVolume);
        var level = Minecraft.getInstance().level;
        if (level == null) {
            stopAndFinish();
            return;
        }

        tick++;
        boolean movingSource = ClientMinecartAudioAnchors.isMoving(sessionId);
        Vec3 currentMovingPos = ClientMinecartAudioAnchors.currentPosition(sessionId);
        Vec3 movingPos = currentMovingPos != null ? currentMovingPos : ClientMinecartAudioAnchors.position(sessionId);
        if (movingPos != null) {
            this.x = movingPos.x;
            this.y = movingPos.y;
            this.z = movingPos.z;
        }
        if (movingSource) {
            minecartMissingTicks = currentMovingPos != null ? 0 : minecartMissingTicks + 1;
        }
        ModernTurntableBlockEntity turntable = level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity modern
                ? modern
                : null;
        if (!ModernTurntablePlaybackTracker.isCurrent(pos, sessionId)) {
            stopAndFinish();
            return;
        }
        if (tick > tickTimes + 50) {
            stopAndFinish();
            return;
        }

        if (tick > BLOCK_STATE_GRACE_TICKS) {
                if (movingSource ? minecartMissingTicks > MINECART_MISSING_GRACE_TICKS
                    : turntable == null || !turntable.isPlaying()) {
                stopAndFinish();
                return;
            }
        }
        // 唱片被取出时立即停止，不等 grace 过期
        if (turntable != null && !turntable.hasDisc() && tick > 0) {
            stopAndFinish();
            return;
        }

        if (lyricRecord != null) {
            int lyricPos = effectiveLyricTick();
            if (lyricPos >= 0) {
                lyricRecord.updateCurrentLine(lyricPos);
                if (turntable != null && turntable.isPlaying()) {
                    turntable.setClientLyricRecord(lyricRecord, sessionId, lyricPos);
                }
            }
        }

        if (turntable != null && turntable.isPlaying()) {
            ModernTurntablePlaybackDiagnostics.logEveryThreeSeconds(pos, sessionId);
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
    protected void onStreamReady() {
        ModernTurntablePlaybackTracker.markStreamStarted(pos, sessionId);
    }

    @Override
    protected String streamDebugName() {
        return "modern turntable";
    }

    private boolean recoverStream(SyncedStreamRecoveryRegistry.RecoveryRequest request) {
        if (sessionFinished || rawUrl.isBlank() || request.sessionId() == null
                || !request.sessionId().equals(sessionId)) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getConnection() == null) {
            return false;
        }
        ModernTurntableBlockEntity turntable = ModernTurntableTimeline.turntable(pos);
        boolean movingSource = ClientMinecartAudioAnchors.isMoving(sessionId);
        if ((!movingSource && (turntable == null || !turntable.isPlaying()))
            || (movingSource && !ClientMinecartAudioAnchors.isMoving(sessionId))
            || !ModernTurntablePlaybackTracker.isCurrent(pos, sessionId)) {
            return false;
        }
        long delay = STREAM_RETRY_DELAY_MILLIS * Math.max(1L, request.attempt());
        LOGGER.warn("现代唱片机音频流断链，将刷新直链并自动续播: pos={} session={} attempt={} song='{}' reason={}",
                pos, sessionId, request.attempt(), songName,
                request.error() != null ? request.error().toString() : "unknown");
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> retryPlaybackOnClient(request.attempt()));
        });
        return true;
    }

    private void retryPlaybackOnClient(int attempt) {
        if (sessionFinished || !ModernTurntablePlaybackTracker.isCurrent(pos, sessionId)) {
            return;
        }
        ModernTurntableBlockEntity turntable = ModernTurntableTimeline.turntable(pos);
        boolean movingSource = ClientMinecartAudioAnchors.isMoving(sessionId);
        if ((!movingSource && (turntable == null || !turntable.isPlaying()))
            || (movingSource && !ClientMinecartAudioAnchors.isMoving(sessionId))) {
            return;
        }
        long elapsedMillis = movingSource ? -1L : ModernTurntableTimeline.mediaMillis(pos);
        if (elapsedMillis < 0L) {
            elapsedMillis = startOffsetMillis + Math.max(0L, tick) * 50L;
        }
        long durationMillis = totalMillis > 0L ? totalMillis : Math.max(0L, tickTimes * 50L);
        if (durationMillis > 0L && elapsedMillis >= durationMillis - 250L) {
            return;
        }
        SyncedMediaPlaybackLauncher.LaunchResult launch = SyncedMediaPlaybackLauncher.prepare(rawUrl,
                songUrl.toString(), songName, true, GeneralConfig.ENABLE_PLAYER_LYRICS.get(), sessionId,
                Math.max(0L, elapsedMillis), durationMillis, pos, null);
        LOGGER.warn("现代唱片机音频流自动续播: pos={} session={} attempt={} offset={}ms host={}", pos, sessionId,
                attempt, elapsedMillis, ClientMediaPreparer.hostOf(launch.playUrl()));
        LyricRecord retryLyric = launch.lyricRecord() != null ? launch.lyricRecord() : lyricRecord;
        long retryOffset = Math.max(0L, elapsedMillis);
        SyncedMediaPlaybackLauncher.play(new SyncedMediaPlaybackLauncher.LaunchResult(launch.playUrl(), retryLyric),
                songName, (url, ignoredLyric) -> new ModernTurntableSound(pos, url, Math.max(1, tickTimes / 20),
                        retryLyric, sessionId, retryOffset, rawUrl, songName, durationMillis));
        stop();
    }

    /**
     * 返回有效的歌词 tick 位置
     */
    private int effectiveLyricTick() {
        int mediaTick = ModernTurntableTimeline.mediaTick(pos);
        if (mediaTick >= 0) {
            return mediaTick;
        }
        return fallbackLyricTick();
    }

    void stopFromTracker() {
        finishSession();
        stop();
    }

    @Override
    protected void finishSession() {
        ModernTurntablePlaybackTracker.unregisterSound(this);
        if (!sessionFinished) {
            sessionFinished = true;
            SyncedStreamRecoveryRegistry.unregister(sessionId);
            ModernTurntablePlaybackDiagnostics.finish(sessionId);
            ModernTurntablePlaybackTracker.finish(pos, sessionId);
            ClientMinecartAudioAnchors.forget(sessionId);
            VideoBillboardPreview.stopIfSession(sessionId);
            var level = Minecraft.getInstance().level;
            if (level != null && level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable) {
                turntable.clearClientLyricRecord(sessionId);
            }
        }
    }
}
