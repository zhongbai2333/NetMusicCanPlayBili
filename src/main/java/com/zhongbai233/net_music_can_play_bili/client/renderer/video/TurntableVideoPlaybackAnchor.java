package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Optional;

final class TurntableVideoPlaybackAnchor implements VideoPlaybackAnchor {
    private final BlockPos turntablePos;
    private final Optional<PlaybackSessionId> playbackSessionId;
    private final MediaVideoTimeline timeline;

    TurntableVideoPlaybackAnchor(BlockPos turntablePos, String sessionId, long totalMillis) {
        this.turntablePos = turntablePos != null ? turntablePos.immutable() : null;
        this.playbackSessionId = PlaybackSessionId.parse(sessionId);
        this.timeline = this.turntablePos != null
                ? new TurntableMediaVideoTimeline(this.turntablePos, playbackSessionId, totalMillis)
                : MediaVideoTimeline.EMPTY;
    }

    @Override
    public MediaVideoTimeline timeline() {
        return timeline;
    }

    @Override
    public Vec3 position() {
        return turntablePos != null
                ? new Vec3(turntablePos.getX() + 0.5D, turntablePos.getY() + 0.5D, turntablePos.getZ() + 0.5D)
                : null;
    }

    @Override
    public boolean isForTurntable(BlockPos pos) {
        return pos != null && turntablePos != null && turntablePos.equals(pos);
    }

    @Override
    public boolean isWithinAudioRange(Minecraft minecraft, Collection<BlockPos> fallbackProjectors, double rangeSqr) {
        if (minecraft == null || minecraft.player == null) {
            return false;
        }
        Vec3 playerPos = minecraft.player.position();
        Vec3 anchorPos = position();
        if (anchorPos != null && distanceSqr(anchorPos, playerPos) <= rangeSqr) {
            return true;
        }
        if (fallbackProjectors == null) {
            return false;
        }
        for (BlockPos pos : fallbackProjectors) {
            Vec3 projectorPos = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (distanceSqr(projectorPos, playerPos) <= rangeSqr) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object replacementOwnerKey() {
        return turntablePos != null
                ? new VideoPlaybackAnchor.TurntableOwnerKey(turntablePos)
                : playbackSessionId.<Object>map(value -> value).orElse(this);
    }

    private static double distanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    Optional<PlaybackSessionId> playbackSessionId() {
        return playbackSessionId;
    }

    private record TurntableMediaVideoTimeline(BlockPos turntablePos,
            Optional<PlaybackSessionId> playbackSessionId, long totalMillis)
            implements MediaVideoTimeline {
        private TurntableMediaVideoTimeline {
            playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
        }

        @Override
        public long mediaMillis() {
            return com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock.mediaMillis(turntablePos);
        }

        @Override
        public long visualMillis() {
            return com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock.visualMillis(turntablePos);
        }

        @Override
        public long pacingMillis() {
            return com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock.pacingMillis(turntablePos);
        }

        @Override
        public long relativeNanos(long absoluteStartMillis) {
            return com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock.relativeNanos(turntablePos,
                    absoluteStartMillis);
        }

    }
}
