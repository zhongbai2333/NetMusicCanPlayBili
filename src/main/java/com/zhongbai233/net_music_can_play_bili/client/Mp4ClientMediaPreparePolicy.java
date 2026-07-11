package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaAudioRouting;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPreparePolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.slf4j.Logger;

import java.net.URL;
import java.util.UUID;

/**
 * MP4/legacy policy for shared asynchronous client media prepare and launch.
 */
final class Mp4ClientMediaPreparePolicy implements ClientMediaPreparePolicy {
    static final Mp4ClientMediaPreparePolicy INSTANCE = new Mp4ClientMediaPreparePolicy();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean PAD_VIDEO_DEBUG_LOG = Boolean.getBoolean("ncpb.pad.video.debug_log");
    private static final long PREPARE_TIMEOUT_SECONDS = Math.max(3L,
            Long.getLong("ncpb.mp4.client_prepare_timeout_seconds", 12L));

    private Mp4ClientMediaPreparePolicy() {
    }

    @Override
    public long prepareTimeoutSeconds() {
        return PREPARE_TIMEOUT_SECONDS;
    }

    @Override
    public boolean canHear(UUID sourceId, boolean headphoneRouted) {
        return ClientMediaAudioRouting.canHear(sourceId, headphoneRouted);
    }

    @Override
    public void stop(UUID sourceId) {
        MP4ClientMediaSessions.stop(sourceId);
    }

    @Override
    public boolean shouldLoadLyrics(ClientMediaSyncPayload payload, UUID sourceId) {
        UUID localPlayerId = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getUUID() : null;
        return localPlayerId != null && localPlayerId.equals(payload.ownerId())
                && GeneralConfig.ENABLE_PLAYER_LYRICS.get();
    }

    @Override
    public long startOffsetMillis(ClientMediaSyncPayload payload,
            ClientMediaPlaybackRegistry.ActivePlayback current) {
        return isWhitelistPreviewSession(payload.sessionId())
                ? Math.max(0L, payload.elapsedMillis())
                : ClientMediaPreparePolicy.super.startOffsetMillis(payload, current);
    }

    @Override
    public void loadLyricsAsync(UUID sourceId, String sessionId, String rawUrl, String songName) {
        ClientMediaPreparer.buildLyricAsync(rawUrl, songName).whenComplete((record, error) -> {
            if (error != null) {
                LOGGER.debug("MP4 客户端歌词后台解析失败: source={} session={} song='{}' reason={}", sourceId,
                        sessionId, songName, error.toString());
                return;
            }
            if (record == null) {
                return;
            }
            Minecraft.getInstance().execute(() -> ClientMediaPlaybackRegistry.computeIfPresent(sourceId,
                    (ignored, active) -> sessionId.equals(active.sessionId()) ? active.withLyrics(record, "", "")
                            : active));
        });
    }

    @Override
    public SoundInstance createSound(UUID sourceId, ClientMediaSyncPayload payload, URL url, LyricRecord lyricRecord,
            long startOffsetMillis) {
        return new ClientMediaMovingSound(sourceId, url, payload.durationSeconds(), lyricRecord, payload.sessionId(),
                startOffsetMillis, payload.volumePerMille() / 1000.0F, payload.headphoneRouted(),
                MP4MediaSoundLifecyclePolicy.INSTANCE, "MP4");
    }

    @Override
    public void onPrepareDuplicate(ClientMediaSyncPayload payload, UUID sourceId) {
        if (PAD_VIDEO_DEBUG_LOG && payload.sessionId() != null && payload.sessionId().contains("-pad-")) {
            LOGGER.info("Pad playback prepare skipped: duplicate source={} session={} headphoneRouted={}",
                    sourceId, payload.sessionId(), payload.headphoneRouted());
        }
    }

    @Override
    public void onPrepareScheduled(ClientMediaSyncPayload payload, UUID sourceId) {
        if (PAD_VIDEO_DEBUG_LOG && payload.sessionId() != null && payload.sessionId().contains("-pad-")) {
            LOGGER.info(
                    "Pad playback prepare scheduled: source={} session={} raw='{}' playUrlHost={} elapsed={}ms duration={}s",
                    sourceId, payload.sessionId(), payload.rawUrl(), ClientMediaPreparer.hostOf(payload.playUrl()),
                    payload.elapsedMillis(), payload.durationSeconds());
        }
    }

    @Override
    public void onPrepareStarted(ClientMediaSyncPayload payload, UUID sourceId, boolean loadLyrics) {
        LOGGER.trace("MP4 客户端准备播放开始: owner={} source={} session={} song='{}' host={} lyrics={}",
                payload.ownerId(), sourceId, payload.sessionId(), payload.songName(),
                ClientMediaPreparer.hostOf(payload.playUrl()), loadLyrics);
    }

    @Override
    public void onPrepareCompleted(ClientMediaSyncPayload payload, UUID sourceId,
            ClientMediaPreparer.PreparedMedia prepared, long costMillis) {
        LOGGER.debug("MP4 客户端准备播放完成: owner={} source={} session={} cost={}ms host={}",
                payload.ownerId(), sourceId, payload.sessionId(), costMillis,
                prepared != null ? ClientMediaPreparer.hostOf(prepared.playUrl()) : "unknown");
    }

    @Override
    public void onPrepareFailed(ClientMediaSyncPayload payload, UUID sourceId, Throwable error) {
        LOGGER.warn("MP4 客户端异步准备播放失败，使用服务端直链继续: owner={} session={} song='{}' reason={}",
                payload.ownerId(), payload.sessionId(), payload.songName(), error.toString());
    }

    @Override
    public void onPrepareTimeout(ClientMediaSyncPayload payload, UUID sourceId) {
        LOGGER.warn("MP4 客户端准备播放超时或无结果，使用服务端直链继续: owner={} source={} session={} song='{}' timeout={}s host={}",
                payload.ownerId(), sourceId, payload.sessionId(), payload.songName(), PREPARE_TIMEOUT_SECONDS,
                ClientMediaPreparer.hostOf(payload.playUrl()));
    }

    @Override
    public void onPrepareCancelledCannotHear(ClientMediaSyncPayload payload, UUID sourceId) {
        LOGGER.trace("MP4 客户端准备完成后取消非当前耳机绑定播放: source={} session={} headphoneRouted={} equipped={}",
                sourceId, payload.sessionId(), payload.headphoneRouted(), HeadphoneClientState.equipped());
    }

    @Override
    public void onLaunch(ClientMediaSyncPayload payload, UUID sourceId, long startOffsetMillis, String playUrl) {
        LOGGER.trace("MP4 客户端开始播放: owner={} source={} type={} song='{}' session={} offset={}ms host={}",
                payload.ownerId(), sourceId, payload.sourceType(), payload.songName(), payload.sessionId(),
                startOffsetMillis, ClientMediaPreparer.hostOf(playUrl));
    }

    private static boolean isWhitelistPreviewSession(String sessionId) {
        return sessionId != null && sessionId.contains("-whitelist-preview-");
    }
}