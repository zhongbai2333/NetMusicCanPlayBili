package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.NetMusicAudioStream;
import com.github.tartaricacid.netmusic.init.InitSounds;
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
    private static final int BLOCK_STATE_GRACE_TICKS = 20;

    private final URL songUrl;
    private final int tickTimes;
    private final BlockPos pos;
    private final LyricRecord lyricRecord;
    private int tick;

    public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord) {
        super(InitSounds.NET_MUSIC.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.songUrl = songUrl;
        this.tickTimes = Math.max(1, timeSecond) * 20;
        this.pos = pos;
        this.lyricRecord = lyricRecord;
        this.x = pos.getX() + 0.5D;
        this.y = pos.getY() + 0.5D;
        this.z = pos.getZ() + 0.5D;
        this.volume = 4.0F;
    }

    @Override
    public void tick() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            stop();
            return;
        }

        tick++;
        if (tick > tickTimes + 50) {
            stop();
            return;
        }

        if (tick > BLOCK_STATE_GRACE_TICKS) {
            var blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof ModernTurntableBlockEntity turntable) || !turntable.isPlaying()) {
                stop();
                return;
            }
        }

        if (lyricRecord != null) {
            lyricRecord.updateCurrentLine(tick);
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new NetMusicAudioStream(songUrl);
            } catch (Exception e) {
                NetMusic.LOGGER.error("Failed to create modern turntable audio stream for URL: {}", songUrl, e);
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor());
    }
}
