package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.MusicPlayManager;
import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.tartaricacid.netmusic.network.client.MusicToClientMessageClient;
import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntableSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URL;

/**
 * 为 B站 音频注入由 CC 字幕生成的歌词。
 */
@Mixin(MusicToClientMessageClient.class)
public abstract class MusicToClientMessageClientMixin {
    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "onHandle", at = @At("HEAD"), cancellable = true)
    private static void net_music_can_play_bili$injectBiliLyrics(MusicToClientMessage message, CallbackInfo ci) {
        PlaybackSync.Metadata sync = PlaybackSync.parse(message.url());
        boolean modernTurntable = sync.hasSession() || net_music_can_play_bili$isModernTurntable(message);
        boolean biliSelection = ClientMediaPreparer.hasStoredBiliSelection(message.rawUrl(), message.url());

        // 现代化唱片机需要我们的 OpenAL 管线；普通唱片机只在 B站选曲时恢复客户端解析和请求头兼容层。
        if (!modernTurntable && !biliSelection) {
            return;
        }

        if (modernTurntable) {
            if (sync.hasSession()
                    && !ModernTurntablePlaybackTracker.tryStart(message.pos(), sync.sessionId(),
                            message.timeSecond())) {
                ModernTurntableVideoClient.syncFromPlayback(message.rawUrl(), message.pos(), sync);
                ci.cancel();
                return;
            }
            if (Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER) <= 0f) {
                ci.cancel();
                return;
            }

            boolean loadLyrics = GeneralConfig.ENABLE_PLAYER_LYRICS.get();
            ClientMediaPreparer.PreparedMedia prepared = ClientMediaPreparer.prepareAudioOnly(message.rawUrl(),
                    message.url(), message.songName(), true);
            String playUrl = prepared.playUrl();
            long elapsedMillis = Math.max(0L, sync.elapsedMillis());
            long totalMillis = Math.max(0L, sync.totalMillis());
            if (totalMillis > 0L) {
                elapsedMillis = Math.min(totalMillis, elapsedMillis);
            }
            String syncedPlayUrl = sync.hasSession()
                    ? PlaybackSync.withSync(playUrl, sync.sessionId(), elapsedMillis, totalMillis)
                    : playUrl;
            HttpAudioStreamHandler.allowUrl(syncedPlayUrl, message.pos());
            ModernTurntableVideoClient.syncFromPlayback(message.rawUrl(), message.pos(), sync);
            BiliPlaybackDiagnostics.beginPlayback(message.songName(), message.rawUrl(), syncedPlayUrl);
            LOGGER.debug(
                    "现代唱片机客户端接管播放: song='{}' session={} pos={} elapsed={}ms total={}ms biliSelection={} lyricsAsync={} audioHost={} videoSync=scheduled",
                    message.songName(), sync.sessionId(), message.pos(), elapsedMillis, totalMillis, biliSelection,
                    loadLyrics, ClientMediaPreparer.hostOf(syncedPlayUrl));
            long startOffsetMillis = elapsedMillis;
            if (loadLyrics) {
                net_music_can_play_bili$loadModernLyricsAsync(message, sync.sessionId());
            }
            MusicPlayManager.play(syncedPlayUrl, message.songName(),
                    url -> net_music_can_play_bili$createSound(message, url, null, sync.sessionId(),
                            startOffsetMillis, true));
            ci.cancel();
            return;
        }

        ClientMediaPreparer.PreparedMedia prepared = ClientMediaPreparer.prepareAudioOnly(message.rawUrl(),
                message.url(),
                message.songName(), modernTurntable);
        String playUrl = prepared.playUrl();

        BiliPlaybackDiagnostics.beginPlayback(message.songName(), message.rawUrl(), playUrl);
        LOGGER.debug("B站/NetMusic 客户端接管播放: song='{}' modern={} biliSelection={} lyric=not-blocking audioHost={}",
                message.songName(), modernTurntable, biliSelection,
                ClientMediaPreparer.hostOf(playUrl));
        MusicPlayManager.play(playUrl, message.songName(),
                url -> net_music_can_play_bili$createSound(message, url, null, sync.sessionId(),
                        sync.elapsedMillis(), modernTurntable));
        ci.cancel();
    }

    @Unique
    private static boolean net_music_can_play_bili$isModernTurntable(MusicToClientMessage message) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(message.pos()) instanceof ModernTurntableBlockEntity;
    }

    @Unique
    private static void net_music_can_play_bili$loadModernLyricsAsync(MusicToClientMessage message, String sessionId) {
        ClientMediaPreparer.buildLyricAsync(message.rawUrl(), message.songName()).whenComplete((record, error) -> {
            if (error != null || record == null) {
                if (error != null) {
                    LOGGER.debug("现代唱片机歌词后台解析失败: song='{}' session={} reason={}", message.songName(),
                            sessionId, error.toString());
                }
                return;
            }
            Minecraft.getInstance().execute(() -> {
                var level = Minecraft.getInstance().level;
                if (level == null) {
                    return;
                }
                BlockEntity blockEntity = level.getBlockEntity(message.pos());
                if (blockEntity instanceof ModernTurntableBlockEntity turntable && turntable.isPlaying()) {
                    turntable.setClientLyricRecord(record, sessionId);
                }
            });
        });
    }

    @Unique
    private static SoundInstance net_music_can_play_bili$createSound(MusicToClientMessage message, URL url,
            LyricRecord lyricRecord, String sessionId, long elapsedMillis, boolean modernTurntable) {
        var level = Minecraft.getInstance().level;
        if (modernTurntable
                || (level != null && level.getBlockEntity(message.pos()) instanceof ModernTurntableBlockEntity)) {
            return new ModernTurntableSound(message.pos(), url, message.timeSecond(), lyricRecord, sessionId,
                    elapsedMillis, message.rawUrl(), message.songName(), Math.max(0L, message.timeSecond()) * 1000L);
        }
        return new NetMusicSound(message.pos(), url, message.timeSecond(), lyricRecord);
    }

}
