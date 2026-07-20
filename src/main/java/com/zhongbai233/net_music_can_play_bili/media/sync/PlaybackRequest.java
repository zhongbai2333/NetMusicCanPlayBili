package com.zhongbai233.net_music_can_play_bili.media.sync;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * 播放请求的强类型身份载体。
 *
 * <p>
 * 媒体 URL 只表示资源地址；session、seek 进度和输出归属都通过此对象传递，
 * 不再依赖 URL 字符串或按 URL 排队。
 * </p>
 */
public record PlaybackRequest(
        String mediaUrl,
        BlockPos pos,
        String sessionId,
        long elapsedMillis,
        long totalMillis,
        UUID ownerId,
        UUID minecartUuid,
        long capturedNanos) {

    public PlaybackRequest {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            throw new IllegalArgumentException("mediaUrl must not be blank");
        }
        mediaUrl = PlaybackSync.strip(mediaUrl);
        pos = AudioUtils.copyPos(pos);
        sessionId = sessionId != null ? sessionId : "";
        elapsedMillis = Math.max(0L, elapsedMillis);
        totalMillis = Math.max(0L, totalMillis);
        capturedNanos = capturedNanos > 0L ? capturedNanos : System.nanoTime();
    }

    public static PlaybackRequest now(String mediaUrl, BlockPos pos, String sessionId, long elapsedMillis,
            long totalMillis, UUID ownerId, UUID minecartUuid) {
        return new PlaybackRequest(mediaUrl, pos, sessionId, elapsedMillis, totalMillis, ownerId, minecartUuid,
                System.nanoTime());
    }

    public float startOffsetSeconds() {
        return elapsedMillis / 1000.0f;
    }
}