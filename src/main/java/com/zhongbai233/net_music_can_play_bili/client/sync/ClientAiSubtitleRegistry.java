package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import net.minecraft.core.BlockPos;

/** Shared client owner for Bilibili AI-CC subtitle requests used by control-console consumers. */
public final class ClientAiSubtitleRegistry {
    public enum Status {
        LOADING,
        READY,
        UNAVAILABLE,
        FAILED
    }

    public record Snapshot(Status status, LyricRecord lyricRecord, String failureReason) {
        public Snapshot {
            failureReason = failureReason != null ? failureReason : "";
        }

        public boolean ready() {
            return status == Status.READY && lyricRecord != null;
        }
    }

    private static final AiSubtitleSessionRegistry<BlockPos, BlockPos, LyricRecord> REGISTRY =
            new AiSubtitleSessionRegistry<>((rawUrl, title) -> task(
                    ClientMediaPreparer.buildAiSubtitleAsync(rawUrl, title)));

    private ClientAiSubtitleRegistry() {
    }

    public static void acquire(BlockPos consumerPos, BlockPos sourcePos, PlaybackSessionId sessionId,
            String rawUrl, String title) {
        if (consumerPos == null || sourcePos == null || sessionId == null || rawUrl == null || rawUrl.isBlank()) {
            release(consumerPos);
            return;
        }
        REGISTRY.acquire(consumerPos.immutable(), sourcePos.immutable(), sessionId, rawUrl, title);
    }

    public static Snapshot snapshot(BlockPos sourcePos, PlaybackSessionId sessionId) {
        AiSubtitleSessionRegistry.Snapshot<LyricRecord> snapshot = REGISTRY.snapshot(sourcePos, sessionId);
        return new Snapshot(Status.valueOf(snapshot.status().name()), snapshot.result(), snapshot.failureReason());
    }

    public static void release(BlockPos consumerPos) {
        if (consumerPos != null) {
            REGISTRY.release(consumerPos);
        }
    }

    public static void clear() {
        REGISTRY.clear();
    }

    public static void releaseChunk(int chunkX, int chunkZ) {
        REGISTRY.releaseMatching(pos -> Math.floorDiv(pos.getX(), 16) == chunkX
                && Math.floorDiv(pos.getZ(), 16) == chunkZ);
    }

    public static int activeSessions() {
        return REGISTRY.activeSessions();
    }

    public static int activeConsumers() {
        return REGISTRY.activeConsumers();
    }

    public static String describe() {
        AiSubtitleSessionRegistry.Diagnostics diagnostics = REGISTRY.diagnostics();
        return "aiSubtitles sessions=" + diagnostics.sessions()
                + " consumers=" + diagnostics.consumers()
                + " loading=" + diagnostics.loading()
                + " ready=" + diagnostics.ready()
                + " unavailable=" + diagnostics.unavailable()
                + " failed=" + diagnostics.failed();
    }

    private static AiSubtitleSessionRegistry.Task<LyricRecord> task(CancellableTaskFuture<LyricRecord> future) {
        return new AiSubtitleSessionRegistry.Task<>() {
            @Override
            public java.util.concurrent.CompletableFuture<LyricRecord> future() {
                return future;
            }

            @Override
            public void cancel() {
                future.cancel(true);
            }
        };
    }
}
