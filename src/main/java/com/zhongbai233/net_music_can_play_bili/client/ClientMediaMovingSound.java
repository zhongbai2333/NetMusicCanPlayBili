package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaSound;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedStreamRecoveryRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaAudioRouting;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackLifecycle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundLifecyclePolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaStreamRecovery;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.net.URL;
import java.util.UUID;

/**
 * Shared synchronized client media sound used by MP4, Pad, and future media
 * devices.
 */
public class ClientMediaMovingSound extends SyncedMediaSound implements ClientMediaSoundHandle {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final UUID sourceId;
    private final boolean headphoneRouted;
    private final long totalMillis;
    private final ClientMediaSoundLifecyclePolicy lifecyclePolicy;
    private final String debugName;
    private float mediaVolume;
    private boolean finished;
    private final SyncedStreamRecoveryRegistry.Registration recoveryRegistration;

    public ClientMediaMovingSound(UUID sourceId, URL songUrl, int timeSecond, LyricRecord lyricRecord,
            String sessionId, long startOffsetMillis, float volume, boolean headphoneRouted,
            ClientMediaSoundLifecyclePolicy lifecyclePolicy, String debugName) {
        super(songUrl, timeSecond, lyricRecord, sessionId, startOffsetMillis);
        this.sourceId = sourceId;
        this.headphoneRouted = headphoneRouted;
        this.totalMillis = Math.max(0L, timeSecond) * 1000L;
        this.lifecyclePolicy = lifecyclePolicy != null ? lifecyclePolicy : MP4MediaSoundLifecyclePolicy.INSTANCE;
        this.debugName = debugName != null && !debugName.isBlank() ? debugName : "ClientMedia";
        this.mediaVolume = Math.max(0.0F, Math.min(1.0F, volume));
        updatePositionAndAttenuation();
        LOGGER.trace("{} 声音实例创建: source={} session={} headphoneRouted={} volume={} gain={} attenuation={}",
                this.debugName, sourceId, this.sessionId, headphoneRouted, this.mediaVolume, volume, attenuation);
        this.lifecyclePolicy.registerSound(sourceId, this.sessionId, this);
        recoveryRegistration = SyncedStreamRecoveryRegistry.register(this.sessionId,
                request -> this.lifecyclePolicy.recoverAfterStreamFailure(sourceId, this.sessionId, request.error()));
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public boolean headphoneRouted() {
        return headphoneRouted;
    }

    @Override
    public boolean stopped() {
        return isStopped();
    }

    @Override
    public void discardWithoutFinishing() {
        finished = true;
        SyncedStreamRecoveryRegistry.unregister(recoveryRegistration);
        stop();
    }

    @Override
    public void setMediaVolume(float volume) {
        this.mediaVolume = Math.max(0.0F, Math.min(1.0F, volume));
        updatePositionAndAttenuation();
    }

    @Override
    public void tick() {
        if (ClientMediaStreamRecovery.isPending(sourceId, sessionId)) {
            stop();
            return;
        }
        if (!ClientMediaPlayback.isCurrent(sourceId, sessionId)) {
            stopAndFinish();
            return;
        }
        if (!ClientMediaAudioRouting.canHear(sourceId, headphoneRouted)) {
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
            LOGGER.trace(
                    "{} 声音实例首 tick: source={} session={} headphoneRouted={} volume={} attenuation={} pos=({}, {}, {})",
                    debugName, sourceId, sessionId, headphoneRouted, volume, attenuation, x, y, z);
        }
        long elapsedMillis = ClientMediaPlayback.elapsedMillis(sourceId, sessionId, startOffsetMillis + tick * 50L);
        int lyricTick = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, Math.round(Math.max(0L, elapsedMillis) / 50.0D)));
        if (totalMillis > 0L && lyricTick > tickTimes + 50) {
            lifecyclePolicy.onCompleted(sourceId, sessionId);
            stopAndFinish();
            return;
        }
        updatePositionAndAttenuation();
        if (lyricRecord != null) {
            lyricRecord.updateCurrentLine(lyricTick);
            ClientMediaPlaybackLifecycle.updateLyric(sourceId, sessionId, lyricRecord, lyricTick);
        }
    }

    @Override
    protected void onStreamFailure(Exception error) {
        boolean retryScheduled = lifecyclePolicy.recoverAfterStreamFailure(sourceId, sessionId, error);
        if (!retryScheduled) {
            finishSession();
        }
    }

    @Override
    protected void onStreamStarting() {
        LOGGER.trace("{} 声音流开始创建: source={} session={} headphoneRouted={} urlHost={}",
                debugName, sourceId, sessionId, headphoneRouted, songUrl.getHost());
    }

    @Override
    protected void onStreamReady() {
        ClientMediaPlayback.markAudioStarted(sourceId, sessionId, startOffsetMillis, totalMillis);
        LOGGER.trace("{} 声音流已就绪: source={} session={} headphoneRouted={} offset={}ms urlHost={}",
                debugName, sourceId, sessionId, headphoneRouted, startOffsetMillis, songUrl.getHost());
    }

    @Override
    protected String streamDebugName() {
        return debugName;
    }

    private void updatePositionAndAttenuation() {
        Vec3 pos = ClientMediaAudioRouting.audiblePosition(sourceId, headphoneRouted);
        x = pos.x;
        y = pos.y;
        z = pos.z;
        attenuation = (headphoneRouted || ClientMediaPlayback.isLocalPlayerSource(sourceId))
                ? Attenuation.NONE
                : Attenuation.LINEAR;
        volume = ClientMediaPlayback.perceivedGain(mediaVolume);
    }

    @Override
    protected void finishSession() {
        if (!finished) {
            finished = true;
            SyncedStreamRecoveryRegistry.unregister(recoveryRegistration);
            lifecyclePolicy.finish(sourceId, sessionId);
        }
    }
}