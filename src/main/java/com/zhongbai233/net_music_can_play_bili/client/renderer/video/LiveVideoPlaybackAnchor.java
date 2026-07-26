package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/**
 * 直播机视频锚点：播放时钟取直播机方块处的 OpenAL 可听位置。
 *
 * <p>
 * 直播视频样本的 pts 由 {@code LiveVideoSampleBus} 换算到同一音频输出时间域，
 * 因此帧展示条件 {@code pts <= audibleMillis} 即为唇音同步；没有总时长与 seek。
 * </p>
 */
final class LiveVideoPlaybackAnchor implements VideoPlaybackAnchor {
    private final BlockPos livePos;
    private final MediaVideoTimeline timeline;

    LiveVideoPlaybackAnchor(BlockPos livePos, String sessionId) {
        this.livePos = livePos != null ? livePos.immutable() : null;
        this.timeline = this.livePos != null
                ? new LiveAudioMediaTimeline(this.livePos, sessionId != null ? sessionId : "")
                : MediaVideoTimeline.EMPTY;
    }

    @Override
    public MediaVideoTimeline timeline() {
        return timeline;
    }

    @Override
    public Vec3 position() {
        return livePos != null
                ? new Vec3(livePos.getX() + 0.5D, livePos.getY() + 0.5D, livePos.getZ() + 0.5D)
                : null;
    }

    @Override
    public boolean isForTurntable(BlockPos pos) {
        return pos != null && livePos != null && livePos.equals(pos);
    }

    @Override
    public boolean isWithinAudioRange(Minecraft minecraft, Collection<BlockPos> fallbackProjectors, double rangeSqr) {
        if (minecraft == null || minecraft.player == null) {
            return false;
        }
        Vec3 playerPos = minecraft.player.position();
        Vec3 anchorPos = position();
        if (anchorPos != null && anchorPos.distanceToSqr(playerPos) <= rangeSqr) {
            return true;
        }
        if (fallbackProjectors == null) {
            return false;
        }
        for (BlockPos pos : fallbackProjectors) {
            Vec3 projectorPos = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (projectorPos.distanceToSqr(playerPos) <= rangeSqr) {
                return true;
            }
        }
        return false;
    }

    private record LiveAudioMediaTimeline(BlockPos livePos, String sessionId) implements MediaVideoTimeline {
        @Override
        public long mediaMillis() {
            ClientAudioOutputRegistry.AudioTimeline audio = ClientAudioOutputRegistry.getAudioTimeline(livePos);
            String audioSession = audio.audioSessionId();
            if (audioSession != null && !audioSession.isBlank() && !audioSession.equals(sessionId)) {
                return -1L;
            }
            return audio.audibleMillis();
        }

        @Override
        public long visualMillis() {
            return mediaMillis();
        }

        @Override
        public long pacingMillis() {
            return mediaMillis();
        }

        @Override
        public long relativeNanos(long absoluteStartMillis) {
            long millis = mediaMillis();
            return millis < 0L ? -1L : Math.max(0L, millis - Math.max(0L, absoluteStartMillis)) * 1_000_000L;
        }

        @Override
        public long totalMillis() {
            return 0L;
        }
    }
}
