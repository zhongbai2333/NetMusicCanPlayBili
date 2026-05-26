package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricParser;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.MusicPlayManager;
import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.tartaricacid.netmusic.network.client.MusicToClientMessageClient;
import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSubtitleLyricService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为 B站 音频注入由 CC 字幕生成的歌词。
 */
@Mixin(MusicToClientMessageClient.class)
public abstract class MusicToClientMessageClientMixin {
    @Unique
    private static final Pattern NET_MUSIC_CAN_PLAY_BILI$NET_EASE_MP3_URL = Pattern.compile("^.*?\\?id=(\\d+)\\.mp3$");

    @Inject(method = "onHandle", at = @At("HEAD"), cancellable = true)
    private static void net_music_can_play_bili$injectBiliLyrics(MusicToClientMessage message, CallbackInfo ci) {
        LyricRecord lyricRecord = null;
        if (GeneralConfig.ENABLE_PLAYER_LYRICS.get()) {
            lyricRecord = net_music_can_play_bili$tryBuildNetEaseLyric(message);
            if (lyricRecord == null) {
                lyricRecord = BiliSubtitleLyricService.tryBuildLyricRecord(message.rawUrl(), message.songName());
            }
        }

        LyricRecord finalLyricRecord = lyricRecord;
        MusicPlayManager.play(message.url(), message.songName(),
                url -> new NetMusicSound(message.pos(), url, message.timeSecond(), finalLyricRecord));
        ci.cancel();
    }

    @Unique
    private static LyricRecord net_music_can_play_bili$tryBuildNetEaseLyric(MusicToClientMessage message) {
        if (!message.rawUrl().startsWith("https://music.163.com/")) {
            return null;
        }

        Matcher matcher = NET_MUSIC_CAN_PLAY_BILI$NET_EASE_MP3_URL.matcher(message.rawUrl());
        if (!matcher.find()) {
            return null;
        }

        try {
            long songId = Long.parseLong(matcher.group(1));
            String lyricJson = NetMusic.NET_EASE_WEB_API.lyric(songId);
            return LyricParser.parseLyric(lyricJson, message.songName());
        } catch (Exception e) {
            NetMusic.LOGGER.error(e);
            return null;
        }
    }
}