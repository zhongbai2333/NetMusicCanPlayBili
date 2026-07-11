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
 * Pad implementation of the shared asynchronous client media prepare and launch
 * flow.
 */
final class PadClientMediaPreparePolicy implements ClientMediaPreparePolicy {
    static final PadClientMediaPreparePolicy INSTANCE = new PadClientMediaPreparePolicy();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean PAD_VIDEO_DEBUG_LOG = Boolean.getBoolean("ncpb.pad.video.debug_log");
    private static final long PREPARE_TIMEOUT_SECONDS = Math.max(3L,
            Long.getLong("ncpb.pad.client_prepare_timeout_seconds", 12L));

    private PadClientMediaPreparePolicy() {
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
        PadClientMediaSessions.stop(sourceId);
    }

    @Override
    public boolean shouldLoadLyrics(ClientMediaSyncPayload payload, UUID sourceId) {
        UUID localPlayerId = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getUUID() : null;
        return localPlayerId != null && localPlayerId.equals(payload.ownerId())
                && GeneralConfig.ENABLE_PLAYER_LYRICS.get();
    }

    @Override
    public void loadLyricsAsync(UUID sourceId, String sessionId, String rawUrl, String songName) {
        ClientMediaPreparer.buildLyricAsync(rawUrl, songName).whenComplete((record, error) -> {
            if (error != null) {
                LOGGER.debug("Pad 客户端歌词后台解析失败: source={} session={} song='{}' reason={}", sourceId,
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
                PadMediaSoundLifecyclePolicy.INSTANCE, "Pad");
    }

    @Override
    public void onPrepareDuplicate(ClientMediaSyncPayload payload, UUID sourceId) {
        if (PAD_VIDEO_DEBUG_LOG) {
            LOGGER.info("Pad playback prepare skipped: duplicate source={} session={} headphoneRouted={}",
                    sourceId, payload.sessionId(), payload.headphoneRouted());
        }
    }

    @Override
    public void onPrepareScheduled(ClientMediaSyncPayload payload, UUID sourceId) {
        if (PAD_VIDEO_DEBUG_LOG) {
            LOGGER.info(
                    "Pad playback prepare scheduled: source={} session={} raw='{}' playUrlHost={} elapsed={}ms duration={}s",
                    sourceId, payload.sessionId(), payload.rawUrl(), ClientMediaPreparer.hostOf(payload.playUrl()),
                    payload.elapsedMillis(), payload.durationSeconds());
        }
    }

    @Override
    public void onPrepareCompleted(ClientMediaSyncPayload payload, UUID sourceId,
            ClientMediaPreparer.PreparedMedia prepared, long costMillis) {
        if (PAD_VIDEO_DEBUG_LOG) {
            LOGGER.info("Pad playback prepare completed: source={} session={} cost={}ms host={}", sourceId,
                    payload.sessionId(), costMillis,
                    prepared != null ? ClientMediaPreparer.hostOf(prepared.playUrl()) : "unknown");
        }
    }
}