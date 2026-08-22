package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientPlaybackCommand;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntableSound;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.network.AudioEndpointSnapshotPacket;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Production SoundEngine/OpenAL proof for audible -> outside -> audible re-entry on one session. */
final class RealMp3RangeReentryScenario implements BenchClientScenario {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TOTAL_MILLIS = 360_000L;
    private static final BenchMetricDescriptor CHANNEL_STARTS = new BenchMetricDescriptor(
            "ncpb.real_mp3_range_reentry.channel_starts", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor PHASE = new BenchMetricDescriptor(
            "ncpb.real_mp3_range_reentry.phase", "phase", MetricDirection.NEUTRAL);
    private final AudioStreamProperties.RealMp3Bench properties = AudioStreamProperties.realMp3Bench();
    private final PlaybackSourceId sourceId = PlaybackSourceId.of(
            UUID.fromString("60000000-0000-0000-0000-000000000001"));
    private final PlaybackSessionId sessionId = PlaybackSessionId.of("bench-real-mp3-range-reentry");
    private final UUID endpointId = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private final AtomicInteger streamingStarts = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Consumer<PlayStreamingSourceEvent> streamingListener = event -> {
        if (event.getSound() instanceof ModernTurntableSound sound && sessionId.value().equals(sound.sessionId())) {
            streamingStarts.incrementAndGet();
        }
    };
    private BlockPos sourcePos;
    private BlockPos endpointPos;
    private Vec3 home;
    private Vec3 far;
    private UUID playerId;
    private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
    private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
    private long audioStagingBaseline;
    private StereoOpenALHandler.PcmQuality firstPcm = emptyPcm();
    private StereoOpenALHandler.PcmQuality secondPcm = emptyPcm();
    private int phase;
    private boolean listenerRegistered;
    private boolean cleanupRequested;

    @Override
    public void setup(BenchClientContext context) {
        ClientAudioOutputRegistry.cleanup();
        ClientAudioEndpointIndex.clear();
        HttpAudioStreamHandler.closeModernStreams();
        ModernTurntablePlaybackTracker.stopAllSounds();
        playerId = context.player().getUUID();
        context.player().setNoGravity(true);
        setServerNoGravity(context, true);
        home = context.player().position();
        far = home.add(0.0D, 0.0D, 96.0D);
        sourcePos = context.player().blockPosition().offset(128, 0, 0).immutable();
        endpointPos = context.player().blockPosition().offset(3, 0, 0).immutable();
        stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
        tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
        audioStagingBaseline = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING)
                .currentBytes();
        NeoForge.EVENT_BUS.addListener(PlayStreamingSourceEvent.class, streamingListener);
        listenerRegistered = true;

        ClientAudioEndpointIndex.accept(new AudioEndpointSnapshotPacket(sourceId.value(), sourcePos,
                List.of(new AudioEndpointSnapshotPacket.Endpoint(endpointId, endpointPos,
                        0, 1.0F, false, 16.0F, 1L))));
        String synchronizedUrl = PlaybackSync.withSourceId(
                PlaybackSync.withSync(properties.url(), sessionId, 0L, TOTAL_MILLIS), sourceId);
        ModernTurntablePlaybackCoordinator.play(new ClientPlaybackCommand(
                sourcePos.getX(), sourcePos.getY(), sourcePos.getZ(), properties.url(), synchronizedUrl,
                "real MP3 range re-entry", (int) (TOTAL_MILLIS / 1_000L), sessionId.value(), 0L,
                TOTAL_MILLIS, null, false, false));
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
        var output = ClientAudioOutputRegistry.getStereoSnapshot(sourcePos).orElse(null);
        var timeline = ClientAudioOutputRegistry.getAudioTimeline(sourcePos);
        context.metrics().record(CHANNEL_STARTS, streamingStarts.get());
        context.metrics().record(PHASE, phase);

        if (phase == 0 && healthy(output, timeline, 1)) {
            firstPcm = output.firstPcm();
            LOGGER.info("真实范围重入 Bench: 首次出声，准备离开；state={}",
                    ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots());
            teleport(context, far);
            phase = 1;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 1 && context.player().position().distanceToSqr(far) < 4.0D) {
            phase = 2;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 2 && outsideConverged(context)) {
            LOGGER.info("真实范围重入 Bench: 范围外已收敛，准备返回；state={}",
                    ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots());
            teleport(context, home);
            phase = 3;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 3 && context.player().position().distanceToSqr(home) < 4.0D) {
            LOGGER.info("真实范围重入 Bench: 已返回原位；state={}",
                    ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots());
            phase = 4;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 4 && healthy(output, timeline, 2)) {
            secondPcm = output.firstPcm();
            ModernTurntablePlaybackCoordinator.stop(sourcePos, sessionId.value());
            cleanupRequested = true;
            phase = 5;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 5 && cleanupRequested && finalConverged(context)) {
            phase = 6;
            return BenchClientStepResult.COMPLETE;
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        if (phase != 6 || streamingStarts.get() != 2 || !finalConverged(context)) {
            throw new AssertionError("Range re-entry did not create exactly two healthy physical streams: phase="
                    + phase + " starts=" + streamingStarts.get() + " timeline="
                    + ClientAudioOutputRegistry.getAudioTimeline(sourcePos));
        }
        requirePcmQuality("range re-entry first audible pass", firstPcm);
        requirePcmQuality("range re-entry second audible pass", secondPcm);
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        if (stereo.instancesCreated() != stereoBaseline.instancesCreated() + 2L
                || stereo.cleanupsCompleted() < stereoBaseline.cleanupsCompleted() + 2L
                || tap.instancesCreated() != tapBaseline.instancesCreated() + 2L
                || tap.closesCompleted() < tapBaseline.closesCompleted() + 2L) {
            throw new AssertionError("Range re-entry physical resource counts mismatch: stereo=" + stereo
                    + " baseline=" + stereoBaseline + " tap=" + tap + " tapBaseline=" + tapBaseline);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        if (sourcePos != null) {
            ModernTurntablePlaybackCoordinator.stop(sourcePos, sessionId.value());
        }
        if (home != null) {
            teleport(context, home);
        }
        context.player().setNoGravity(false);
        setServerNoGravity(context, false);
        ClientAudioEndpointIndex.clear();
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        ModernTurntablePlaybackTracker.stopAllSounds();
        if (listenerRegistered) {
            NeoForge.EVENT_BUS.unregister(streamingListener);
            listenerRegistered = false;
        }
    }

    private boolean healthy(StereoOpenALHandler.DiagnosticSnapshot output,
            ClientAudioOutputRegistry.AudioTimeline timeline, int expectedStarts) {
        if (output == null || !output.started() || output.firstPcm().samples() < 1_024L
                || streamingStarts.get() != expectedStarts || timeline.relayStartedCount() < 1
                || !sessionId.equals(timeline.playbackSessionId().orElse(null))) {
            return false;
        }
        requirePcmQuality("range re-entry pass " + expectedStarts, output.firstPcm());
        return true;
    }

    private boolean outsideConverged(BenchClientContext context) {
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        return ClientAudioOutputRegistry.getStereoSnapshot(sourcePos).isEmpty()
                && tap.activeInstances() == tapBaseline.activeInstances()
                && tap.closesCompleted() >= tapBaseline.closesCompleted() + 1L
                && stereo.activeInstances() == stereoBaseline.activeInstances()
                && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 1L;
    }

    private boolean finalConverged(BenchClientContext context) {
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        return ClientAudioOutputRegistry.getStereoSnapshot(sourcePos).isEmpty()
                && tap.activeInstances() == tapBaseline.activeInstances()
                && tap.closesCompleted() >= tapBaseline.closesCompleted() + 2L
                && stereo.activeInstances() == stereoBaseline.activeInstances()
                && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 2L
                && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                && MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING).currentBytes()
                        == audioStagingBaseline;
    }

    private void teleport(BenchClientContext context, Vec3 target) {
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            failure.compareAndSet(null, new IllegalStateException("Integrated server disappeared"));
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.level() instanceof ServerLevel level)
                        || !player.teleportTo(level, target.x, target.y, target.z, Set.<Relative>of(),
                                player.getYRot(), player.getXRot(), true)) {
                    throw new IllegalStateException("Could not teleport range re-entry player to " + target);
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    private void setServerNoGravity(BenchClientContext context, boolean noGravity) {
        var server = context.minecraft().getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    player.setNoGravity(noGravity);
                }
            });
        }
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Real MP3 range re-entry failed", error);
        }
    }

    private static StereoOpenALHandler.PcmQuality emptyPcm() {
        return new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
    }
}
