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
        if (!System.getProperty("modBench.paired.sessionId", "").isBlank()) {
            registrar.register(new ScenarioDescriptor(
                    "ncpb.luckperms-permission-bridge",
                    "LuckPerms grants flow through NeoForge PermissionAPI into the real whitelist command gate",
                    Set.of("server", "paired", "permissions", "luckperms", "neoforge", "whitelist", "command"),
                    Duration.ofSeconds(30)), ignored -> new LuckPermsPermissionBridgeServerScenario());
        }
        if (Integer.getInteger("modBench.paired.clientCount", 1) < 2) {
            return;
        }
        registrar.register(new ScenarioDescriptor(
                "ncpb.multi-client-consumer-lifecycle",
                "Two physical clients hold independent console leases through disconnect cleanup",
                Set.of("server", "multi-client", "console", "lease", "network", "disconnect"),
                Duration.ofSeconds(60)), ignored -> new MultiClientConsumerServerScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.multi-client-real-media-lifecycle",
                "Two physical real-media clients preserve the survivor through staggered exit",
                Set.of("server", "multi-client", "console", "lease", "network", "disconnect", "media"),
                Duration.ofMinutes(5)), ignored -> new MultiClientConsumerServerScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.multi-client-reconnect",
                "One physical client disconnects and rejoins with the same identity while its peer remains",
                Set.of("server", "multi-client", "console", "lease", "network", "disconnect", "reconnect"),
                Duration.ofMinutes(2)), ignored -> new MultiClientReconnectServerScenario());
    }

    @Override
    public void registerClient(BenchClientRegistrar registrar) {
        if (!System.getProperty("modBench.paired.sessionId", "").isBlank()) {
            registrar.register(new ScenarioDescriptor(
                    "ncpb.luckperms-permission-bridge",
                    "Remote non-owner player participates in the LuckPerms permission bridge test",
                    Set.of("client", "paired", "permissions", "luckperms", "neoforge", "whitelist", "command"),
                    Duration.ofSeconds(30)), ignored -> new LuckPermsPermissionBridgeClientScenario());
        }
        if (Integer.getInteger("modBench.paired.clientCount", 1) >= 2) {
            registrar.register(new ScenarioDescriptor(
                    "ncpb.multi-client-consumer-lifecycle",
                    "Physical-client console lease acquisition and staggered disconnect",
                    Set.of("client", "multi-client", "console", "lease", "network", "disconnect"),
                    Duration.ofSeconds(60)), ignored -> new MultiClientConsumerClientScenario());
            if (VideoFeatureProperties.realMediaLifecycle().enabled()) {
                registrar.register(new ScenarioDescriptor(
                        "ncpb.multi-client-real-media-lifecycle",
                        "Two physical clients load real Bilibili video/audio; one exits while the survivor remains",
                        Set.of("client", "multi-client", "console", "lease", "network", "disconnect", "media",
                                "bilibili", "native", "gpu", "pbo", "openal", "iris", "shaderpack"),
                        Duration.ofMinutes(5)), ignored -> new MultiClientRealMediaScenario());
            }
            registrar.register(new ScenarioDescriptor(
                    "ncpb.multi-client-reconnect",
                    "A physical client reconnects and reacquires its formal lease without interrupting its peer",
                    Set.of("client", "multi-client", "console", "lease", "network", "disconnect", "reconnect"),
                    Duration.ofMinutes(2)), ignored -> new MultiClientReconnectClientScenario());
        }
        registrar.register(new ScenarioDescriptor(
                "ncpb.scene-editor-library-smoke",
                "Scene Editor JiJ core and Minecraft adapter load and execute on the integrated client",
                Set.of("client", "editor", "library", "jij"), Duration.ofSeconds(10)),
                ignored -> new SceneEditorLibrarySmokeScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.console-consumer-lifecycle",
                "100 rounds of shared control-console video consumer attach/detach",
                Set.of("client", "console", "lifecycle", "resources"), Duration.ofSeconds(20)),
                ignored -> new ConsoleConsumerLifecycleScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.editor-gui-lifecycle", "30 real editor Screen open/render/snapshot/close rounds",
                Set.of("client", "gui", "editor", "lifecycle"), Duration.ofSeconds(30)),
                ignored -> new EditorGuiLifecycleScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.terrain-lod-roundtrip",
                TerrainLodRoundTripScenario.COMPAT_MATRIX
                        ? "Third-party resource-pack/mod/Iris terrain PIP compatibility matrix"
                        : "Terrain material LOD, frozen tint, block entity and translucent PIP convergence",
                TerrainLodRoundTripScenario.COMPAT_MATRIX
                        ? Set.of("client", "terrain", "lod", "pip", "gpu", "resources", "mods", "iris", "shaderpack")
                        : Set.of("client", "terrain", "lod", "pip", "gpu", "resources"),
                Duration.ofSeconds(TerrainLodRoundTripScenario.COMPAT_MATRIX ? 180 : 60)),
                ignored -> new TerrainLodRoundTripScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.media-resource-convergence", "Video, OpenAL and owned-memory idle convergence",
                Set.of("client", "media", "resources", "close"), Duration.ofSeconds(10)),
                ignored -> new MediaResourceConvergenceScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.deterministic-video-upload",
                "Deterministic RGBA, YUV420P and NV12/PBO upload and release convergence",
                Set.of("client", "media", "gpu", "upload", "resources"), Duration.ofSeconds(20)),
                ignored -> new DeterministicVideoUploadScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.playback-session-races",
                "Deterministic start/seek/pause/resume and retained-session retry replacement",
                Set.of("client", "media", "playback", "session", "race"), Duration.ofSeconds(10)),
                ignored -> new PlaybackSessionRaceScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.indexed-audio-on-demand",
                "Chunk-independent endpoint discovery, no-prewarm admission and shared on-demand decoder restart",
                Set.of("client", "audio", "index", "range", "chunk", "decoder", "lifecycle"),
                Duration.ofSeconds(10)), ignored -> new IndexedAudioOnDemandScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.area-control-audio-boundaries",
                "Real AreaControl parent/child/sibling/wildness isolation with physical, virtual and moving outputs",
                Set.of("client", "server", "audio", "area-control", "compat", "boundary", "fade",
                        "speaker", "console", "mp4", "runtime"), Duration.ofSeconds(45)),
                ignored -> new AreaControlAudioBoundaryScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.indexed-server-session-unloaded",
                "Server-indexed playback survives while the source chunk remains unloaded and unticketed",
                Set.of("client", "server", "audio", "index", "chunk", "session", "lifecycle"),
                Duration.ofSeconds(15)), ignored -> new IndexedServerSessionUnloadScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.playback-range-debug-visualization",
                "Playback range command state, world wireframes and HUD diagnostics render from endpoint snapshots",
                Set.of("client", "audio", "debug", "range", "render", "hud", "command"),
                Duration.ofSeconds(15)), ignored -> new PlaybackRangeDebugVisualizationScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.turntable-block-interactions",
                "Real modern-turntable right-click eject packet and transactional automation extraction",
                Set.of("client", "server", "turntable", "block", "packet", "transfer"), Duration.ofSeconds(20)),
                ignored -> new TurntableBlockInteractionScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.turntable-redstone-system",
                "Real high, low, pulse and ignore modes with automation insertion and comparator output",
                Set.of("client", "server", "turntable", "redstone", "automation", "comparator", "resume"),
                Duration.ofSeconds(45)), ignored -> new TurntableRedstoneSystemScenario());

        registrar.register(new ScenarioDescriptor(
                "ncpb.cross-dimension-media-cleanup",
                "Real respawn packet, loading UI and exact media cleanup across a round-trip dimension change",
                Set.of("client", "server", "dimension", "packet", "gui", "media", "lifecycle"),
                Duration.ofSeconds(60)),
                ignored -> new CrossDimensionMediaCleanupScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.device-link-config-matrix",
                "Real block entities synchronize links and persisted settings for every fixed media device",
                Set.of("client", "server", "device", "link", "config", "projector", "lyrics", "speaker",
                        "console", "live"), Duration.ofSeconds(30)),
                ignored -> new DeviceLinkConfigMatrixScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.wearable-binding-topology",
                "Headphones and holographic glasses bind, route, enforce slot limits and clean media links",
                Set.of("client", "server", "wearable", "headphones", "glasses", "link", "turntable",
                        "mp4", "pad", "projector", "cleanup"), Duration.ofSeconds(30)),
                ignored -> new WearableBindingTopologyScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.gui-screen-matrix",
                "Every offline-safe production media Screen opens, renders, exposes widgets and closes cleanly",
                Set.of("client", "gui", "device", "projector", "lyrics", "speaker", "console", "live",
                        "mp4", "pad", "map"), Duration.ofSeconds(45)),
                ignored -> new GuiScreenMatrixScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.handheld-media-contracts",
                "MP4 and Pad screen geometry, logical sessions and device-independent media contracts",
                Set.of("client", "handheld", "mp4", "pad", "session", "video", "subtitle"),
                Duration.ofSeconds(10)), ignored -> new HandheldMediaContractScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.live-stream-contracts",
                "Bilibili live input, metadata ownership, reconnect backoff and consumer rebind contracts",
                Set.of("client", "live", "bilibili", "metadata", "reconnect", "consumer", "session"),
                Duration.ofSeconds(10)), ignored -> new LiveStreamContractScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.whitelist-management-lifecycle",
                "Whitelist add/remove, live-source denial, review/preview GUI and CSV export without persistent pollution",
                Set.of("client", "server", "whitelist", "live", "deny", "add", "remove", "export",
                        "review", "preview", "gui"), Duration.ofSeconds(45)),
                ignored -> new WhitelistManagementLifecycleScenario());
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
                    "ncpb.real-mp3-seek",
                    "Real MP3 decode-from-head seek, OpenAL replacement and native cleanup convergence",
                    Set.of("client", "media", "network", "mp3", "openal", "seek"), Duration.ofSeconds(180)),
                    ignored -> new RealMp3SeekScenario());
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-mp3-sound-engine",
                    "Real MP3 streaming channels, pause/resume and exact mute/range-stop convergence",
                    Set.of("client", "media", "network", "mp3", "openal", "sound-engine", "channel", "mute", "range"),
                    Duration.ofSeconds(180)),
                    ignored -> new RealMp3SoundEngineScenario());
            if (VideoFeatureProperties.realBenchEnabled()) {
                registrar.register(new ScenarioDescriptor(
                        "ncpb.real-media-channel-recovery",
                        "Twelve pre-stream cancellations preserve the next real MP3 channel and native video session",
                        Set.of("client", "media", "network", "mp3", "sound-engine", "channel", "cancel",
                                "video", "native", "resources"), Duration.ofMinutes(4)),
                        ignored -> new RealMediaChannelRecoveryScenario());
            }
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-mp3-range-reentry",
                    "One indexed session emits real PCM, retires outside range, then emits real PCM again on re-entry",
                    Set.of("client", "server", "media", "network", "mp3", "openal", "sound-engine",
                            "range", "reentry", "decoder", "pcm"), Duration.ofSeconds(240)),
                    ignored -> new RealMp3RangeReentryScenario());
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-turntable-volume-range-reentry",
                    "A real turntable GUI volume drag is preserved while BenchMod moves the player out of range and back",
                    Set.of("client", "server", "turntable", "gui", "slider", "volume", "movement", "network",
                            "mp3", "openal", "sound-engine", "range", "reentry", "pcm"),
                    Duration.ofSeconds(300)),
                    ignored -> new RealTurntableVolumeRangeReentryScenario());
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-mp3-retained-retry",
                    "Retained-session real MP3 refresh, world-unload cleanup and delayed-retry suppression",
                    Set.of("client", "media", "network", "mp3", "retry", "session", "sound-engine", "world"),
                    Duration.ofSeconds(180)),
                    ignored -> new RealMp3RetainedRetryScenario());
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-turntable-mp3-end-to-end",
                    "Real turntable interaction, server resolve, sync packet, MP3/OpenAL output and eject cleanup",
                    Set.of("client", "server", "turntable", "block", "packet", "resolve", "network", "mp3",
                            "sound-engine", "openal"), Duration.ofSeconds(180)),
                    ignored -> new RealTurntableMp3EndToEndScenario());
        }
        if (VideoFeatureProperties.realBenchEnabled()) {
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-bv-playback",
                    "Real Bilibili DASH video/audio resolve, native decode, OpenAL output and resource convergence",
                    Set.of("client", "media", "network", "native", "bilibili", "video", "audio", "openal"),
                    Duration.ofSeconds(180)),
                    ignored -> new RealBvPlaybackScenario());
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-video-range-reentry",
                    "Real Bilibili native video pauses outside render range and resumes the retained session on re-entry",
                    Set.of("client", "server", "media", "network", "native", "bilibili", "video",
                            "projector", "range", "reentry", "decoder"), Duration.ofSeconds(240)),
                    ignored -> new RealVideoRangeReentryScenario());
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
                    ignored -> new RealAv1HardwareSeekScenario(false));
            registrar.register(new ScenarioDescriptor(
                    "ncpb.frozen-real-av1-hardware-seek",
                    "Frozen exact Bilibili AV1 bytes over loopback Range HTTP under the production startup budget",
                    Set.of("client", "media", "network", "native", "bilibili", "fixture", "av1", "hardware",
                            "seek", "pts", "resources"), Duration.ofSeconds(180)),
                    ignored -> new RealAv1HardwareSeekScenario(true));
        }
        if (VideoFeatureProperties.realMediaLifecycle().enabled()) {
            registrar.register(new ScenarioDescriptor(
                    "ncpb.real-media-lifecycle-100",
                    "100 loaded real-Bilibili DASH video/audio last-consumer convergence rounds",
                    Set.of("client", "media", "network", "bilibili", "native", "decoder", "gpu", "pbo",
                            "openal", "lifecycle", "resources"), Duration.ofMinutes(60)),
                    ignored -> new RealMediaLifecycleScenario());
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
