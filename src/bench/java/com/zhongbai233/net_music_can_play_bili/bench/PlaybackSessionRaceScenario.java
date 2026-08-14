package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackSessions;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;

import java.util.UUID;

final class PlaybackSessionRaceScenario implements BenchClientScenario {
    private static final int MEASURE_TICKS = 6;
    private static final BenchMetricDescriptor ACTIVE_PLAYBACKS = new BenchMetricDescriptor(
            "ncpb.playback.active", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor SOUND_DISCARDS = new BenchMetricDescriptor(
            "ncpb.playback.sound_discards", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor RETRY_DISPATCHES = new BenchMetricDescriptor(
            "ncpb.playback.retry_dispatches", "count", MetricDirection.LOWER_IS_BETTER);

    private final UUID sourceId = UUID.fromString("00000000-0000-0000-0000-00000000c0de");
    private final PlaybackSessionId startSession = PlaybackSessionId.of("bench-start");
    private final PlaybackSessionId firstSeek = PlaybackSessionId.of("bench-seek-1");
    private final PlaybackSessionId secondSeek = PlaybackSessionId.of("bench-seek-2");
    private final PlaybackSessionId finalSeek = PlaybackSessionId.of("bench-seek-final");
    private final PlaybackSessionId resumedSession = PlaybackSessionId.of("bench-resumed");
    private final RaceSyncPolicy policy = new RaceSyncPolicy();
    private int ticks;

    @Override
    public void setup(BenchClientContext context) {
        ClientMediaPlaybackSessions.clearAll(null);
        accept(context, startSession, "transport-start", 0L);
        accept(context, firstSeek, "transport-seek-1", 5_000L);
        accept(context, secondSeek, "transport-seek-2", 12_000L);
        accept(context, finalSeek, "transport-seek-final", 21_000L);
        requireCurrent(finalSeek, 21_000L, "continuous seek");
        requireDiscardCounts(1, 1, 1, 0);

        RaceSound failedTransport = policy.latestSound();
        failedTransport.failTransport();
        if (!ClientMediaRetryHandler.retryAfterStreamFailure(sourceId, finalSeek,
                new IllegalStateException("bench transport failure"), policy.retryPolicy())) {
            throw new AssertionError("Retained-session retry was not admitted");
        }
        if (!ClientMediaRetryHandler.isPending(sourceId, finalSeek)) {
            throw new AssertionError("Retry owner was not recorded before authoritative refresh");
        }
        accept(context, finalSeek, "transport-refreshed", 24_000L);
        requireCurrent(finalSeek, 24_000L, "retained-session refresh");
        if (ClientMediaRetryHandler.isPending(sourceId, finalSeek)) {
            throw new AssertionError("Authoritative retained-session refresh did not clear retry owner");
        }
        if (policy.latestSound() == failedTransport || failedTransport.discards() != 1) {
            throw new AssertionError("Retained-session refresh did not replace the failed sound exactly once");
        }
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        ticks++;
        if (ticks == 2) {
            ClientMediaSyncHandler.handleSync(MP4PlaybackSyncPacket.stop(context.player().getUUID(), sourceId, 0),
                    policy);
            requireConverged("pause");
        } else if (ticks == 3) {
            accept(context, resumedSession, "transport-resumed", 24_000L);
            requireCurrent(resumedSession, 24_000L, "resume");
        } else if (ticks == 5) {
            ClientMediaSyncHandler.handleSync(MP4PlaybackSyncPacket.stop(context.player().getUUID(), sourceId, 0),
                    policy);
            requireConverged("final stop");
        }
        context.metrics().record(ACTIVE_PLAYBACKS,
                ClientMediaPlaybackRegistry.contains(sourceId) ? 1 : 0);
        context.metrics().record(SOUND_DISCARDS, policy.totalDiscards());
        context.metrics().record(RETRY_DISPATCHES, policy.retryDispatches());
        return ticks >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        requireConverged("verify");
        if (policy.retryDispatches() != 0) {
            throw new AssertionError("Cleared delayed retry dispatched after retained-session refresh: "
                    + policy.retryDispatches());
        }
        if (policy.prepareCount() != 6 || policy.rebuildCount() != 1 || policy.stopCount() != 2) {
            throw new AssertionError("Unexpected playback transition counts: " + policy.summary());
        }
        if (policy.sounds().stream().anyMatch(sound -> sound.discards() != 1)) {
            throw new AssertionError("Each replaced/stopped sound must be discarded exactly once: "
                    + policy.summary());
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        ClientMediaPlaybackSessions.clearAll(null);
    }

    private void accept(BenchClientContext context, PlaybackSessionId sessionId, String transport,
            long elapsedMillis) {
        ClientMediaSyncPayload payload = new MP4PlaybackSyncPacket(context.player().getUUID(), sourceId,
                ClientMediaSyncPayload.SOURCE_PLAYER, context.player().getId(), context.player().getX(),
                context.player().getY(), context.player().getZ(), true, 0,
                "https://example.invalid/" + transport, "BV-bench", "bench", 120, 750,
                sessionId.value(), elapsedMillis, false);
        ClientMediaSyncHandler.handleSync(payload, policy);
    }

    private void requireCurrent(PlaybackSessionId expected, long expectedServerMillis, String phase) {
        ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(sourceId);
        ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
        if (active == null || !active.playbackSessionId().filter(expected::equals).isPresent()
                || active.timelineSnapshot().serverMillis() != expectedServerMillis
                || sound == null || !sound.playbackSession().filter(expected::equals).isPresent()) {
            throw new AssertionError("Unexpected active playback after " + phase + ": active=" + active
                    + " sound=" + sound);
        }
    }

    private void requireDiscardCounts(int... expected) {
        if (policy.sounds().size() != expected.length) {
            throw new AssertionError("Unexpected sound count: " + policy.summary());
        }
        for (int i = 0; i < expected.length; i++) {
            if (policy.sounds().get(i).discards() != expected[i]) {
                throw new AssertionError("Unexpected discard count at sound " + i + ": " + policy.summary());
            }
        }
    }

    private void requireConverged(String phase) {
        if (ClientMediaPlaybackRegistry.contains(sourceId) || ClientMediaSoundRegistry.get(sourceId) != null
                || ClientMediaRetryHandler.isPending(sourceId, finalSeek)) {
            throw new AssertionError("Playback state did not converge after " + phase);
        }
    }
}
