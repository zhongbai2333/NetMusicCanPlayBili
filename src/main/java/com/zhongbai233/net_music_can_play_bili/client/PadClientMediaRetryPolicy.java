package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryPolicy;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.network.PadPlaybackControlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.UUID;

/** Pad retry action for shared stream recovery. */
final class PadClientMediaRetryPolicy implements ClientMediaRetryPolicy {
    static final PadClientMediaRetryPolicy INSTANCE = new PadClientMediaRetryPolicy();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long STREAM_RETRY_DELAY_MILLIS = 750L;

    private PadClientMediaRetryPolicy() {
    }

    @Override
    public long retryDelayMillis() {
        return STREAM_RETRY_DELAY_MILLIS;
    }

    @Override
    public void onRetryScheduled(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active,
            Throwable error) {
        LOGGER.warn("Pad 音频流初始化失败，将自动刷新直链并重试一次: device={} session={} song='{}' at={}ms reason={}",
                deviceId, sessionId, active.songName(), Math.max(0L, active.elapsedMillis()),
                error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    @Override
    public void scheduleRetry(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active,
            Throwable error) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) {
            return;
        }
        ItemStack stack = PadItem.findByDeviceId(client.player, deviceId);
        if (!PadItem.isPad(stack)) {
            return;
        }
        UUID pointId = PadClientMediaSessionIds.pointId(sessionId);
        if (pointId == null) {
            return;
        }
        client.getConnection().send(new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.SEEK, deviceId,
                pointId, Math.max(0L, active.elapsedMillis())));
    }
}