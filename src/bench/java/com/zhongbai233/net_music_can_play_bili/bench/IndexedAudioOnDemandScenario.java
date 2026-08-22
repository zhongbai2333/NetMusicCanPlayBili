package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackDemandIndex;
import com.zhongbai233.net_music_can_play_bili.media.audio.IndexedAudioEndpoint;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaPlaybackLauncher;
import com.github.tartaricacid.netmusic.init.InitSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic proof of chunk-independent discovery and exact on-demand decoder ownership. */
final class IndexedAudioOnDemandScenario implements BenchClientScenario {
    private static final BenchMetricDescriptor STARTS = new BenchMetricDescriptor(
            "ncpb.indexed_audio.starts", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor STOPS = new BenchMetricDescriptor(
            "ncpb.indexed_audio.stops", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor REMOTE_DISTANCE = new BenchMetricDescriptor(
            "ncpb.indexed_audio.remote_distance", "blocks", MetricDirection.HIGHER_IS_BETTER);

    private final PlaybackSourceId sourceId = PlaybackSourceId.of(
            UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private final PlaybackSessionId sessionId = PlaybackSessionId.of("bench-indexed-on-demand");
    private final UUID firstEndpoint = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private final UUID secondEndpoint = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private final AudioEndpointIndex endpoints = new AudioEndpointIndex();
    private final AudioPlaybackDemandIndex<String> demand = new AudioPlaybackDemandIndex<>();
    private int starts;
    private int stops;

    @Override
    public void setup(BenchClientContext context) {
        AtomicBoolean soundFactoryCalled = new AtomicBoolean();
        boolean submittedSynchronously = SyncedMediaPlaybackLauncher.play(
                new SyncedMediaPlaybackLauncher.LaunchResult("https://example.invalid/indexed-submit.mp3", null),
                "indexed synchronous submit", (url, lyric) -> {
                    soundFactoryCalled.set(true);
                    return new ImmediateBenchSound(true);
                }, false);
        if (!submittedSynchronously || !soundFactoryCalled.get()) {
            throw new AssertionError("Sound factory was still queued after play returned");
        }
        boolean rejectedSilentSound = SyncedMediaPlaybackLauncher.play(
                new SyncedMediaPlaybackLauncher.LaunchResult("https://example.invalid/indexed-reject.mp3", null),
                "indexed rejected silent submit", (url, lyric) -> new ImmediateBenchSound(false), false);
        if (rejectedSilentSound) {
            throw new AssertionError("Launcher reported success after SoundEngine rejected a silent sound");
        }
        String dimension = context.level().dimension().identifier().toString();
        endpoints.upsert(new IndexedAudioEndpoint(firstEndpoint, sourceId, dimension,
                512.5D, 80.5D, 0.5D, 64.0F, 1.0F, 1.0F,
                IndexedAudioEndpoint.Kind.SPEAKER, 1L));
        endpoints.upsert(new IndexedAudioEndpoint(secondEndpoint, sourceId, dimension,
                544.5D, 80.5D, 0.5D, 64.0F, 1.0F, 1.0F,
                IndexedAudioEndpoint.Kind.SPEAKER, 1L));
        demand.announce(sourceId, sessionId, "metadata-only");

        Set<UUID> oldPrewarmOnly = endpoints.audibleDemands(sourceId, dimension,
                544.5D + 80.0D, 80.5D, 0.5D);
        demand.updateDemand(sourceId, sessionId, oldPrewarmOnly, 0L);
        if (!oldPrewarmOnly.isEmpty() || demand.claimStart(sourceId, sessionId).isPresent()) {
            throw new AssertionError("Former prewarm-only band started indexed audio");
        }

        Set<UUID> remote = endpoints.audibleDemands(sourceId, dimension, 528.5D, 80.5D, 0.5D);
        if (!remote.contains(firstEndpoint) || !remote.contains(secondEndpoint)) {
            throw new AssertionError("Remote endpoints were not both discovered: " + remote);
        }
        demand.updateDemand(sourceId, sessionId, remote, 100L);
        if (demand.claimStart(sourceId, sessionId).isEmpty()) {
            throw new AssertionError("Audible remote endpoint did not claim a starting decoder");
        }
        demand.updateDemand(sourceId, sessionId, Set.of(), 200L);
        if (!demand.claimStopAfterIdle(sourceId, sessionId, 1_700L, 1_500L)
                || demand.markPlaying(sourceId, sessionId)) {
            throw new AssertionError("A late stream-ready event revived a retired start");
        }
        stops++;
        demand.updateDemand(sourceId, sessionId, remote, 2_000L);
        if (demand.claimStart(sourceId, sessionId).isEmpty() || !demand.markPlaying(sourceId, sessionId)) {
            throw new AssertionError("Re-entering range did not start and promote a fresh decoder");
        }
        starts++;
        if (demand.claimStart(sourceId, sessionId).isPresent()) {
            throw new AssertionError("Multiple endpoints created a duplicate decoder");
        }

        demand.updateDemand(sourceId, sessionId, Set.of(), 3_000L);
        if (demand.claimStopAfterIdle(sourceId, sessionId, 4_499L, 1_500L)
                || !demand.claimStopAfterIdle(sourceId, sessionId, 4_500L, 1_500L)) {
            throw new AssertionError("Indexed idle grace did not stop exactly at the boundary");
        }
        stops++;
        demand.updateDemand(sourceId, sessionId, Set.of(firstEndpoint), 5_000L);
        if (demand.claimStart(sourceId, sessionId).isEmpty()) {
            throw new AssertionError("Returning demand did not restart from retained metadata");
        }
        starts++;
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE
                : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        context.metrics().record(STARTS, starts);
        context.metrics().record(STOPS, stops);
        context.metrics().record(REMOTE_DISTANCE, 512.0D);
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (starts != 2 || stops != 2 || endpoints.endpointsFor(sourceId).size() != 2) {
            throw new AssertionError("Indexed audio lifecycle mismatch: starts=" + starts + " stops=" + stops);
        }
    }

    private static final class ImmediateBenchSound extends AbstractTickableSoundInstance {
        private final boolean silentStartAllowed;

        private ImmediateBenchSound(boolean silentStartAllowed) {
            super(InitSounds.NET_MUSIC.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.silentStartAllowed = silentStartAllowed;
            relative = true;
            volume = 0.0F;
            stop();
        }

        @Override
        public boolean canStartSilent() {
            return silentStartAllowed;
        }

        @Override
        public void tick() {
        }
    }
}
