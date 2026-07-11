package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryPolicy;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.UUID;

/** MP4 retry action for shared stream recovery. */
final class Mp4ClientMediaRetryPolicy implements ClientMediaRetryPolicy {
    static final Mp4ClientMediaRetryPolicy INSTANCE = new Mp4ClientMediaRetryPolicy();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long STREAM_RETRY_DELAY_MILLIS = 750L;

    private Mp4ClientMediaRetryPolicy() {
    }

    @Override
    public long retryDelayMillis() {
        return STREAM_RETRY_DELAY_MILLIS;
    }

    @Override
    public void onRetryScheduled(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active,
            Throwable error) {
        long targetMillis = Math.max(0L, active.elapsedMillis());
        LOGGER.warn("MP4 音频流初始化失败，将自动刷新直链并重试一次: owner={} session={} song='{}' at={}ms reason={}",
                deviceId, sessionId, active.songName(), targetMillis,
                error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    @Override
    public void scheduleRetry(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active,
            Throwable error) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) {
            return;
        }
        ItemStack stack = MP4Item.findByDeviceId(client.player, deviceId);
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        client.getConnection().send(new MP4PlaybackControlPacket(MP4PlaybackControlPacket.Action.SEEK,
                active.queueIndex(), Math.round(active.volume() * 1000.0F), Math.max(0L, active.elapsedMillis()),
                deviceId));
    }
}