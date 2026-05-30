package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricParser;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.MusicPlayManager;
import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.tartaricacid.netmusic.network.client.MusicToClientMessageClient;
import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliAudioResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSubtitleLyricService;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntableSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URL;

/**
 * 为 B站 音频注入由 CC 字幕生成的歌词。
 */
@Mixin(MusicToClientMessageClient.class)
public abstract class MusicToClientMessageClientMixin {
    @Unique
    private static final Pattern NET_MUSIC_CAN_PLAY_BILI$NET_EASE_MP3_URL = Pattern.compile("^.*?\\?id=(\\d+)\\.mp3$");

    @Inject(method = "onHandle", at = @At("HEAD"), cancellable = true)
    private static void net_music_can_play_bili$injectBiliLyrics(MusicToClientMessage message, CallbackInfo ci) {
        boolean modernTurntable = net_music_can_play_bili$isModernTurntable(message);
        boolean biliSelection = net_music_can_play_bili$hasStoredBiliSelection(message);

        // 现代化唱片机需要我们的 OpenAL 管线；普通唱片机只在 B站选曲时恢复客户端解析和请求头兼容层。
        if (!modernTurntable && !biliSelection) {
            return;
        }

        LyricRecord lyricRecord = null;
        if (GeneralConfig.ENABLE_PLAYER_LYRICS.get()) {
            lyricRecord = net_music_can_play_bili$tryBuildNetEaseLyric(message);
            if (lyricRecord == null) {
                lyricRecord = BiliSubtitleLyricService.tryBuildLyricRecord(message.rawUrl(), message.songName());
            }
        }

        LyricRecord finalLyricRecord = lyricRecord;
        String playUrl = net_music_can_play_bili$resolveBiliUrlOnClient(message, modernTurntable);

        if (modernTurntable) {
            HttpAudioStreamHandler.allowUrl(playUrl, message.pos());
        }

        BiliPlaybackDiagnostics.beginPlayback(message.songName(), message.rawUrl(), playUrl);
        MusicPlayManager.play(playUrl, message.songName(),
                url -> net_music_can_play_bili$createSound(message, url, finalLyricRecord));
        ci.cancel();
    }

    @Unique
    private static boolean net_music_can_play_bili$isModernTurntable(MusicToClientMessage message) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(message.pos()) instanceof ModernTurntableBlockEntity;
    }

    @Unique
    private static boolean net_music_can_play_bili$hasStoredBiliSelection(MusicToClientMessage message) {
        return BiliApiClient.isStoredVideoSelection(message.rawUrl())
                || BiliApiClient.isStoredVideoSelection(message.url());
    }

    @Unique
    private static SoundInstance net_music_can_play_bili$createSound(MusicToClientMessage message, URL url,
            LyricRecord lyricRecord) {
        var level = Minecraft.getInstance().level;
        if (level != null && level.getBlockEntity(message.pos()) instanceof ModernTurntableBlockEntity) {
            return new ModernTurntableSound(message.pos(), url, message.timeSecond(), lyricRecord);
        }
        return new NetMusicSound(message.pos(), url, message.timeSecond(), lyricRecord);
    }

    @Unique
    private static String net_music_can_play_bili$resolveBiliUrlOnClient(MusicToClientMessage message,
            boolean allowDolby) {
        String storedSelection = null;
        if (BiliApiClient.isStoredVideoSelection(message.rawUrl())) {
            storedSelection = message.rawUrl();
        } else if (BiliApiClient.isStoredVideoSelection(message.url())) {
            storedSelection = message.url();
        }

        if (storedSelection == null) {
            return message.url();
        }

        try {
            String resolvedUrl = BiliAudioResolver.resolvePlayableUrl(storedSelection, allowDolby);
            NetMusic.LOGGER.info("B站客户端本地解析播放直链成功: {}", message.songName());
            return resolvedUrl;
        } catch (Exception e) {
            NetMusic.LOGGER.error("B站客户端本地解析播放直链失败: {}", storedSelection, e);
            return message.url();
        }
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
