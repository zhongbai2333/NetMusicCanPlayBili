package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.NetMusicAudioStream;
import com.github.tartaricacid.netmusic.init.InitSounds;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ModernTurntableSound extends AbstractTickableSoundInstance {
    private static final int BLOCK_STATE_GRACE_TICKS = 40;

    /** 客户端全局音量倍率，由 GUI 音量滑块控制。范围 [0, 2]，默认 1.0 */
    public static volatile float clientVolume = 1.0f;

    private final URL songUrl;
    private final int tickTimes;
    private final BlockPos pos;
    private final LyricRecord lyricRecord;
    private final String sessionId;
    private final int lyricStartTick;
    private int tick;
    private boolean sessionFinished;

    public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord) {
        this(pos, songUrl, timeSecond, lyricRecord, "", 0L);
    }

    public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId) {
        this(pos, songUrl, timeSecond, lyricRecord, sessionId, 0L);
    }

    public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId,
            long startOffsetMillis) {
        super(InitSounds.NET_MUSIC.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.songUrl = songUrl;
        this.tickTimes = Math.max(1, timeSecond) * 20;
        this.pos = pos;
        this.lyricRecord = lyricRecord;
        this.sessionId = sessionId != null ? sessionId : "";
        this.lyricStartTick = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, Math.round(Math.max(0L, startOffsetMillis) / 50.0D)));
        this.x = pos.getX() + 0.5D;
        this.y = pos.getY() + 0.5D;
        this.z = pos.getZ() + 0.5D;
        this.volume = Math.max(0.01F, 4.0F * clientVolume);
    }

    @Override
    public void tick() {
        this.volume = Math.max(0.01F, 4.0F * clientVolume);
        var level = Minecraft.getInstance().level;
        if (level == null) {
            finishSession();
            stop();
            return;
        }

        tick++;
        ModernTurntableBlockEntity turntable = level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity modern
                ? modern
                : null;
        if (!ModernTurntablePlaybackTracker.isCurrent(pos, sessionId)) {
            finishSession();
            stop();
            return;
        }
        if (tick > tickTimes + 50) {
            finishSession();
            stop();
            return;
        }

        if (tick > BLOCK_STATE_GRACE_TICKS) {
            if (turntable == null || !turntable.isPlaying()) {
                finishSession();
                stop();
                return;
            }
        }
        // 唱片被取出时立即停止，不等 grace 过期
        if (turntable != null && !turntable.hasDisc() && tick > 0) {
            finishSession();
            stop();
            return;
        }

        if (lyricRecord != null) {
            lyricRecord.updateCurrentLine(lyricTick());
            if (turntable != null && turntable.isPlaying()) {
                turntable.setClientLyricRecord(lyricRecord, sessionId);
            }
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
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        ModernTurntablePlaybackTracker.markStreamStarted(pos, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new NetMusicAudioStream(songUrl);
            } catch (Exception e) {
                BiliPlaybackDiagnostics.markFailed(songUrl, e);
                finishSession();
                NetMusic.LOGGER.error("Failed to create modern turntable audio stream for URL: {}", songUrl, e);
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor());
    }

    private int lyricTick() {
        long value = (long) lyricStartTick + tick;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private void finishSession() {
        if (!sessionFinished) {
            sessionFinished = true;
            ModernTurntablePlaybackTracker.finish(pos, sessionId);
            var level = Minecraft.getInstance().level;
            if (level != null && level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable) {
                turntable.clearClientLyricRecord(sessionId);
            }
        }
    }
}
