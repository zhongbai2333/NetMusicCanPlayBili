package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelineView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.UUID;

/** 审核 GUI 预览专用锚点：视频直接跟随同一条 MP4 音频时间线。 */
final class PreviewVideoPlaybackAnchor implements VideoPlaybackAnchor {
    private final UUID sourceId;
    private final String sessionId;
    private final long startOffsetMillis;
    private final long totalMillis;

    PreviewVideoPlaybackAnchor(UUID sourceId, String sessionId, long startOffsetMillis, long totalMillis) {
        this.sourceId = sourceId;
        this.sessionId = sessionId != null ? sessionId : "";
        this.startOffsetMillis = Math.max(0L, startOffsetMillis);
        this.totalMillis = Math.max(0L, totalMillis);
    }

    @Override
    public MediaVideoTimeline timeline() {
        ClientMediaTimelineView view = ClientMediaTimelineView.forMp4Owner(sourceId, sessionId, startOffsetMillis,
                totalMillis);
        if (!sessionId.equals(view.sessionId()) || !view.hasTimeline()) {
            return MediaVideoTimeline.EMPTY;
        }
        if (!view.started()) {
            return new FrozenTimeline();
        }
        return new SnapshotTimeline(view);
    }

    @Override
    public Vec3 position() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null ? minecraft.player.position().add(0.0D, minecraft.player.getEyeHeight(), 0.0D)
                : Vec3.ZERO;
    }

    @Override
    public boolean isForTurntable(BlockPos pos) {
        return false;
    }

    @Override
    public boolean isWithinAudioRange(Minecraft minecraft, Collection<BlockPos> fallbackProjectors, double rangeSqr) {
        return minecraft != null && minecraft.player != null;
    }

    private final class SnapshotTimeline implements MediaVideoTimeline {
        private final ClientMediaTimelineView view;

        private SnapshotTimeline(ClientMediaTimelineView view) {
            this.view = view;
        }

        @Override
        public long mediaMillis() {
            return view.mediaMillis();
        }

        @Override
        public long visualMillis() {
            return view.visualMillis();
        }

        @Override
        public long pacingMillis() {
            return view.pacingMillis();
        }

        @Override
        public long relativeNanos(long absoluteStartMillis) {
            return view.relativeNanos(absoluteStartMillis);
        }

        @Override
        public long totalMillis() {
            return view.totalMillis();
        }

        @Override
        public String sessionId() {
            return sessionId;
        }
    }

    private final class FrozenTimeline implements MediaVideoTimeline {
        @Override
        public long mediaMillis() {
            return startOffsetMillis;
        }

        @Override
        public long visualMillis() {
            return startOffsetMillis;
        }

        @Override
        public long pacingMillis() {
            return startOffsetMillis;
        }

        @Override
        public long relativeNanos(long absoluteStartMillis) {
            return Math.max(0L, startOffsetMillis - Math.max(0L, absoluteStartMillis)) * 1_000_000L;
        }

        @Override
        public long totalMillis() {
            return totalMillis;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }
    }
}
