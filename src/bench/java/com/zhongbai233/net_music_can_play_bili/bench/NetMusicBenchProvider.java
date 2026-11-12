package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchApiVersion;
import com.zhongbai233.bench.api.BenchCompatibility;
import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.ScenarioDescriptor;
import com.zhongbai233.bench.api.neoforge.client.BenchClientProvider;
import com.zhongbai233.bench.api.neoforge.client.BenchClientRegistrar;
import com.zhongbai233.bench.api.neoforge.server.BenchServerProvider;
import com.zhongbai233.bench.api.neoforge.server.BenchServerRegistrar;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureProperties;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.time.Duration;
import java.util.Set;

/** Test-only integrated-client workloads. This class must never enter the production jar. */
public final class NetMusicBenchProvider implements BenchClientProvider, BenchServerProvider {
    static final BlockPos MULTI_CLIENT_CONSOLE_POS = new BlockPos(0, 200, 0);
    static final BlockPos MULTI_CLIENT_SOURCE_POS = new BlockPos(2, 200, 0);
    static final BenchMetricDescriptor MULTI_CLIENT_PLAYER_COUNT = new BenchMetricDescriptor(
            "ncpb.multi_client.players", "players", MetricDirection.NEUTRAL);
    static final BenchMetricDescriptor MULTI_CLIENT_LEASE_COUNT = new BenchMetricDescriptor(
            "ncpb.multi_client.console_leases", "leases", MetricDirection.NEUTRAL);
    static final BenchMetricDescriptor MULTI_CLIENT_REAL_MEDIA_LOADED = new BenchMetricDescriptor(
            "ncpb.multi_client.real_media_loaded", "state", MetricDirection.HIGHER_IS_BETTER);
    static final BenchMetricDescriptor MULTI_CLIENT_REAL_MEDIA_CONVERGED = new BenchMetricDescriptor(
            "ncpb.multi_client.real_media_converged", "state", MetricDirection.HIGHER_IS_BETTER);
    static final BenchMetricDescriptor MULTI_CLIENT_REAL_MEDIA_OWNED_BYTES = new BenchMetricDescriptor(
            "ncpb.multi_client.real_media_owned_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
    static final BenchMetricDescriptor MULTI_CLIENT_REAL_MEDIA_IRIS = new BenchMetricDescriptor(
            "ncpb.multi_client.real_media_iris", "state", MetricDirection.HIGHER_IS_BETTER);

    public NetMusicBenchProvider() {
    }

    @Override
    public String id() {
        return "ncpb";
    }

    @Override
    public BenchCompatibility compatibility() {
        return BenchApiVersion.currentCompatibility();
    }

    @Override
    public void registerServer(BenchServerRegistrar registrar) {
        if (Integer.getInteger("modBench.paired.clientCount", 1) < 2) {
            return;
        }
        registrar.register(new ScenarioDescriptor(
                "ncpb.multi-client-consumer-lifecycle",
                "Two physical clients hold independent console leases through same-UUID reconnect and disconnect cleanup",
                Set.of("server", "multi-client", "console", "lease", "network", "disconnect", "reconnect"),
                Duration.ofMinutes(2)), ignored -> new MultiClientConsumerServerScenario(true));
        registrar.register(new ScenarioDescriptor(
                "ncpb.multi-client-real-media-lifecycle",
                "Two physical real-media clients preserve the survivor through staggered exit",
                Set.of("server", "multi-client", "console", "lease", "network", "disconnect", "media"),
                Duration.ofMinutes(5)), ignored -> new MultiClientConsumerServerScenario(false));
    }

    @Override
    public void registerClient(BenchClientRegistrar registrar) {
        registrar.register(new ScenarioDescriptor(
                "ncpb.platform-mixin-audit",
                "Real FML transformed targets and actual sound bridge injection",
                Set.of("client", "platform", "mixin", "fml"), Duration.ofSeconds(20)),
                ignored -> new PlatformFunctionalClosureMixinAuditScenario());
        if (Integer.getInteger("modBench.paired.clientCount", 1) >= 2) {
            registrar.register(new ScenarioDescriptor(
                    "ncpb.multi-client-consumer-lifecycle",
                    "Physical-client console lease acquisition, same-UUID reconnect and staggered disconnect",
                    Set.of("client", "multi-client", "console", "lease", "network", "disconnect", "reconnect"),
                    Duration.ofMinutes(2)), ignored -> new MultiClientConsumerClientScenario());
            if (VideoFeatureProperties.realMediaLifecycle().enabled()) {
                registrar.register(new ScenarioDescriptor(
                        "ncpb.multi-client-real-media-lifecycle",
                        "Two physical clients load real Bilibili video/audio; one exits while the survivor remains",
                        Set.of("client", "multi-client", "console", "lease", "network", "disconnect", "media",
                                "bilibili", "native", "gpu", "pbo", "openal", "iris", "shaderpack"),
                        Duration.ofMinutes(5)), ignored -> new MultiClientRealMediaScenario());
            }
        }
        registrar.register(new ScenarioDescriptor(
                "ncpb.deterministic-video-upload",
                "Deterministic RGBA, YUV420P and NV12/PBO upload and release convergence",
                Set.of("client", "media", "gpu", "upload", "resources"), Duration.ofSeconds(20)),
                ignored -> new DeterministicVideoUploadScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.cross-dimension-media-cleanup",
                "Real respawn packet, loading UI and exact media cleanup across a round-trip dimension change",
                Set.of("client", "server", "dimension", "packet", "gui", "media", "lifecycle"),
                Duration.ofSeconds(60)),
                ignored -> new CrossDimensionMediaCleanupScenario());
        if (Boolean.getBoolean("ncpb.live.real_bench")) {
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-live-device-topology",
                    "Real Bilibili live room 8178490 through live streamer, projector, console and speaker",
                    Set.of("client", "server", "live", "bilibili", "network", "audio", "video", "openal",
                            "native", "projector", "console", "speaker"), Duration.ofMinutes(5)),
                    ignored -> new RealLiveDeviceTopologyScenario());
        }
        if (AudioStreamProperties.realMp3Bench().enabled()) {
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-mp3-sound-engine",
                    "Real MP3 SoundEngine channels, decode-from-head seek windows, pause/resume and exact mute/range-stop",
                    Set.of("client", "media", "network", "mp3", "openal", "sound-engine", "channel", "seek",
                            "mute", "range"),
                    Duration.ofSeconds(180)),
                    ignored -> new RealMp3SoundEngineScenario());
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-turntable-volume-range-reentry",
                    "Real turntable GUI volume drag, right-click disc resolve, range exit/re-entry and eject cleanup",
                    Set.of("client", "server", "turntable", "gui", "slider", "volume", "movement", "network",
                            "block", "packet", "resolve", "mp3", "openal", "sound-engine", "range", "reentry", "pcm"),
                    Duration.ofSeconds(300)),
                    ignored -> new RealTurntableVolumeRangeReentryScenario());
        }
        if (VideoFeatureProperties.realBenchEnabled()) {
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-bv-playback",
                    "Real Bilibili DASH video/audio decode, OpenAL output, then projector range exit/re-entry convergence",
                    Set.of("client", "server", "media", "network", "native", "bilibili", "video", "audio", "openal",
                            "projector", "range", "reentry"),
                    Duration.ofSeconds(300)),
                    ignored -> new RealBvPlaybackScenario());
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-av1-h264-fallback",
                    "Real AV1/H.264 plan with injected AV1 startup failure, same-session fallback and physical resource convergence",
                    Set.of("client", "media", "network", "native", "bilibili", "av1", "h264",
                            "fallback", "seek", "resources"), Duration.ofSeconds(180)),
                    ignored -> new RealAv1H264FallbackScenario());
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-av1-hardware-seek",
                    "Real AV1 hardware playback, forward range seek, PTS monotonicity and physical resource convergence",
                    Set.of("client", "media", "network", "native", "bilibili", "av1", "hardware",
                            "seek", "pts", "resources"), Duration.ofSeconds(240)),
                    ignored -> new RealAv1HardwareSeekScenario());
        }
    }

    static void requirePcmQuality(String phase, StereoOpenALHandler.PcmQuality pcm) {
        if (pcm.samples() < 1_024L || pcm.peak() < 0.001F || pcm.rms() < 0.0001D
                || pcm.peak() > 1.001F || pcm.clippedRatio() >= 0.20D) {
            throw new AssertionError("Decoded PCM quality is implausible during " + phase + ": " + pcm);
        }
    }

    static <T> T requireBlockEntity(Level level, BlockPos pos, Class<T> type) {
        Object value = level.getBlockEntity(pos);
        if (!type.isInstance(value)) {
            throw new AssertionError(type.getSimpleName() + " is missing at " + pos + ": " + value);
        }
        return type.cast(value);
    }
}
