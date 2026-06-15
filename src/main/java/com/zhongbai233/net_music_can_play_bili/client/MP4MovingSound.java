package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaSound;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedStreamRecoveryRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.net.URL;
import java.util.UUID;

public class MP4MovingSound extends SyncedMediaSound {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final UUID sourceId;
    private final boolean headphoneRouted;
    private float mp4Volume;
    private boolean finished;

    public MP4MovingSound(UUID sourceId, URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId,
            long startOffsetMillis, float volume, boolean headphoneRouted) {
        super(songUrl, timeSecond, lyricRecord, sessionId, startOffsetMillis);
        this.sourceId = sourceId;
        this.headphoneRouted = headphoneRouted;
        this.mp4Volume = Math.max(0.0F, Math.min(1.0F, volume));
        updatePositionAndAttenuation();
        LOGGER.trace("MP4 声音实例创建: source={} session={} headphoneRouted={} volume={} gain={} attenuation={}",
            sourceId, this.sessionId, headphoneRouted, this.mp4Volume, volume, attenuation);
        MP4ClientPlayback.registerSound(sourceId, this.sessionId, this);
        SyncedStreamRecoveryRegistry.register(this.sessionId,
            request -> MP4ClientPlayback.retryAfterStreamFailure(sourceId, this.sessionId, request.error()));
    }

    String sessionId() {
        return sessionId;
    }

    boolean headphoneRouted() {
        return headphoneRouted;
    }

    boolean stopped() {
        return isStopped();
    }

    void discardWithoutFinishing() {
        finished = true;
        SyncedStreamRecoveryRegistry.unregister(sessionId);
        stop();
    }

    public void setMp4Volume(float volume) {
        this.mp4Volume = Math.max(0.0F, Math.min(1.0F, volume));
        updatePositionAndAttenuation();
    }

    @Override
    public void tick() {
        if (MP4ClientPlayback.hasPendingStreamRetry(sourceId, sessionId)) {
            stop();
            return;
        }
        if (!MP4ClientPlayback.isCurrent(sourceId, sessionId)) {
            stopAndFinish();
            return;
        }
        if (!MP4ClientPlayback.canHear(sourceId, headphoneRouted)) {
            stopAndFinish();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            stopAndFinish();
            return;
        }
        tick++;
        if (tick == 1) {
            LOGGER.trace("MP4 声音实例首 tick: source={} session={} headphoneRouted={} volume={} attenuation={} pos=({}, {}, {})",
                sourceId, sessionId, headphoneRouted, volume, attenuation, x, y, z);
        }
        long elapsedMillis = MP4ClientPlayback.elapsedMillis(sourceId, sessionId, startOffsetMillis + tick * 50L);
        int lyricTick = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, Math.round(Math.max(0L, elapsedMillis) / 50.0D)));
        if (lyricTick > tickTimes + 50) {
            MP4ClientPlayback.onSoundCompleted(sourceId, sessionId);
            stopAndFinish();
            return;
        }
        updatePositionAndAttenuation();
        if (lyricRecord != null) {
            lyricRecord.updateCurrentLine(lyricTick);
            MP4ClientPlayback.updateLyric(sourceId, sessionId, lyricRecord, lyricTick);
        }
    }

    @Override
    protected void onStreamFailure(Exception error) {
        boolean retryScheduled = MP4ClientPlayback.retryAfterStreamFailure(sourceId, sessionId, error);
        if (!retryScheduled) {
            finishSession();
        }
    }

    @Override
    protected void onStreamStarting() {
        LOGGER.trace("MP4 声音流开始创建: source={} session={} headphoneRouted={} urlHost={}",
            sourceId, sessionId, headphoneRouted, songUrl.getHost());
    }

    @Override
    protected String streamDebugName() {
        return "MP4";
    }

    private void updatePositionAndAttenuation() {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 pos = headphoneRouted ? MP4ClientPlayback.localHeadPosition() : MP4ClientPlayback.sourcePosition(sourceId);
        if (pos == null) {
            pos = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
        }
        x = pos.x;
        y = pos.y;
        z = pos.z;
        attenuation = (headphoneRouted || MP4ClientPlayback.isLocalPlayerSource(sourceId))
            ? Attenuation.NONE
            : Attenuation.LINEAR;
        float gain = MP4ClientPlayback.perceivedGain(mp4Volume);
        volume = gain;
    }

    @Override
    protected void finishSession() {
        if (!finished) {
            finished = true;
            SyncedStreamRecoveryRegistry.unregister(sessionId);
            MP4ClientPlayback.finish(sourceId, sessionId);
        }
    }
}