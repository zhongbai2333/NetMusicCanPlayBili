package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.mojang.logging.LogUtils;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.MusicPlayManager;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.slf4j.Logger;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * 同步媒体客户端启动工具。
 *
 * <p>
 * 现代化唱片机和 MP4 都需要做同一串动作：刷新直链、保留 session 进度、注册 HTTP 处理上下文、
 * 写诊断并交给 NetMusic 播放管理器。集中在这里可避免两条线路继续重复造轮子。
 * </p>
 */
public final class SyncedMediaPlaybackLauncher {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SyncedMediaPlaybackLauncher() {
    }

    public static LaunchResult prepare(String rawUrl, String playUrl, String songName, boolean allowDolby,
            boolean enableLyrics, String sessionId, long elapsedMillis, long totalMillis, BlockPos pos,
            UUID ownerId) {
        if (!com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection.allowMediaStart()) {
            return null;
        }
        ClientMediaPreparer.PreparedMedia prepared = ClientMediaPreparer.prepareAudioOnly(rawUrl, playUrl, songName,
                allowDolby);
        return fromPrepared(rawUrl, songName, prepared, playUrl, sessionId, elapsedMillis, totalMillis, pos, ownerId);
    }

    public static LaunchResult fromPrepared(String rawUrl, String songName, ClientMediaPreparer.PreparedMedia prepared,
            String fallbackPlayUrl, String sessionId, long elapsedMillis, long totalMillis, BlockPos pos,
            UUID ownerId) {
        return fromPrepared(rawUrl, songName, prepared, fallbackPlayUrl, sessionId, elapsedMillis, totalMillis, pos,
                ownerId, null);
    }

    public static LaunchResult fromPrepared(String rawUrl, String songName, ClientMediaPreparer.PreparedMedia prepared,
            String fallbackPlayUrl, String sessionId, long elapsedMillis, long totalMillis, BlockPos pos,
            UUID ownerId, PlaybackSync.MinecartAnchor minecartAnchor) {
        if (!com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection.allowMediaStart()) {
            return null;
        }
        String playUrl = prepared != null ? prepared.playUrl() : fallbackPlayUrl;
        LyricRecord lyricRecord = prepared != null ? prepared.lyricRecord() : null;
        PlaybackRequest playbackRequest = PlaybackRequest.now(playUrl, pos, sessionId,
                Math.max(0L, elapsedMillis), Math.max(0L, totalMillis), ownerId,
                minecartAnchor != null ? minecartAnchor.entityUuid() : null);
        HttpAudioStreamHandler.RegisteredRequest request = HttpAudioStreamHandler.registerRequest(playbackRequest);
        BiliPlaybackDiagnostics.beginPlayback(songName, rawUrl, request.url());
        return new LaunchResult(request.url(), lyricRecord, request.requestToken());
    }

    public static boolean play(LaunchResult launch, String songName,
            BiFunction<URL, LyricRecord, SoundInstance> soundFactory) {
        return play(launch, songName, soundFactory, true);
    }

    public static boolean play(LaunchResult launch, String songName,
            BiFunction<URL, LyricRecord, SoundInstance> soundFactory, boolean announceImmediately) {
        if (!com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection.allowMediaStart()
                || launch == null || launch.playUrl() == null || launch.playUrl().isBlank()) {
            return false;
        }
        LyricRecord lyricRecord = launch.lyricRecord();
        if (announceImmediately) {
            MusicPlayManager.play(launch.playUrl(), songName, url -> soundFactory.apply(url, lyricRecord));
            return true;
        }
        var finalUrl = MusicPlayManager.getFinalUrl(launch.playUrl());
        if (finalUrl.isEmpty()) {
            return false;
        }
        try {
            URL url = new URI(finalUrl.get()).toURL();
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.isSameThread()) {
                return submitSound(minecraft, soundFactory.apply(url, lyricRecord), songName);
            }
            minecraft.execute(() -> submitSound(minecraft, soundFactory.apply(url, lyricRecord), songName));
        } catch (MalformedURLException | URISyntaxException error) {
            return false;
        }
        return true;
    }

    private static boolean submitSound(net.minecraft.client.Minecraft minecraft, SoundInstance sound,
            String songName) {
        if (sound == null) {
            LOGGER.warn("同步媒体声音工厂返回空实例: song='{}'", songName);
            return false;
        }
        SoundEngine.PlayResult result = minecraft.getSoundManager().play(sound);
        if (result == SoundEngine.PlayResult.NOT_STARTED) {
            LOGGER.warn("同步媒体声音引擎拒绝启动: song='{}' sound={} volume={} canStartSilent={}",
                    songName, sound.getIdentifier(), sound.getVolume(), sound.canStartSilent());
            return false;
        }
        LOGGER.debug("同步媒体声音引擎已接受: song='{}' result={} volume={} canStartSilent={}",
                songName, result, sound.getVolume(), sound.canStartSilent());
        return true;
    }

    public record LaunchResult(String playUrl, LyricRecord lyricRecord, Optional<MediaRequestToken> requestToken) {
        public LaunchResult {
            requestToken = requestToken == null ? Optional.empty() : requestToken;
        }

        public LaunchResult(String playUrl, LyricRecord lyricRecord) {
            this(playUrl, lyricRecord, Optional.empty());
        }
    }
}
