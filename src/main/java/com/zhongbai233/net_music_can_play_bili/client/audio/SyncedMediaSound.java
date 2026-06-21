package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.NetMusicAudioStream;
import com.github.tartaricacid.netmusic.init.InitSounds;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.mojang.logging.LogUtils;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;

import java.net.URL;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;

/**
 * 共享的 NetMusic 同步媒体 Sound 基类。
 *
 * <p>
 * 现代化唱片机是主播放线路；MP4 复用这里的基础音频流创建、诊断和 session 字段，
 * 仅保留自己的动态声源、音量、重试和生命周期差异。
 * </p>
 */
public abstract class SyncedMediaSound extends AbstractTickableSoundInstance {
    private static final Logger LOGGER = LogUtils.getLogger();
    protected final URL songUrl;
    protected final int tickTimes;
    protected final LyricRecord lyricRecord;
    protected final String sessionId;
    protected final long startOffsetMillis;
    protected int tick;

    protected SyncedMediaSound(URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId,
            long startOffsetMillis) {
        super(InitSounds.NET_MUSIC.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.songUrl = songUrl;
        this.tickTimes = Math.max(1, timeSecond) * 20;
        this.lyricRecord = lyricRecord;
        this.sessionId = sessionId != null ? sessionId : "";
        this.startOffsetMillis = Math.max(0L, startOffsetMillis);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        onStreamStarting();
        long started = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (isStopped()) {
                    throw stoppedBeforeStreamReady();
                }
                AudioStream stream = new NetMusicAudioStream(songUrl);
                if (isStopped()) {
                    stream.close();
                    throw stoppedBeforeStreamReady();
                }
                LOGGER.debug("{} audio stream ready: cost={}ms host={}", streamDebugName(),
                        System.currentTimeMillis() - started, songUrl.getHost());
                onStreamReady();
                return stream;
            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                BiliPlaybackDiagnostics.markFailed(songUrl, e);
                onStreamFailure(e);
                NetMusic.LOGGER.error("Failed to create {} audio stream for URL: {}", streamDebugName(), songUrl, e);
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor());
    }

    protected int fallbackLyricTick() {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, Math.round(Math.max(0L, startOffsetMillis) / 50.0D)));
    }

    protected void stopAndFinish() {
        finishSession();
        stop();
    }

    protected void onStreamStarting() {
    }

    protected void onStreamReady() {
    }

    protected void onStreamFailure(Exception error) {
        finishSession();
    }

    protected abstract void finishSession();

    protected abstract String streamDebugName();

    private CancellationException stoppedBeforeStreamReady() {
        finishSession();
        return new CancellationException(streamDebugName() + " sound stopped before stream creation");
    }
}
