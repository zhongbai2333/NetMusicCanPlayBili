package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntablePlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntableTimeline;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackRuntimeProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.net.URL;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.sounds.SoundSource;

public class ModernTurntableSound extends SyncedMediaSound {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BLOCK_STATE_GRACE_TICKS = 40;
    private static final int MINECART_MISSING_GRACE_TICKS = 200;
    private static final long STREAM_RETRY_DELAY_MILLIS = 750L;
    private static final PlaybackRuntimeProperties.Watchdog WATCHDOG =
            PlaybackRuntimeProperties.watchdog();

    private final BlockPos pos;
    private final String rawUrl;
    private final String songName;
    private final PlaybackSourceId sourceId;
    private final long totalMillis;
    private boolean sessionFinished;
    private int minecartMissingTicks;
    private final SyncedStreamRecoveryRegistry.Registration recoveryRegistration;
    private final PlaybackPresentationEnvelope presentationEnvelope = new PlaybackPresentationEnvelope();
    private volatile int streamReadyTick = -1;
    private volatile int lastAudioProgressTick = -1;
    private volatile long lastObservedAudioMillis = -1L;
    private volatile boolean watchdogRecoveryRequested;
    private boolean nowPlayingShown;

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
        this(pos, songUrl, timeSecond, lyricRecord, sessionId, startOffsetMillis, rawUrl, songName, totalMillis,
                PlaybackSync.parsePlaybackSourceId(songUrl.toString()).orElse(null));
    }

    public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId,
            long startOffsetMillis, String rawUrl, String songName, long totalMillis, PlaybackSourceId sourceId) {
        super(songUrl, timeSecond, lyricRecord, sessionId, startOffsetMillis);
        this.pos = pos;
        this.rawUrl = rawUrl != null ? rawUrl : "";
        this.songName = songName != null ? songName : "";
        this.sourceId = sourceId;
        this.totalMillis = Math.max(0L, totalMillis);
        this.x = pos.getX() + 0.5D;
        this.y = pos.getY() + 0.5D;
        this.z = pos.getZ() + 0.5D;
        this.volume = 0.0F;
        recoveryRegistration = SyncedStreamRecoveryRegistry.register(playbackSessionId(), this::recoverStream);
        if (!ModernTurntablePlaybackTracker.onCancel(pos, sessionId(),
                () -> SyncedStreamRecoveryRegistry.unregister(recoveryRegistration))) {
            SyncedStreamRecoveryRegistry.unregister(recoveryRegistration);
        }
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
        boolean movingSource = ClientMinecartAudioAnchors.isMoving(sessionId());
        Vec3 currentMovingPos = ClientMinecartAudioAnchors.currentPosition(sessionId());
        Vec3 movingPos = currentMovingPos != null ? currentMovingPos : ClientMinecartAudioAnchors.position(sessionId());
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
        float configuredVolume = 4.0F * (turntable != null ? turntable.getVolume() : 1.0F);
        boolean audible = ClientAudioOutputRegistry.hasAudioDemand(pos, sourceId, sessionId());
        this.volume = configuredVolume
                * presentationEnvelope.gain(streamReadyTick >= 0 && audible, System.nanoTime());
        refreshDecodeDemand();
        if (!ModernTurntablePlaybackTracker.isCurrent(pos, sessionId())) {
            stopAndFinish();
            return;
        }
        if (tick > tickTimes + 50) {
            stopAndFinish();
            return;
        }

        if (tick > BLOCK_STATE_GRACE_TICKS) {
            if (movingSource ? minecartMissingTicks > MINECART_MISSING_GRACE_TICKS
                    : !fixedSourceAvailable(turntable)) {
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
                    turntable.setClientLyricRecord(lyricRecord, sessionId(), lyricPos);
                }
            }
        }

        if (turntable != null && turntable.isPlaying()) {
            ModernTurntablePlaybackDiagnostics.logEveryThreeSeconds(pos, sessionId());
        }
        showNowPlayingWhenAudible();
        checkAudioProgressWatchdog(turntable, movingSource);

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
        SyncedStreamRecoveryRegistry.unregister(recoveryRegistration);
    }

    private void showNowPlayingWhenAudible() {
        if (nowPlayingShown || streamReadyTick < 0 || songName.isBlank()
                || !ClientAudioOutputRegistry.hasAudibleOutput(pos)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.setNowPlaying(Component.literal(songName));
            nowPlayingShown = true;
        }
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

    @Override
    protected String streamDebugName() {
        return "modern turntable";
    }

    private boolean recoverStream(SyncedStreamRecoveryRegistry.RecoveryRequest request) {
        if (sessionFinished || rawUrl.isBlank() || request.sessionId() == null
                || !request.sessionId().equals(sessionId()) || !hasCurrentDemand()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getConnection() == null) {
            return false;
        }
        ModernTurntableBlockEntity turntable = ModernTurntableTimeline.turntable(pos);
        boolean movingSource = ClientMinecartAudioAnchors.isMoving(sessionId());
        if ((!movingSource && !fixedSourceAvailable(turntable))
                || (movingSource && !ClientMinecartAudioAnchors.isMoving(sessionId()))
                || !ModernTurntablePlaybackTracker.isCurrent(pos, sessionId())) {
            return false;
        }
        long delay = STREAM_RETRY_DELAY_MILLIS * Math.max(1L, request.attempt());
        ModernTurntablePlaybackTracker.markRecovering(pos, sessionId());
        LOGGER.warn("现代唱片机音频流断链，将刷新直链并自动续播: pos={} session={} attempt={} song='{}' reason={}",
                pos, sessionId(), request.attempt(), songName,
                request.error() != null ? request.error().toString() : "unknown");
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> retryPlaybackOnClient(request.attempt()));
        });
        return true;
    }

    private void retryPlaybackOnClient(int attempt) {
        if (sessionFinished || !ModernTurntablePlaybackTracker.isCurrent(pos, sessionId())
                || !hasCurrentDemand()) {
            return;
        }
        ModernTurntableBlockEntity turntable = ModernTurntableTimeline.turntable(pos);
        boolean movingSource = ClientMinecartAudioAnchors.isMoving(sessionId());
        if ((!movingSource && !fixedSourceAvailable(turntable))
                || (movingSource && !ClientMinecartAudioAnchors.isMoving(sessionId()))) {
            return;
        }
        long elapsedMillis = movingSource ? -1L : PlaybackClock.mediaMillis(pos);
        if (elapsedMillis < 0L) {
            elapsedMillis = startOffsetMillis + Math.max(0L, tick) * 50L;
        }
        long durationMillis = totalMillis > 0L ? totalMillis : Math.max(0L, tickTimes * 50L);
        if (durationMillis > 0L && elapsedMillis >= durationMillis - 250L) {
            return;
        }
        CompletableFuture<ClientMediaPreparer.PreparedMedia> prepare = ClientMediaPreparer.prepareAudioOnlyAsync(
                rawUrl, songUrl.toString(), songName, true);
        if (!ModernTurntablePlaybackTracker.onCancel(pos, sessionId(), () -> prepare.cancel(false))) {
            prepare.cancel(false);
            return;
        }
        prepare.whenComplete((prepared, error) -> Minecraft.getInstance().execute(
                () -> finishRetryPrepare(attempt, prepared, error)));
    }

    private void finishRetryPrepare(int attempt, ClientMediaPreparer.PreparedMedia prepared, Throwable error) {
        if (sessionFinished || !ModernTurntablePlaybackTracker.isActiveSession(pos, sessionId())
                || !hasCurrentDemand()) {
            return;
        }
        ModernTurntableBlockEntity turntable = ModernTurntableTimeline.turntable(pos);
        boolean movingSource = ClientMinecartAudioAnchors.isMoving(sessionId());
        if ((!movingSource && !fixedSourceAvailable(turntable))
                || (movingSource && !ClientMinecartAudioAnchors.isMoving(sessionId()))) {
            return;
        }
        long elapsedMillis = movingSource ? -1L : PlaybackClock.mediaMillis(pos);
        if (elapsedMillis < 0L) {
            elapsedMillis = startOffsetMillis + Math.max(0L, tick) * 50L;
        }
        long durationMillis = totalMillis > 0L ? totalMillis : Math.max(0L, tickTimes * 50L);
        if (durationMillis > 0L && elapsedMillis >= durationMillis - 250L) {
            return;
        }
        if (error != null) {
            LOGGER.warn("现代唱片机续播直链后台刷新失败，沿用旧直链: pos={} session={} reason={}",
                    pos, sessionId(), error.toString());
        }
        SyncedMediaPlaybackLauncher.LaunchResult launch = SyncedMediaPlaybackLauncher.fromPrepared(rawUrl,
                songName, prepared, songUrl.toString(), sessionId(), Math.max(0L, elapsedMillis), durationMillis,
                pos, null);
        if (launch == null) {
            return;
        }
        if (launch.requestToken().isPresent()) {
            MediaRequestToken requestToken = launch.requestToken().orElseThrow();
            if (!ModernTurntablePlaybackTracker.onCancel(pos, sessionId(),
                    () -> HttpAudioStreamHandler.cancelRequest(requestToken))) {
                HttpAudioStreamHandler.cancelRequest(requestToken);
                return;
            }
        }
        LOGGER.warn("现代唱片机音频流自动续播: pos={} session={} attempt={} offset={}ms host={}", pos, sessionId(),
                attempt, elapsedMillis, ClientMediaPreparer.hostOf(launch.playUrl()));
        LyricRecord retryLyric = launch.lyricRecord() != null ? launch.lyricRecord() : lyricRecord;
        long retryOffset = Math.max(0L, elapsedMillis);
        SyncedMediaPlaybackLauncher.play(new SyncedMediaPlaybackLauncher.LaunchResult(launch.playUrl(), retryLyric,
                launch.requestToken()),
                songName, (url, ignoredLyric) -> new ModernTurntableSound(pos, url, Math.max(1, tickTimes / 20),
                retryLyric, sessionId(), retryOffset, rawUrl, songName, durationMillis, sourceId), false);
        retireForRecovery();
        stop();
    }

    private void checkAudioProgressWatchdog(ModernTurntableBlockEntity turntable, boolean movingSource) {
        Minecraft minecraft = Minecraft.getInstance();
        if (watchdogRecoveryRequested || sessionFinished || streamReadyTick < 0 || minecraft.isPaused()
                || minecraft.options == null
                || minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F
                || minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) <= 0.0F) {
            lastAudioProgressTick = tick;
            return;
        }
        if ((!movingSource && !fixedSourceAvailable(turntable))
                || !ModernTurntablePlaybackTracker.isCurrent(pos, sessionId())) {
            return;
        }
        ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getAudioTimeline(pos);
        boolean matchingTimeline = timeline.audioSessionId().isBlank()
                || sessionId().equals(timeline.audioSessionId());
        long observed = matchingTimeline ? Math.max(timeline.audibleMillis(), timeline.fedMillis()) : -1L;
        if (observed > lastObservedAudioMillis) {
            lastObservedAudioMillis = observed;
            lastAudioProgressTick = tick;
            return;
        }
        long elapsedMillis = PlaybackClock.mediaMillis(pos);
        if (elapsedMillis < 0L) {
            elapsedMillis = startOffsetMillis + Math.max(0L, tick) * 50L;
        }
        if (totalMillis > 0L && elapsedMillis >= totalMillis - WATCHDOG.audioEndGraceMillis()) {
            return;
        }
        int stalledTicks = tick - Math.max(streamReadyTick, lastAudioProgressTick);
        int threshold = observed < 0L ? WATCHDOG.audioStartupStallTicks() : WATCHDOG.audioNoProgressTicks();
        if (stalledTicks < threshold) {
            return;
        }
        watchdogRecoveryRequested = true;
        IOException error = new IOException("audio timeline made no progress for " + (stalledTicks * 50L)
                + "ms (audible=" + timeline.audibleMillis() + "ms, fed=" + timeline.fedMillis() + "ms)");
        LOGGER.warn("现代唱片机音频 watchdog 检测到无进展，准备重建: pos={} session={} observed={}ms stalled={}ms",
                pos, sessionId(), observed, stalledTicks * 50L);
        if (!SyncedStreamRecoveryRegistry.reportFailure(playbackSessionId(), songUrl, error)) {
            LOGGER.warn("现代唱片机音频 watchdog 无法安排恢复，释放僵死会话等待服务器重新同步: pos={} session={}",
                    pos, sessionId());
            stopAndFinish();
        }
    }

    private boolean fixedSourceAvailable(ModernTurntableBlockEntity turntable) {
        return turntable != null ? turntable.isPlaying()
                : sourceId != null && ClientAudioEndpointIndex.sourcePosition(sourceId) != null;
    }

    private boolean hasCurrentDemand() {
        return ClientAudioOutputRegistry.hasAudioDemand(pos, sourceId, sessionId());
    }

    private void retireForRecovery() {
        if (sessionFinished) {
            return;
        }
        sessionFinished = true;
        ModernTurntablePlaybackCoordinator.retireStreamForRecovery(this, recoveryRegistration);
    }

    /**
     * 返回有效的歌词 tick 位置
     */
    private int effectiveLyricTick() {
        int mediaTick = PlaybackClock.mediaTick(pos);
        if (mediaTick >= 0) {
            return mediaTick;
        }
        return fallbackLyricTick();
    }

    @Override
    protected void finishSession() {
        if (!sessionFinished) {
            sessionFinished = true;
            ModernTurntablePlaybackCoordinator.finishSession(pos, sessionId());
        }
    }
}
