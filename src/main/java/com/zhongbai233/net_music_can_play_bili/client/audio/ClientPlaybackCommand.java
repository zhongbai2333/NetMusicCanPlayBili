package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;

/**
 * 从网络协议冻结出的客户端播放命令。
 *
 * <p>
 * 后续异步准备和解码只读取该快照，不再重复读取可能滞后的方块实体状态。
 * </p>
 */
public record ClientPlaybackCommand(
        int sourceX,
        int sourceY,
        int sourceZ,
        String rawUrl,
        String playUrl,
        String songName,
        int remainingSeconds,
        String sessionId,
        long elapsedMillis,
        long totalMillis,
        PlaybackSync.MinecartAnchor minecartAnchor,
        boolean biliSelection,
        boolean loadLyrics) {

    public ClientPlaybackCommand {
        rawUrl = normalize(rawUrl);
        playUrl = normalize(playUrl);
        songName = normalize(songName);
        remainingSeconds = Math.max(1, remainingSeconds);
        sessionId = normalize(sessionId);
        elapsedMillis = Math.max(0L, elapsedMillis);
        totalMillis = Math.max(0L, totalMillis);
        if (totalMillis > 0L) {
            elapsedMillis = Math.min(totalMillis, elapsedMillis);
        }
    }

    public boolean hasSession() {
        return !sessionId.isBlank();
    }

    public PlaybackSync.Metadata syncMetadata() {
        return hasSession()
                ? new PlaybackSync.Metadata(sessionId, elapsedMillis, totalMillis)
                : new PlaybackSync.Metadata("", 0L, 0L);
    }

    public long durationMillis() {
        return totalMillis > 0L ? totalMillis : Math.max(0L, remainingSeconds) * 1000L;
    }

    private static String normalize(String value) {
        return value != null ? value : "";
    }
}