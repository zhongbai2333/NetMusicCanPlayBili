package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/**
 * 视频播放锚点抽象。
 *
 * <p>
 * 现代化唱片机使用固定 BlockPos；未来 MP4 可用 deviceId/sourceId 实现动态锚点，
 * 让视频播放器不再硬编码 turntablePos。
 * </p>
 */
interface VideoPlaybackAnchor {
    MediaVideoTimeline timeline();

    Vec3 position();

    boolean isForTurntable(BlockPos pos);

    boolean isWithinAudioRange(Minecraft minecraft, Collection<BlockPos> fallbackProjectors, double rangeSqr);

    static VideoPlaybackAnchor turntable(BlockPos turntablePos, String sessionId, long totalMillis) {
        return new TurntableVideoPlaybackAnchor(turntablePos, sessionId, totalMillis);
    }
}
