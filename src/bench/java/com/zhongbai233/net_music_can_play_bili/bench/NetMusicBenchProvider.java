package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchApiVersion;
import com.zhongbai233.bench.api.BenchCompatibility;
import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.ScenarioDescriptor;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientProvider;
import com.zhongbai233.bench.api.neoforge.client.BenchClientRegistrar;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.bench.api.neoforge.client.BenchGuiSession;
import com.zhongbai233.bench.api.neoforge.server.BenchServerContext;
import com.zhongbai233.bench.api.neoforge.server.BenchServerProvider;
import com.zhongbai233.bench.api.neoforge.server.BenchServerRegistrar;
import com.zhongbai233.bench.api.neoforge.server.BenchServerScenario;
import com.zhongbai233.bench.api.neoforge.server.BenchStepResult;
import com.github.tartaricacid.netmusic.init.InitItems;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.Config;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.LiveStreamerVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.BiliRealVideoPlaybackBench;
import com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.ClientMediaLifecycleHandler;
import com.zhongbai233.net_music_can_play_bili.client.DeterministicVideoUploadWorkload;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureFlags;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureProperties;
import com.zhongbai233.net_music_can_play_bili.client.WhitelistCsvExportClient;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldMediaProfile;
import com.zhongbai233.net_music_can_play_bili.client.PadHandheldMediaProfile;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntableSound;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaSound;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackSessions;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPrepareLauncher;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPreparePolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryPolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.LiveRoomMetadataRegistry;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainHardRangeBounds;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderState;
import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.TerrainPreviewRenderDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.gui.ControlConsoleGuideScreen;
import com.zhongbai233.net_music_can_play_bili.gui.LiveStreamerScreen;
import com.zhongbai233.net_music_can_play_bili.gui.LyricProjectorScreen;
import com.zhongbai233.net_music_can_play_bili.gui.MP4FocusScreen;
import com.zhongbai233.net_music_can_play_bili.gui.MediaToolBindingScreen;
import com.zhongbai233.net_music_can_play_bili.gui.MediaToolReportScreen;
import com.zhongbai233.net_music_can_play_bili.gui.ModernTurntableScreen;
import com.zhongbai233.net_music_can_play_bili.gui.PadFocusScreen;
import com.zhongbai233.net_music_can_play_bili.gui.PadMapScreen;
import com.zhongbai233.net_music_can_play_bili.gui.SpeakerScreen;
import com.zhongbai233.net_music_can_play_bili.gui.VideoPlaceholderDebugScreen;
import com.zhongbai233.net_music_can_play_bili.gui.VideoProjectorScreen;
import com.zhongbai233.net_music_can_play_bili.gui.WhitelistReviewScreen;
import com.zhongbai233.net_music_can_play_bili.gui.WhitelistPreviewScreen;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData.MediaSource;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveReconnectPolicy;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;
import com.zhongbai233.net_music_can_play_bili.network.PadPlaybackSessionIds;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistPreviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistCsvExportPacket;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolBindingMenu;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolReportMenu;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsoleConsumerLeaseRegistry;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.MediaBindingCleanupService;
import com.zhongbai233.net_music_can_play_bili.server.MediaEquipmentBindingService;
import com.zhongbai233.net_music_can_play_bili.mixin.GuiGraphicsExtractorAccessor;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import com.zhongbai233.scene_editor.core.scene.SceneElement;
import com.zhongbai233.scene_editor.core.session.EditorSession;
import com.zhongbai233.scene_editor.minecraft.SceneEditorMinecraftLibrary;
import com.zhongbai233.scene_editor.minecraft.gui.MinecraftEditorViewport;
import com.zhongbai233.scene_editor.minecraft.input.MinecraftEditorInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import javax.sound.sampled.AudioInputStream;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Test-only integrated-client workloads. This class must never enter the production jar. */
public final class NetMusicBenchProvider implements BenchClientProvider, BenchServerProvider {
    private static final BlockPos MULTI_CLIENT_CONSOLE_POS = new BlockPos(0, 200, 0);
    private static final BlockPos MULTI_CLIENT_SOURCE_POS = new BlockPos(2, 200, 0);
    private static final BenchMetricDescriptor MULTI_CLIENT_PLAYER_COUNT = new BenchMetricDescriptor(
            "ncpb.multi_client.players", "players", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor MULTI_CLIENT_LEASE_COUNT = new BenchMetricDescriptor(
            "ncpb.multi_client.console_leases", "leases", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor MULTI_CLIENT_REAL_MEDIA_LOADED = new BenchMetricDescriptor(
            "ncpb.multi_client.real_media_loaded", "state", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor MULTI_CLIENT_REAL_MEDIA_CONVERGED = new BenchMetricDescriptor(
            "ncpb.multi_client.real_media_converged", "state", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor MULTI_CLIENT_REAL_MEDIA_OWNED_BYTES = new BenchMetricDescriptor(
            "ncpb.multi_client.real_media_owned_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor MULTI_CLIENT_REAL_MEDIA_IRIS = new BenchMetricDescriptor(
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
                "ncpb.turntable-block-interactions",
                "Real modern-turntable right-click eject packet and transactional automation extraction",
                Set.of("client", "server", "turntable", "block", "packet", "transfer"), Duration.ofSeconds(20)),
                ignored -> new TurntableBlockInteractionScenario());
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

    private static final class MultiClientConsumerServerScenario implements BenchServerScenario {
        private final Set<UUID> initialPlayers = new java.util.HashSet<>();
        private UUID survivingPlayer;
        private boolean observedTwoLeases;
        private boolean observedIndependentSurvivor;
        private boolean observedFinalCleanup;
        private int warmupTicks;
        private int measureTicks;
        private int phase;

        @Override
        public void setup(BenchServerContext context) {
            if (context.server().getPlayerList().getPlayerCount() != 2) {
                throw new AssertionError("Expected exactly two paired clients, got "
                        + context.server().getPlayerList().getPlayerCount());
            }
            ServerLevel level = context.level();
            ControlConsoleConsumerLeaseRegistry.clear();
            for (int x = -2; x <= 3; x++) {
                for (int z = -2; z <= 3; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, MULTI_CLIENT_CONSOLE_POS.getY() - 1, z),
                            Blocks.STONE.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(MULTI_CLIENT_SOURCE_POS, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
            level.setBlockAndUpdate(MULTI_CLIENT_CONSOLE_POS, ModBlocks.CONTROL_CONSOLE.get().defaultBlockState());
            if (!(level.getBlockEntity(MULTI_CLIENT_CONSOLE_POS) instanceof ControlConsoleBlockEntity console)) {
                throw new AssertionError("Paired console block entity was not created");
            }
            console.linkTo(level.dimension().identifier().toString(), MULTI_CLIENT_SOURCE_POS);
            int index = 0;
            for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
                initialPlayers.add(player.getUUID());
                double x = MULTI_CLIENT_CONSOLE_POS.getX() + 0.25D + index * 0.5D;
                if (!player.teleportTo(level, x, MULTI_CLIENT_CONSOLE_POS.getY() + 1.0D,
                        MULTI_CLIENT_CONSOLE_POS.getZ() + 2.5D, Set.<Relative>of(), 180.0F, 0.0F, true)) {
                    throw new AssertionError("Could not place paired client " + player.getUUID());
                }
                // The paired suite runs in the void preset.  Rendering-heavy shaderpack
                // frames can stretch several client ticks, so do not let gravity turn a
                // media/lease test into an unrelated fall-damage test.
                player.setNoGravity(true);
                index++;
            }
            if (initialPlayers.size() != 2) {
                throw new AssertionError("Paired clients did not have distinct UUIDs: " + initialPlayers);
            }
        }

        @Override
        public BenchStepResult stabilize(BenchServerContext context) {
            keepPairedPlayersSafe(context);
            Set<UUID> active = activePlayers(context);
            record(context, active.size());
            if (active.size() == 2 && active.equals(initialPlayers)
                    && context.server().getPlayerList().getPlayerCount() == 2) {
                observedTwoLeases = true;
                return BenchStepResult.COMPLETE;
            }
            return BenchStepResult.CONTINUE;
        }

        @Override
        public BenchStepResult warmup(BenchServerContext context) {
            keepPairedPlayersSafe(context);
            Set<UUID> active = activePlayers(context);
            record(context, active.size());
            if (active.size() != 2 || !active.equals(initialPlayers)
                    || context.server().getPlayerList().getPlayerCount() != 2) {
                throw new AssertionError("A paired lease disappeared while both clients were connected: players="
                        + context.server().getPlayerList().getPlayerCount() + ", leases=" + active);
            }
            return ++warmupTicks >= 20 ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
        }

        @Override
        public BenchStepResult measure(BenchServerContext context) {
            keepPairedPlayersSafe(context);
            measureTicks++;
            Set<UUID> active = activePlayers(context);
            int players = context.server().getPlayerList().getPlayerCount();
            record(context, active.size());
            if (phase == 0 && players == 1 && active.size() == 1) {
                survivingPlayer = active.iterator().next();
                if (!initialPlayers.contains(survivingPlayer)) {
                    throw new AssertionError("Unknown lease survived the first disconnect: " + survivingPlayer);
                }
                observedIndependentSurvivor = true;
                phase = 1;
            } else if (phase == 1 && players == 0 && active.isEmpty()) {
                observedFinalCleanup = true;
                return BenchStepResult.COMPLETE;
            }
            if (measureTicks > 600) {
                throw new AssertionError("Paired disconnect lifecycle stalled: phase=" + phase
                        + ", players=" + players + ", leases=" + active + ", survivor=" + survivingPlayer);
            }
            return BenchStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchServerContext context) {
            Set<UUID> active = activePlayers(context);
            if (!observedTwoLeases || !observedIndependentSurvivor || !observedFinalCleanup
                    || context.server().getPlayerList().getPlayerCount() != 0 || !active.isEmpty()) {
                throw new AssertionError("Paired consumer lifecycle did not prove 2 -> 1 -> 0: initial="
                        + observedTwoLeases + ", survivor=" + observedIndependentSurvivor + ", final="
                        + observedFinalCleanup + ", players="
                        + context.server().getPlayerList().getPlayerCount() + ", leases=" + active);
            }
        }

        @Override
        public void teardown(BenchServerContext context) {
            ControlConsoleConsumerLeaseRegistry.clear();
            ServerLevel level = context.level();
            level.setBlockAndUpdate(MULTI_CLIENT_CONSOLE_POS, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(MULTI_CLIENT_SOURCE_POS, Blocks.AIR.defaultBlockState());
            for (int x = -2; x <= 3; x++) {
                for (int z = -2; z <= 3; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, MULTI_CLIENT_CONSOLE_POS.getY() - 1, z),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        private Set<UUID> activePlayers(BenchServerContext context) {
            return ControlConsoleConsumerLeaseRegistry.activePlayers(
                    context.level().dimension().identifier().toString(), MULTI_CLIENT_CONSOLE_POS.asLong(),
                    System.currentTimeMillis());
        }

        private void keepPairedPlayersSafe(BenchServerContext context) {
            ServerLevel level = context.level();
            int index = 0;
            for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
                if (!initialPlayers.contains(player.getUUID())) {
                    continue;
                }
                player.setNoGravity(true);
                player.setDeltaMovement(Vec3.ZERO);
                player.resetFallDistance();
                if (player.getY() < MULTI_CLIENT_CONSOLE_POS.getY()
                        || player.position().distanceTo(Vec3.atCenterOf(MULTI_CLIENT_CONSOLE_POS)) > 8.0D) {
                    double x = MULTI_CLIENT_CONSOLE_POS.getX() + 0.25D + index * 0.5D;
                    if (!player.teleportTo(level, x, MULTI_CLIENT_CONSOLE_POS.getY() + 1.0D,
                            MULTI_CLIENT_CONSOLE_POS.getZ() + 2.5D, Set.<Relative>of(), 180.0F, 0.0F, true)) {
                        throw new AssertionError("Could not restore paired client " + player.getUUID());
                    }
                }
                index++;
            }
        }

        private void record(BenchServerContext context, int leases) {
            context.metrics().record(MULTI_CLIENT_PLAYER_COUNT,
                    context.server().getPlayerList().getPlayerCount());
            context.metrics().record(MULTI_CLIENT_LEASE_COUNT, leases);
        }
    }

    private static final class MultiClientConsumerClientScenario implements BenchClientScenario {
        private final int clientIndex = Integer.getInteger("modBench.paired.clientIndex", -1);
        private final int clientCount = Integer.getInteger("modBench.paired.clientCount", -1);
        private boolean observedBothPlayers;
        private boolean observedPeerDisconnect;
        private int leaseObservations;
        private int measureTicks;
        private int loneTicks;

        @Override
        public void setup(BenchClientContext context) {
            if (clientCount != 2 || clientIndex < 0 || clientIndex >= clientCount) {
                throw new AssertionError("Invalid paired client role " + clientIndex + "/" + clientCount);
            }
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            if (!(context.level().getBlockEntity(MULTI_CLIENT_CONSOLE_POS) instanceof ControlConsoleBlockEntity)
                    || context.minecraft().player == null
                    || context.minecraft().player.position().distanceTo(
                            Vec3.atCenterOf(MULTI_CLIENT_CONSOLE_POS)) > 8.0D) {
                return BenchClientStepResult.CONTINUE;
            }
            return ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS).registered()
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            int online = onlinePlayers(context);
            observedBothPlayers |= online == 2;
            var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
            if (lease.active() && lease.leasePresent()) {
                leaseObservations++;
            }
            return observedBothPlayers && leaseObservations >= 5
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            measureTicks++;
            int online = onlinePlayers(context);
            observedBothPlayers |= online == 2;
            var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
            if (lease.active() && lease.leasePresent()) {
                leaseObservations++;
            }
            if (clientIndex == 0) {
                if (observedBothPlayers && measureTicks >= 80) {
                    return BenchClientStepResult.COMPLETE;
                }
            } else if (observedBothPlayers && online == 1) {
                observedPeerDisconnect = true;
                if (++loneTicks >= 60) {
                    return BenchClientStepResult.COMPLETE;
                }
            }
            if (measureTicks > 600) {
                throw new AssertionError("Paired client stalled: index=" + clientIndex + ", online=" + online
                        + ", leaseObservations=" + leaseObservations + ", observedBoth=" + observedBothPlayers
                        + ", observedPeerExit=" + observedPeerDisconnect);
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (!observedBothPlayers || leaseObservations < 5
                    || (clientIndex == 1 && !observedPeerDisconnect)) {
                throw new AssertionError("Paired client did not observe its ownership lifecycle: index="
                        + clientIndex + ", leaseObservations=" + leaseObservations + ", both=" + observedBothPlayers
                        + ", peerExit=" + observedPeerDisconnect);
            }
        }

        private int onlinePlayers(BenchClientContext context) {
            return context.minecraft().getConnection() == null ? 0
                    : context.minecraft().getConnection().getOnlinePlayers().size();
        }
    }

    private static final class MultiClientReconnectServerScenario implements BenchServerScenario {
        private final Set<UUID> initialPlayers = new java.util.HashSet<>();
        private UUID reconnectingPlayer;
        private int stableBeforeDisconnect;
        private int stableAfterReconnect;
        private int measureTicks;
        private boolean observedDisconnect;
        private boolean observedSameIdentityReconnect;

        @Override
        public void setup(BenchServerContext context) {
            if (context.server().getPlayerList().getPlayerCount() != 2) {
                throw new AssertionError("Expected two clients for reconnect, got "
                        + context.server().getPlayerList().getPlayerCount());
            }
            ServerLevel level = context.level();
            ControlConsoleConsumerLeaseRegistry.clear();
            for (int x = -2; x <= 3; x++) {
                for (int z = -2; z <= 3; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, MULTI_CLIENT_CONSOLE_POS.getY() - 1, z),
                            Blocks.STONE.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(MULTI_CLIENT_SOURCE_POS, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
            level.setBlockAndUpdate(MULTI_CLIENT_CONSOLE_POS, ModBlocks.CONTROL_CONSOLE.get().defaultBlockState());
            if (!(level.getBlockEntity(MULTI_CLIENT_CONSOLE_POS) instanceof ControlConsoleBlockEntity console)) {
                throw new AssertionError("Reconnect console block entity was not created");
            }
            console.linkTo(level.dimension().identifier().toString(), MULTI_CLIENT_SOURCE_POS);
            int index = 0;
            for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
                initialPlayers.add(player.getUUID());
                if (player.getGameProfile().name().equals("ModBenchClient0")) {
                    reconnectingPlayer = player.getUUID();
                }
                if (!player.teleportTo(level, MULTI_CLIENT_CONSOLE_POS.getX() + 0.25D + index++ * 0.5D,
                        MULTI_CLIENT_CONSOLE_POS.getY() + 1.0D, MULTI_CLIENT_CONSOLE_POS.getZ() + 2.5D,
                        Set.<Relative>of(), 180.0F, 0.0F, true)) {
                    throw new AssertionError("Could not place reconnect client " + player.getUUID());
                }
            }
            if (initialPlayers.size() != 2 || reconnectingPlayer == null) {
                throw new AssertionError("Reconnect fixture did not identify both stable clients: "
                        + initialPlayers + ", reconnecting=" + reconnectingPlayer);
            }
        }

        @Override
        public BenchStepResult stabilize(BenchServerContext context) {
            Set<UUID> leases = activePlayers(context);
            record(context, leases.size());
            return leases.equals(initialPlayers) && context.server().getPlayerList().getPlayerCount() == 2
                    ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
        }

        @Override
        public BenchStepResult warmup(BenchServerContext context) {
            Set<UUID> leases = activePlayers(context);
            record(context, leases.size());
            if (!leases.equals(initialPlayers) || context.server().getPlayerList().getPlayerCount() != 2) {
                stableBeforeDisconnect = 0;
                return BenchStepResult.CONTINUE;
            }
            return ++stableBeforeDisconnect >= 20 ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
        }

        @Override
        public BenchStepResult measure(BenchServerContext context) {
            measureTicks++;
            Set<UUID> online = context.server().getPlayerList().getPlayers().stream()
                    .map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toSet());
            Set<UUID> leases = activePlayers(context);
            record(context, leases.size());
            if (!observedDisconnect && online.size() == 1 && !online.contains(reconnectingPlayer)
                    && leases.equals(online)) {
                observedDisconnect = true;
            } else if (observedDisconnect && online.equals(initialPlayers) && leases.equals(initialPlayers)) {
                observedSameIdentityReconnect = true;
                if (++stableAfterReconnect >= 20) {
                    return BenchStepResult.COMPLETE;
                }
            } else if (observedSameIdentityReconnect) {
                stableAfterReconnect = 0;
            }
            if (measureTicks > 600) {
                throw new AssertionError("Reconnect lifecycle stalled: online=" + online + ", leases=" + leases
                        + ", missingObserved=" + observedDisconnect + ", rejoined="
                        + observedSameIdentityReconnect);
            }
            return BenchStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchServerContext context) {
            if (!observedDisconnect || !observedSameIdentityReconnect || stableAfterReconnect < 20) {
                throw new AssertionError("Server did not prove same-identity reconnect and lease reacquisition");
            }
        }

        @Override
        public void teardown(BenchServerContext context) {
            // A paired server remains alive after writing its report while both clients finish their
            // independent proof windows. Clearing leases or removing the platform here would alter
            // their workload (and can kill the players). The paired coordinator terminates this
            // temporary server after all three reports exist; its run directory is recreated next run.
        }

        private Set<UUID> activePlayers(BenchServerContext context) {
            return ControlConsoleConsumerLeaseRegistry.activePlayers(
                    context.level().dimension().identifier().toString(), MULTI_CLIENT_CONSOLE_POS.asLong(),
                    System.currentTimeMillis());
        }

        private void record(BenchServerContext context, int leases) {
            context.metrics().record(MULTI_CLIENT_PLAYER_COUNT,
                    context.server().getPlayerList().getPlayerCount());
            context.metrics().record(MULTI_CLIENT_LEASE_COUNT, leases);
        }
    }

    private static final class MultiClientReconnectClientScenario implements BenchClientScenario {
        private static final int CLIENT_RECONNECT_STABLE_TICKS = 60;
        private static final int SERVER_PROOF_STABLE_TICKS = 20;
        private final int clientIndex = Integer.getInteger("modBench.paired.clientIndex", -1);
        private final int clientCount = Integer.getInteger("modBench.paired.clientCount", -1);
        private UUID originalPlayer;
        private int leaseObservations;
        private int measureTicks;
        private int stableAfterReconnect;
        private boolean disconnectRequested;
        private boolean observedPeerDisconnect;
        private boolean observedReconnect;
        private boolean observedPeerCompletionExit;

        @Override
        public void setup(BenchClientContext context) {
            if (clientCount != 2 || clientIndex < 0 || clientIndex >= clientCount) {
                throw new AssertionError("Invalid reconnect client role " + clientIndex + '/' + clientCount);
            }
            originalPlayer = context.player().getUUID();
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            if (!(context.level().getBlockEntity(MULTI_CLIENT_CONSOLE_POS) instanceof ControlConsoleBlockEntity)
                    || context.player().position().distanceTo(Vec3.atCenterOf(MULTI_CLIENT_CONSOLE_POS)) > 8.0D) {
                return BenchClientStepResult.CONTINUE;
            }
            return ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS).registered()
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
            if (onlinePlayers(context) == 2 && lease.active() && lease.leasePresent()) {
                leaseObservations++;
            }
            return leaseObservations >= 10 ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            measureTicks++;
            int online = onlinePlayers(context);
            var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
            if (clientIndex == 0 && !disconnectRequested && measureTicks >= 40) {
                disconnectRequested = true;
                context.minecraft().getConnection().getConnection().disconnect(
                        Component.literal("ModBench planned reconnect"));
                return BenchClientStepResult.CONTINUE;
            }
            if (clientIndex == 1 && online == 1) {
                observedPeerDisconnect = true;
                if (!lease.active() || !lease.leasePresent()) {
                    throw new AssertionError("Peer disconnect invalidated surviving formal lease");
                }
            }
            if ((clientIndex == 0 && disconnectRequested || clientIndex == 1 && observedPeerDisconnect)
                    && online == 2 && context.player().getUUID().equals(originalPlayer)
                    && lease.active() && lease.leasePresent()) {
                observedReconnect = true;
                if (++stableAfterReconnect >= CLIENT_RECONNECT_STABLE_TICKS) {
                    return BenchClientStepResult.COMPLETE;
                }
            }
            if (observedReconnect && online == 1
                    && stableAfterReconnect >= SERVER_PROOF_STABLE_TICKS
                    && lease.active() && lease.leasePresent()) {
                // The peer may finish one render tick earlier. It waited three times the server's
                // proof window before exiting, so the remaining client can now close independently.
                observedPeerCompletionExit = true;
                return BenchClientStepResult.COMPLETE;
            }
            if (measureTicks > 600) {
                throw new AssertionError("Reconnect client stalled: index=" + clientIndex + ", online=" + online
                        + ", disconnect=" + disconnectRequested + ", peerDisconnect=" + observedPeerDisconnect
                        + ", reconnect=" + observedReconnect + ", lease=" + lease);
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (!observedReconnect || stableAfterReconnect < SERVER_PROOF_STABLE_TICKS
                    || stableAfterReconnect < CLIENT_RECONNECT_STABLE_TICKS && !observedPeerCompletionExit
                    || clientIndex == 0 && !disconnectRequested
                    || clientIndex == 1 && !observedPeerDisconnect) {
                throw new AssertionError("Client did not prove planned reconnect: index=" + clientIndex
                        + ", disconnect=" + disconnectRequested + ", peerDisconnect=" + observedPeerDisconnect
                        + ", reconnect=" + observedReconnect + ", stable=" + stableAfterReconnect
                        + ", peerCompletion=" + observedPeerCompletionExit);
            }
        }

        private int onlinePlayers(BenchClientContext context) {
            return context.minecraft().getConnection() == null ? 0
                    : context.minecraft().getConnection().getOnlinePlayers().size();
        }
    }

    /**
     * Physical two-client system gate with real Bilibili video/audio load. Client zero deliberately completes
     * while both native media stages are still active; its scenario teardown initiates normal client-owned
     * cleanup and the process exits. Client one must observe that disconnect while its own decoder, GPU upload,
     * OpenAL output and formal console lease remain alive, then close independently and return every tracked
     * resource to its pre-load baseline.
     */
    private static final class MultiClientRealMediaScenario implements BenchClientScenario {
        private static final int LOADED_HOLD_TICKS = 40;
        private static final int SURVIVOR_HOLD_TICKS = 60;
        private final int clientIndex = Integer.getInteger("modBench.paired.clientIndex", -1);
        private final int clientCount = Integer.getInteger("modBench.paired.clientCount", -1);
        private final boolean irisExpected = Boolean.getBoolean("ncpb.terrain.compat_matrix");
        private final VideoFeatureProperties.RealMediaLifecycle properties =
                VideoFeatureProperties.realMediaLifecycle();
        private final AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
        private final AtomicBoolean stopIssued = new AtomicBoolean();
        private CompletableFuture<PairedResolvedMedia> resolution;
        private PairedResolvedMedia media;
        private UUID audioOwner;
        private PlaybackSessionId mediaSession;
        private RealMediaLifecycleScenario.RealVideoStage video;
        private RealMediaLifecycleScenario.RealAudioStage audio;
        private long[] memoryBaseline;
        private VideoBillboardPreview.BenchUploadResources uploadBaseline;
        private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
        private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
        private int videoCloseBaseline;
        private int audioCloseBaseline;
        private int pendingNativeDeleteBaseline;
        private int loadedTicks;
        private int survivorTicks;
        private int measureTicks;
        private boolean baselineCaptured;
        private boolean mediaStarted;
        private boolean mediaLoaded;
        private boolean observedBothPlayers;
        private boolean observedPeerExit;
        private boolean survivorStayedLoaded;
        private boolean converged;
        private boolean irisObserved;

        @Override
        public void setup(BenchClientContext context) {
            if (clientCount != 2 || clientIndex < 0 || clientIndex >= clientCount) {
                throw new AssertionError("Invalid paired real-media role " + clientIndex + '/' + clientCount);
            }
            cleanupGlobalResources();
            audioOwner = context.player().getUUID();
            ClientAudioOutputRegistry.setOwnerVolume(audioOwner, 1.0F);
            mediaSession = PlaybackSessionId.of("bench-paired-real-media-" + clientIndex);
            resolution = CompletableFuture.supplyAsync(this::resolveMedia)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            resolutionFailure.compareAndSet(null,
                                    RealMediaLifecycleScenario.unwrapCompletion(error));
                        }
                    });
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            tickResourceClosures(context);
            throwResolutionFailure();
            if (!context.environment().readiness().ready() || context.frames().sampleCount() < 2
                    || !(context.level().getBlockEntity(MULTI_CLIENT_CONSOLE_POS)
                            instanceof ControlConsoleBlockEntity)
                    || context.player().position().distanceTo(Vec3.atCenterOf(MULTI_CLIENT_CONSOLE_POS)) > 8.0D
                    || !ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS).registered()
                    || resolution == null || !resolution.isDone()) {
                return BenchClientStepResult.CONTINUE;
            }
            if (media == null) {
                media = resolution.join();
            }
            if (!idleForBaseline()) {
                return BenchClientStepResult.CONTINUE;
            }
            captureBaseline();
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            tickResourceClosures(context);
            throwResolutionFailure();
            if (!mediaStarted) {
                startMedia();
            }
            pollLoadedMedia(context);
            if (!mediaLoaded) {
                return BenchClientStepResult.CONTINUE;
            }
            observedBothPlayers |= onlinePlayers(context) == 2;
            return observedBothPlayers && ++loadedTicks >= LOADED_HOLD_TICKS
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            tickResourceClosures(context);
            throwStageFailures();
            recordMetrics(context);
            measureTicks++;
            int online = onlinePlayers(context);
            observedBothPlayers |= online == 2;
            var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
            if (clientIndex == 0) {
                if (!stopIssued.get() && (!lease.active() || !lease.leasePresent() || !mediaStillLoaded())) {
                    throw new AssertionError("Departing real-media client lost its live resources before exit: "
                            + describeResources(context));
                }
                if (observedBothPlayers && measureTicks >= 80 && !stopIssued.get()) {
                    stopMedia();
                }
                if (stopIssued.get() && resourcesAtBaseline()) {
                    converged = true;
                    recordMetrics(context);
                    return BenchClientStepResult.COMPLETE;
                }
                return BenchClientStepResult.CONTINUE;
            }

            if (!observedPeerExit && observedBothPlayers && online == 1) {
                observedPeerExit = true;
            }
            if (observedPeerExit && !stopIssued.get()) {
                if (!lease.active() || !lease.leasePresent() || !mediaStillLoaded()) {
                    throw new AssertionError("Surviving client lost media or its lease after peer exit: "
                            + describeResources(context));
                }
                survivorStayedLoaded = true;
                if (++survivorTicks >= SURVIVOR_HOLD_TICKS) {
                    stopMedia();
                }
            }
            if (stopIssued.get() && resourcesAtBaseline()) {
                converged = true;
                recordMetrics(context);
                return BenchClientStepResult.COMPLETE;
            }
            if (measureTicks > properties.cycleTimeoutTicks() * 2) {
                throw new AssertionError("Paired real-media lifecycle stalled: " + describeResources(context));
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (!baselineCaptured || !mediaLoaded || !observedBothPlayers
                    || irisExpected && !irisObserved) {
                throw new AssertionError("Paired real-media client did not reach its loaded gate: "
                        + describeResources(context));
            }
            if (!converged || video == null || !video.terminatedNormally()
                    || audio == null || !audio.streamClosed()
                    || clientIndex == 1 && (!observedPeerExit || !survivorStayedLoaded)) {
                throw new AssertionError("Surviving real-media client did not converge independently: "
                        + describeResources(context));
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            stopMedia();
            cleanupGlobalResources();
        }

        private PairedResolvedMedia resolveMedia() {
            try {
                BiliApiClient.VideoId videoId = BiliApiClient.extractVideoId(properties.videoId());
                if (videoId == null) {
                    throw new IOException("invalid Bilibili video id: " + properties.videoId());
                }
                BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(videoId);
                BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(
                        videoId, info.cid(), properties.quality());
                BiliApiClient.VideoStream videoStream = !plan.h264Candidates().isEmpty()
                        ? plan.h264Candidates().getFirst() : plan.preferred();
                String audioUrl = BiliApiClient.getBestAudioUrl(videoId, info.cid(), false);
                if (videoStream.baseUrl().isBlank() || audioUrl == null || audioUrl.isBlank()) {
                    throw new IOException("Bilibili playurl returned an empty paired media URL");
                }
                return new PairedResolvedMedia(Math.max(1L, info.duration() * 1_000L), videoStream, audioUrl);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }

        private void captureBaseline() {
            memoryBaseline = RealMediaLifecycleScenario.currentOwnedBytesByCategory();
            uploadBaseline = VideoBillboardPreview.benchUploadResources();
            stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
            tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
            videoCloseBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
            audioCloseBaseline = AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
            pendingNativeDeleteBaseline = OpenALSpatialAudio.pendingNativeDeleteBatches();
            if (uploadBaseline.rgbaTexture() || uploadBaseline.yuvTextures()
                    || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0
                    || videoCloseBaseline != 0 || audioCloseBaseline != 0 || pendingNativeDeleteBaseline != 0) {
                throw new AssertionError("Paired real-media baseline is not idle: " + describeResources(null));
            }
            baselineCaptured = true;
        }

        private void startMedia() {
            video = RealMediaLifecycleScenario.RealVideoStage.start(
                    media.videoStream(), memoryBaseline, uploadBaseline.gpuPboBytes());
            audio = RealMediaLifecycleScenario.RealAudioStage.start(
                    media.audioUrl(), audioOwner, mediaSession, media.durationMillis());
            mediaStarted = true;
        }

        private void pollLoadedMedia(BenchClientContext context) {
            throwStageFailures();
            StereoOpenALHandler.DiagnosticSnapshot output =
                    ClientAudioOutputRegistry.getSessionStereoSnapshot(mediaSession).orElse(null);
            boolean audioReady = output != null && output.started()
                    && output.firstAudiblePcm().samples() >= 1_024L && output.inputSamples() > 0L;
            if (irisExpected && IrisShaderpackCompat.isShaderPackInUse()) {
                irisObserved = true;
            }
            if (!video.loaded() || !audioReady || irisExpected && !irisObserved) {
                return;
            }
            requirePcmQuality("paired real Bilibili client " + clientIndex, output.firstAudiblePcm());
            if (!video.directFrame() || video.frameBytes() <= 0L || !video.yuvTextureObserved()
                    || !video.decoderNv12Observed() || !video.pboObserved()) {
                throw new AssertionError("Paired real video did not exercise direct NV12 texture/PBO: " + video);
            }
            var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
            if (!lease.active() || !lease.leasePresent() || onlinePlayers(context) != 2) {
                return;
            }
            mediaLoaded = true;
        }

        private boolean mediaStillLoaded() {
            StereoOpenALHandler.DiagnosticSnapshot output =
                    ClientAudioOutputRegistry.getSessionStereoSnapshot(mediaSession).orElse(null);
            return video != null && video.loaded() && !video.finished()
                    && output != null && output.started() && output.inputSamples() > 0L;
        }

        private void stopMedia() {
            if (!stopIssued.compareAndSet(false, true)) {
                return;
            }
            if (video != null) {
                video.stop();
            }
            if (audio != null) {
                audio.stop();
            }
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
            VideoBillboardPreview.releaseBenchUploadResources();
        }

        private void tickResourceClosures(BenchClientContext context) {
            ClientAudioOutputRegistry.updatePositions(new float[] {
                    (float) context.player().getX(), (float) context.player().getEyeY(),
                    (float) context.player().getZ()
            });
            OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
            VideoCloseDiagnostics.tickGlobal();
        }

        private void recordMetrics(BenchClientContext context) {
            context.metrics().record(MULTI_CLIENT_REAL_MEDIA_LOADED, mediaLoaded ? 1L : 0L);
            context.metrics().record(MULTI_CLIENT_REAL_MEDIA_CONVERGED, converged ? 1L : 0L);
            context.metrics().record(MULTI_CLIENT_REAL_MEDIA_OWNED_BYTES,
                    RealMediaLifecycleScenario.currentOwnedBytes());
            context.metrics().record(MULTI_CLIENT_REAL_MEDIA_IRIS, irisObserved ? 1L : 0L);
        }

        private boolean idleForBaseline() {
            return !ClientAudioOutputRegistry.isActive()
                    && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() == 0
                    && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                    && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                    && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                    && !VideoBillboardPreview.benchUploadResources().rgbaTexture()
                    && !VideoBillboardPreview.benchUploadResources().yuvTextures();
        }

        private boolean resourcesAtBaseline() {
            if (video == null || audio == null || !video.finished() || !audio.finished()) {
                return false;
            }
            VideoBillboardPreview.BenchUploadResources upload = VideoBillboardPreview.benchUploadResources();
            return !ClientAudioOutputRegistry.isActive()
                    && StereoOpenALHandler.lifecycleSnapshot().activeInstances() == stereoBaseline.activeInstances()
                    && OpenALTappedAudioInputStream.lifecycleSnapshot().activeInstances()
                            == tapBaseline.activeInstances()
                    && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() == 0
                    && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations()
                            == videoCloseBaseline
                    && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations()
                            == audioCloseBaseline
                    && OpenALSpatialAudio.pendingNativeDeleteBatches() == pendingNativeDeleteBaseline
                    && upload.rgbaTexture() == uploadBaseline.rgbaTexture()
                    && upload.yuvTextures() == uploadBaseline.yuvTextures()
                    && upload.textureStagingBytes() == uploadBaseline.textureStagingBytes()
                    && upload.gpuPboBytes() == uploadBaseline.gpuPboBytes()
                    && Arrays.equals(memoryBaseline,
                            RealMediaLifecycleScenario.currentOwnedBytesByCategory());
        }

        private void cleanupGlobalResources() {
            ModernTurntableVideoClient.clear();
            VideoBillboardPreview.stop();
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
            VideoBillboardPreview.releaseBenchUploadResources();
            OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
        }

        private void throwResolutionFailure() {
            Throwable error = resolutionFailure.get();
            if (error != null) {
                throw new AssertionError("Failed to resolve paired real Bilibili media", error);
            }
        }

        private void throwStageFailures() {
            if (video != null) {
                video.throwIfFailed();
            }
            if (audio != null) {
                audio.throwIfFailed();
            }
        }

        private int onlinePlayers(BenchClientContext context) {
            return context.minecraft().getConnection() == null ? 0
                    : context.minecraft().getConnection().getOnlinePlayers().size();
        }

        private String describeResources(BenchClientContext context) {
            return "index=" + clientIndex + " online=" + (context != null ? onlinePlayers(context) : -1)
                    + " loaded=" + mediaLoaded + " peerExit=" + observedPeerExit
                    + " survivorLoaded=" + survivorStayedLoaded + " stop=" + stopIssued
                    + " converged=" + converged + " iris=" + irisObserved + '/' + irisExpected
                    + " video=" + video + " audio=" + audio
                    + " upload=" + VideoBillboardPreview.benchUploadResources()
                    + " http=" + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime())
                    + " videoClose=" + VideoCloseDiagnostics.global().snapshot(System.nanoTime())
                    + " audioClose=" + AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime())
                    + " stereo=" + StereoOpenALHandler.lifecycleSnapshot()
                    + " tap=" + OpenALTappedAudioInputStream.lifecycleSnapshot()
                    + " pendingNative=" + OpenALSpatialAudio.pendingNativeDeleteBatches()
                    + " memory=" + Arrays.toString(RealMediaLifecycleScenario.currentOwnedBytesByCategory());
        }

        private record PairedResolvedMedia(long durationMillis,
                BiliApiClient.VideoStream videoStream, String audioUrl) {
        }
    }

    private static final class SceneEditorLibrarySmokeScenario implements BenchClientScenario {
        private boolean verified;

        @Override
        public void setup(BenchClientContext context) {
            verified = false;
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
            if (!"com.github.zhongbai2333.SceneEditor".equals(SceneEditorMinecraftLibrary.GROUP)
                    || !"scene-editor-minecraft".equals(SceneEditorMinecraftLibrary.ARTIFACT)
                    || SceneEditorMinecraftLibrary.API_MAJOR != 1) {
                throw new AssertionError("Scene Editor Minecraft adapter identity mismatch");
            }
            var viewport = MinecraftEditorViewport.fullWindow(context.minecraft());
            if (viewport.width() != Math.max(1, context.minecraft().getWindow().getGuiScaledWidth())
                    || viewport.height() != Math.max(1, context.minecraft().getWindow().getGuiScaledHeight())) {
                throw new AssertionError("Minecraft viewport adapter did not preserve the scaled client window");
            }
            if (MinecraftEditorInput.standardView(GLFW.GLFW_KEY_1).orElseThrow() != StandardCameraView.FRONT) {
                throw new AssertionError("Minecraft input adapter did not expose the core standard view");
            }

            UUID id = UUID.fromString("00000000-0000-0000-0000-000000000057");
            SceneElement element = new ClientProbeElement(id, "client:probe", EditorTransform.identity());
            SceneDocument<SceneElement> document = new SceneDocument<>(List.of(element));
            EditorCameraState camera = EditorCameraState.lookingAt(EditorCameraMode.ORBIT,
                    new Vector3d(0.0D, 0.0D, 5.0D), new Vector3d(), new Vector3d(0.0D, 1.0D, 0.0D),
                    60.0F, 4.0F, 0.05F, 100.0F);
            try (EditorSession<SceneElement> session = EditorSession.open(document, camera, viewport, 16)) {
                session.select(id);
                if (!session.selectedElementId().orElseThrow().equals(id)
                        || session.document().element(id).orElseThrow() != element) {
                    throw new AssertionError("Scene Editor core session failed on the integrated client");
                }
            }
            verified = true;
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (!verified) {
                throw new AssertionError("Scene Editor client smoke did not execute");
            }
        }

        private record ClientProbeElement(UUID id, String typeId, EditorTransform transform)
                implements SceneElement {
        }
    }

    private static final class RealTurntableMp3EndToEndScenario implements BenchClientScenario {
        private static final int MAX_PHASE_TICKS = 600;
        private static final BenchMetricDescriptor CHANNEL_STARTS = new BenchMetricDescriptor(
                "ncpb.real_turntable_mp3.channel_starts", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor AUDIBLE_MILLIS = new BenchMetricDescriptor(
                "ncpb.real_turntable_mp3.audible_millis", "milliseconds", MetricDirection.NEUTRAL);

        private final AudioStreamProperties.RealMp3Bench properties = AudioStreamProperties.realMp3Bench();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean serverTaskPending = new AtomicBoolean();
        private final AtomicBoolean setupComplete = new AtomicBoolean();
        private final AtomicBoolean handReady = new AtomicBoolean();
        private final AtomicBoolean serverPlaybackObserved = new AtomicBoolean();
        private final AtomicBoolean serverEjectObserved = new AtomicBoolean();
        private final AtomicReference<String> serverSession = new AtomicReference<>("");
        private final AtomicReference<ModernTurntableSound> sound = new AtomicReference<>();
        private final AtomicInteger streamingChannelStarts = new AtomicInteger();
        private final AtomicReference<String> lastObservation = new AtomicReference<>("not started");
        private final Consumer<PlayStreamingSourceEvent> streamingListener = event -> {
            if (event.getSound() instanceof ModernTurntableSound modernSound) {
                sound.compareAndSet(null, modernSound);
                streamingChannelStarts.incrementAndGet();
            }
        };

        private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
        private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
        private StereoOpenALHandler.PcmQuality pcm = new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
        private long audioStagingBaseline;
        private BlockPos turntablePos;
        private ItemStack pendingHand = ItemStack.EMPTY;
        private UUID playerId;
        private boolean listenerRegistered;
        private boolean converged;
        private int phase;
        private int phaseTicks;

        @Override
        public void setup(BenchClientContext context) {
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
            ModernTurntablePlaybackTracker.stopAllSounds();
            playerId = context.player().getUUID();
            turntablePos = context.player().blockPosition().offset(2, 0, 2).immutable();
            tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
            stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
            audioStagingBaseline = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING)
                    .currentBytes();
            NeoForge.EVENT_BUS.addListener(PlayStreamingSourceEvent.class, streamingListener);
            listenerRegistered = true;
            submitServer(context, (level, player) -> {
                level.setBlockAndUpdate(turntablePos, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
                turntable(level).setVolumePerMille(1_000);
                setupComplete.set(true);
            });
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            throwIfFailed();
            return setupComplete.get() && clientTurntable(context) != null
                    && context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            throwIfFailed();
            phaseTicks++;
            ClientAudioOutputRegistry.updatePositions(new float[] {
                    (float) context.player().getX(), (float) context.player().getEyeY(),
                    (float) context.player().getZ()
            });
            OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
            StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry
                    .getStereoSnapshot(turntablePos).orElse(null);
            if (output != null && output.firstPcm().samples() > 0L) {
                pcm = output.firstPcm();
            }
            context.metrics().record(CHANNEL_STARTS, streamingChannelStarts.get());
            context.metrics().record(AUDIBLE_MILLIS, output != null ? output.positionMillis() : -1L);

            switch (phase) {
                case 0 -> {
                    if (!serverTaskPending.get()) {
                        prepareHand(context, realDisc());
                        advanceTo(1, "waiting for real MP3 disc hand sync");
                    }
                }
                case 1 -> {
                    if (handReady.get()) {
                        interact(context);
                        advanceTo(2, "real MP3 use-item-on packet sent");
                    }
                }
                case 2 -> {
                    probePlayback(context);
                    ModernTurntableSound activeSound = sound.get();
                    String expectedSession = serverSession.get();
                    if (serverPlaybackObserved.get() && activeSound != null && output != null && output.started()
                            && output.firstPcm().samples() >= 1_024L
                            && streamingChannelStarts.get() == 1
                            && context.minecraft().getSoundManager().isActive(activeSound)
                            && !expectedSession.isBlank()
                            && activeSound.playbackSession().filter(id -> expectedSession.equals(id.value())).isPresent()
                            && ModernTurntablePlaybackTracker.isActiveSession(turntablePos, expectedSession)) {
                        requirePcmQuality("real turntable end-to-end", output.firstPcm());
                        pcm = output.firstPcm();
                        prepareHand(context, new ItemStack(Items.STICK));
                        advanceTo(3, "waiting for eject hand sync");
                    }
                }
                case 3 -> {
                    if (handReady.get()) {
                        interact(context);
                        advanceTo(4, "real MP3 eject packet sent");
                    }
                }
                case 4 -> {
                    probeEject(context);
                    if (serverEjectObserved.get() && clientStopped(context) && resourcesConverged(context)) {
                        converged = true;
                        return BenchClientStepResult.COMPLETE;
                    }
                }
                default -> throw new AssertionError("Unexpected real turntable MP3 phase " + phase);
            }
            if (phaseTicks > MAX_PHASE_TICKS) {
                throw new AssertionError("Real turntable MP3 end-to-end stalled in phase " + phase + ": "
                        + lastObservation.get() + ", channels=" + streamingChannelStarts + ", output=" + output
                        + ", tap=" + OpenALTappedAudioInputStream.lifecycleSnapshot() + ", stereo="
                        + StereoOpenALHandler.lifecycleSnapshot());
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfFailed();
            requirePcmQuality("real turntable end-to-end", pcm);
            OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
            StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
            if (!converged || !serverPlaybackObserved.get() || !serverEjectObserved.get()
                    || streamingChannelStarts.get() != 1 || !resourcesConverged(context)
                    || tap.instancesCreated() != tapBaseline.instancesCreated() + 1L
                    || tap.closesCompleted() != tapBaseline.closesCompleted() + 1L
                    || stereo.instancesCreated() != stereoBaseline.instancesCreated() + 1L
                    || stereo.cleanupsStarted() != stereoBaseline.cleanupsStarted() + 1L
                    || stereo.cleanupsCompleted() != stereoBaseline.cleanupsCompleted() + 1L) {
                throw new AssertionError("Real turntable MP3 end-to-end did not converge exactly: channels="
                        + streamingChannelStarts + ", session=" + serverSession + ", tapBaseline=" + tapBaseline
                        + ", tap=" + tap + ", stereoBaseline=" + stereoBaseline + ", stereo=" + stereo);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            context.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            ModernTurntablePlaybackTracker.stopAllSounds();
            if (listenerRegistered) {
                NeoForge.EVENT_BUS.unregister(streamingListener);
                listenerRegistered = false;
            }
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
            var server = context.minecraft().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null && player.level() instanceof ServerLevel level) {
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        level.getEntitiesOfClass(ItemEntity.class, new AABB(turntablePos).inflate(3.0D))
                                .forEach(ItemEntity::discard);
                        level.setBlockAndUpdate(turntablePos, Blocks.AIR.defaultBlockState());
                    }
                });
            }
        }

        private void probePlayback(BenchClientContext context) {
            if (serverPlaybackObserved.get() || serverTaskPending.get()) {
                return;
            }
            submitServer(context, (level, player) -> {
                ModernTurntableBlockEntity turntable = turntable(level);
                String session = turntable.getPlaybackSyncMetadata(level.getGameTime()).sessionId();
                lastObservation.set("server playback: hasDisc=" + turntable.hasDisc() + ", playing="
                        + turntable.isPlaying() + ", rawUrl=" + turntable.getRawUrl() + ", session=" + session);
                if (turntable.hasDisc() && turntable.isPlaying() && properties.url().equals(turntable.getRawUrl())
                        && !session.isBlank()) {
                    serverSession.set(session);
                    serverPlaybackObserved.set(true);
                }
            });
        }

        private void probeEject(BenchClientContext context) {
            if (serverEjectObserved.get() || serverTaskPending.get()) {
                return;
            }
            submitServer(context, (level, player) -> {
                ModernTurntableBlockEntity turntable = turntable(level);
                boolean blockHasDisc = level.getBlockState(turntablePos).getValue(ModernTurntableBlock.HAS_DISC);
                boolean blockPlaying = level.getBlockState(turntablePos).getValue(ModernTurntableBlock.PLAYING);
                lastObservation.set("server eject: hasDisc=" + turntable.hasDisc() + ", playing="
                        + turntable.isPlaying() + ", blockHasDisc=" + blockHasDisc + ", blockPlaying="
                        + blockPlaying);
                if (!turntable.hasDisc() && !turntable.isPlaying() && !blockHasDisc && !blockPlaying) {
                    serverEjectObserved.set(true);
                }
            });
        }

        private boolean clientStopped(BenchClientContext context) {
            ModernTurntableBlockEntity turntable = clientTurntable(context);
            return turntable != null && !turntable.hasDisc() && !turntable.isPlaying()
                    && !context.level().getBlockState(turntablePos).getValue(ModernTurntableBlock.HAS_DISC)
                    && !context.level().getBlockState(turntablePos).getValue(ModernTurntableBlock.PLAYING);
        }

        private boolean resourcesConverged(BenchClientContext context) {
            ModernTurntableSound activeSound = sound.get();
            OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
            StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
            return activeSound != null && !context.minecraft().getSoundManager().isActive(activeSound)
                    && !ModernTurntablePlaybackTracker.isActiveSession(turntablePos, serverSession.get())
                    && ClientAudioOutputRegistry.getStereoSnapshot(turntablePos).isEmpty()
                    && tap.activeInstances() == tapBaseline.activeInstances()
                    && tap.closesCompleted() >= tapBaseline.closesCompleted() + 1L
                    && stereo.activeInstances() == stereoBaseline.activeInstances()
                    && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 1L
                    && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                    && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                    && MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING).currentBytes()
                            == audioStagingBaseline;
        }

        private void prepareHand(BenchClientContext context, ItemStack stack) {
            pendingHand = stack.copy();
            handReady.set(false);
            context.player().setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
            submitServer(context, (level, player) -> {
                player.setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
                handReady.set(true);
            });
        }

        private void interact(BenchClientContext context) {
            handReady.set(false);
            context.player().setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(turntablePos), Direction.UP,
                    turntablePos, false);
            context.minecraft().gameMode.useItemOn(context.player(), InteractionHand.MAIN_HAND, hit);
        }

        private ItemStack realDisc() {
            ItemStack stack = new ItemStack(InitItems.MUSIC_CD.get());
            return ItemMusicCD.setSongInfo(new ItemMusicCD.SongInfo(
                    properties.url(), "real turntable MP3 end-to-end", 360, false), stack);
        }

        private void submitServer(BenchClientContext context, ServerAction action) {
            if (!serverTaskPending.compareAndSet(false, true)) {
                return;
            }
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                serverTaskPending.set(false);
                failure.compareAndSet(null, new IllegalStateException("Integrated server is unavailable"));
                return;
            }
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || !(player.level() instanceof ServerLevel level)) {
                        throw new IllegalStateException("Integrated server player is unavailable");
                    }
                    action.run(level, player);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    serverTaskPending.set(false);
                }
            });
        }

        private ModernTurntableBlockEntity turntable(ServerLevel level) {
            if (level.getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable) {
                return turntable;
            }
            throw new AssertionError("Modern turntable block entity is missing at " + turntablePos);
        }

        private ModernTurntableBlockEntity clientTurntable(BenchClientContext context) {
            return context.level().getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable
                    ? turntable : null;
        }

        private void advanceTo(int nextPhase, String observation) {
            phase = nextPhase;
            phaseTicks = 0;
            lastObservation.set(observation);
        }

        private void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Real turntable MP3 end-to-end failed", error);
            }
        }

        @FunctionalInterface
        private interface ServerAction {
            void run(ServerLevel level, ServerPlayer player) throws Exception;
        }
    }

    private static final class CrossDimensionMediaCleanupScenario implements BenchClientScenario {
        private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-00000000d036");
        private static final PlaybackSessionId OUTBOUND_SESSION = PlaybackSessionId.of("bench-dimension-outbound");
        private static final PlaybackSessionId RETURN_SESSION = PlaybackSessionId.of("bench-dimension-return");
        private static final int MAX_PHASE_TICKS = 400;

        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean serverTaskPending = new AtomicBoolean();
        private final AtomicInteger loadingScreens = new AtomicInteger();
        private final AtomicInteger clientClones = new AtomicInteger();
        private final CopyOnWriteArrayList<ResourceKey<Level>> unloadedDimensions = new CopyOnWriteArrayList<>();
        private final RaceSyncPolicy policy = new RaceSyncPolicy();
        private BenchClientContext benchContext;
        private BenchGuiSession loadingGuiSession;
        private final Consumer<ScreenEvent.Init.Post> screenInitListener = event -> {
            if (event.getScreen() instanceof LevelLoadingScreen && benchContext != null) {
                loadingScreens.incrementAndGet();
                loadingGuiSession = benchContext.automation().beginGuiSession(LevelLoadingScreen.class);
            }
        };
        private final Consumer<ScreenEvent.Closing> screenClosingListener = event -> {
            if (event.getScreen() instanceof LevelLoadingScreen && loadingGuiSession != null) {
                loadingGuiSession.close();
                loadingGuiSession = null;
            }
        };
        private final Consumer<ClientPlayerNetworkEvent.Clone> cloneListener = ignored -> clientClones.incrementAndGet();
        private final Consumer<LevelEvent.Unload> unloadListener = event -> {
            if (event.getLevel() instanceof ClientLevel level) {
                unloadedDimensions.add(level.dimension());
            }
        };

        private ResourceKey<Level> originDimension;
        private ResourceKey<Level> targetDimension;
        private Vec3 originPosition;
        private float originYRot;
        private float originXRot;
        private UUID playerId;
        private BlockPos targetPlatform;
        private boolean listenersRegistered;
        private boolean completed;
        private int phase;
        private int phaseTicks;

        @Override
        public void setup(BenchClientContext context) {
            if (context.minecraft().level == null || context.minecraft().player == null) {
                throw new AssertionError("Integrated client is unavailable before dimension smoke setup");
            }
            ClientMediaPlaybackSessions.clearAll(null);
            originDimension = context.minecraft().level.dimension();
            targetDimension = originDimension.equals(Level.NETHER) ? Level.OVERWORLD : Level.NETHER;
            originPosition = context.minecraft().player.position();
            originYRot = context.minecraft().player.getYRot();
            originXRot = context.minecraft().player.getXRot();
            playerId = context.minecraft().player.getUUID();
            benchContext = context;
            NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, screenInitListener);
            NeoForge.EVENT_BUS.addListener(ScreenEvent.Closing.class, screenClosingListener);
            NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.Clone.class, cloneListener);
            NeoForge.EVENT_BUS.addListener(LevelEvent.Unload.class, unloadListener);
            listenersRegistered = true;
            acceptCurrent(context, OUTBOUND_SESSION, "outbound");
            requireActive(OUTBOUND_SESSION, "outbound setup");
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            throwIfFailed();
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
            phaseTicks++;
            switch (phase) {
                case 0 -> {
                    if (!serverTaskPending.get()) {
                        teleport(context, targetDimension, null);
                        advanceTo(1);
                    }
                }
                case 1 -> {
                    if (targetDimension.equals(clientDimension(context))) {
                        requireTransition(originDimension, 1, 0, "outbound");
                        acceptCurrent(context, RETURN_SESSION, "return");
                        requireActive(RETURN_SESSION, "return setup");
                        teleport(context, originDimension, originPosition);
                        advanceTo(2);
                    }
                }
                case 2 -> {
                    if (originDimension.equals(clientDimension(context))) {
                        requireTransition(targetDimension, 2, 1, "return");
                        if (!(context.minecraft().screen instanceof LevelLoadingScreen)) {
                            completed = true;
                            return BenchClientStepResult.COMPLETE;
                        }
                    }
                }
                default -> throw new AssertionError("Unexpected cross-dimension phase " + phase);
            }
            if (phaseTicks > MAX_PHASE_TICKS) {
                throw new AssertionError("Cross-dimension media cleanup stalled in phase " + phase
                        + ": dimension=" + clientDimension(context) + ", loadingScreens=" + loadingScreens
                        + ", clones=" + clientClones + ", unloads=" + unloadedDimensions);
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfFailed();
            if (!completed || !originDimension.equals(clientDimension(context)) || loadingScreens.get() < 1
                    || clientClones.get() < 2 || !unloadedDimensions.contains(originDimension)
                    || !unloadedDimensions.contains(targetDimension) || policy.sounds().size() != 2
                    || policy.sounds().stream().anyMatch(sound -> sound.discards() != 1)
                    || ClientMediaPlaybackRegistry.contains(SOURCE_ID)
                    || ClientMediaSoundRegistry.get(SOURCE_ID) != null) {
                throw new AssertionError("Cross-dimension round trip did not converge: dimension="
                        + clientDimension(context) + ", loadingScreens=" + loadingScreens + ", clones="
                        + clientClones + ", unloads=" + unloadedDimensions + ", policy=" + policy.summary());
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            ClientMediaPlaybackSessions.clearAll(null);
            if (listenersRegistered) {
                NeoForge.EVENT_BUS.unregister(screenInitListener);
                NeoForge.EVENT_BUS.unregister(screenClosingListener);
                NeoForge.EVENT_BUS.unregister(cloneListener);
                NeoForge.EVENT_BUS.unregister(unloadListener);
                listenersRegistered = false;
            }
            if (loadingGuiSession != null) {
                loadingGuiSession.close();
                loadingGuiSession = null;
            }
            benchContext = null;
            var server = context.minecraft().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    ServerLevel origin = server.getLevel(originDimension);
                    if (player != null && origin != null && !player.level().dimension().equals(originDimension)) {
                        player.teleportTo(origin, originPosition.x, originPosition.y, originPosition.z, Set.of(),
                                originYRot, originXRot, true);
                    }
                    ServerLevel target = server.getLevel(targetDimension);
                    if (target != null && targetPlatform != null) {
                        for (int x = -1; x <= 1; x++) {
                            for (int z = -1; z <= 1; z++) {
                                target.setBlockAndUpdate(targetPlatform.offset(x, -1, z), Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                });
            }
        }

        private void acceptCurrent(BenchClientContext context, PlaybackSessionId sessionId, String transport) {
            if (context.minecraft().player == null) {
                throw new AssertionError("Client player is unavailable before " + transport + " media setup");
            }
            var player = context.minecraft().player;
            ClientMediaSyncPayload payload = new MP4PlaybackSyncPacket(player.getUUID(), SOURCE_ID,
                    ClientMediaSyncPayload.SOURCE_PLAYER, player.getId(), player.getX(), player.getY(), player.getZ(),
                    true, 0, "https://example.invalid/dimension-" + transport, "BV-dimension-bench",
                    "dimension bench", 120, 750, sessionId.value(), 1_000L, false);
            ClientMediaSyncHandler.handleSync(payload, policy);
        }

        private void requireActive(PlaybackSessionId sessionId, String stage) {
            ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(SOURCE_ID);
            ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(SOURCE_ID);
            if (active == null || !active.playbackSessionId().filter(sessionId::equals).isPresent()
                    || sound == null || !sound.playbackSession().filter(sessionId::equals).isPresent()) {
                throw new AssertionError("Dimension smoke media state was not active during " + stage
                        + ": active=" + active + ", sound=" + sound);
            }
        }

        private void requireTransition(ResourceKey<Level> unloadedDimension, int expectedTransitions,
                int soundIndex, String stage) {
            if (loadingScreens.get() < 1 || clientClones.get() < expectedTransitions
                    || !unloadedDimensions.contains(unloadedDimension)
                    || ClientMediaPlaybackRegistry.contains(SOURCE_ID)
                    || ClientMediaSoundRegistry.get(SOURCE_ID) != null
                    || policy.sounds().size() <= soundIndex || policy.sounds().get(soundIndex).discards() != 1) {
                throw new AssertionError("Dimension " + stage + " transition did not clean exact media state: loading="
                        + loadingScreens + ", clones=" + clientClones + ", unloads=" + unloadedDimensions
                        + ", policy=" + policy.summary());
            }
        }

        private void teleport(BenchClientContext context, ResourceKey<Level> destinationKey,
                Vec3 requestedPosition) {
            if (!serverTaskPending.compareAndSet(false, true)) {
                return;
            }
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                serverTaskPending.set(false);
                failure.compareAndSet(null, new IllegalStateException("Integrated server is unavailable"));
                return;
            }
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    ServerLevel destination = server.getLevel(destinationKey);
                    if (player == null || destination == null) {
                        throw new IllegalStateException("Dimension teleport endpoint is unavailable: " + destinationKey);
                    }
                    Vec3 position = requestedPosition;
                    if (position == null) {
                        int platformY = Math.min(destination.getMaxY() - 8, 200);
                        targetPlatform = new BlockPos(0, platformY, 0);
                        for (int x = -1; x <= 1; x++) {
                            for (int z = -1; z <= 1; z++) {
                                destination.setBlockAndUpdate(targetPlatform.offset(x, -1, z),
                                        Blocks.STONE.defaultBlockState());
                            }
                        }
                        position = Vec3.atBottomCenterOf(targetPlatform);
                    }
                    if (!player.teleportTo(destination, position.x, position.y, position.z, Set.<Relative>of(),
                            originYRot, originXRot, true)) {
                        throw new AssertionError("Server rejected dimension teleport to " + destinationKey);
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    serverTaskPending.set(false);
                }
            });
        }

        private ResourceKey<Level> clientDimension(BenchClientContext context) {
            return context.minecraft().level != null ? context.minecraft().level.dimension() : null;
        }

        private void advanceTo(int nextPhase) {
            phase = nextPhase;
            phaseTicks = 0;
        }

        private void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Cross-dimension media cleanup failed", error);
            }
        }
    }

    private static final class TurntableBlockInteractionScenario implements BenchClientScenario {
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean serverTaskPending = new AtomicBoolean();
        private final AtomicBoolean setupComplete = new AtomicBoolean();
        private final AtomicBoolean handReady = new AtomicBoolean();
        private final AtomicBoolean firstInsertObserved = new AtomicBoolean();
        private final AtomicBoolean rightClickEjectObserved = new AtomicBoolean();
        private final AtomicBoolean secondInsertObserved = new AtomicBoolean();
        private final AtomicBoolean automationExtracted = new AtomicBoolean();
        private BlockPos turntablePos;
        private ItemStack pendingHand = ItemStack.EMPTY;
        private final AtomicReference<String> lastObservation = new AtomicReference<>("not started");
        private UUID playerId;
        private int phase;
        private int phaseTicks;

        @Override
        public void setup(BenchClientContext context) {
            playerId = context.player().getUUID();
            turntablePos = context.player().blockPosition().offset(2, 0, 2).immutable();
            submitServer(context, (level, player) -> {
                level.setBlockAndUpdate(turntablePos, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
                turntable(level).setVolumePerMille(0);
                setupComplete.set(true);
            });
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            throwIfFailed();
            return setupComplete.get() && clientTurntable(context) != null
                    && context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            throwIfFailed();
            phaseTicks++;
            switch (phase) {
                case 0 -> {
                    if (!serverTaskPending.get()) {
                        prepareHand(context, disc("bench right-click eject"));
                        advanceTo(1, "waiting for first disc hand sync");
                    }
                }
                case 1 -> {
                    if (handReady.get()) {
                        interact(context);
                        advanceTo(2, "first use-item-on packet sent");
                    }
                }
                case 2 -> {
                    if (firstInsertObserved.get() && !serverTaskPending.get()) {
                        prepareHand(context, new ItemStack(Items.STICK));
                        advanceTo(3, "waiting for ordinary-item hand sync");
                    } else if (!firstInsertObserved.get()) {
                        probeServer(context, (level, player) -> {
                            ModernTurntableBlockEntity turntable = turntable(level);
                            boolean blockHasDisc = level.getBlockState(turntablePos)
                                    .getValue(ModernTurntableBlock.HAS_DISC);
                            boolean blockPlaying = level.getBlockState(turntablePos)
                                    .getValue(ModernTurntableBlock.PLAYING);
                            lastObservation.set("first insert: entityHasDisc=" + turntable.hasDisc()
                                    + " entityPlaying=" + turntable.isPlaying() + " blockHasDisc=" + blockHasDisc
                                    + " blockPlaying=" + blockPlaying + " serverHand="
                                    + player.getMainHandItem().getItem());
                            if (turntable.hasDisc() && turntable.isPlaying() && blockHasDisc && blockPlaying) {
                                firstInsertObserved.set(true);
                            }
                        });
                    }
                }
                case 3 -> {
                    if (handReady.get()) {
                        interact(context);
                        advanceTo(4, "right-click eject packet sent");
                    }
                }
                case 4 -> {
                    if (rightClickEjectObserved.get() && !serverTaskPending.get()) {
                        prepareHand(context, disc("bench automation extract"));
                        advanceTo(5, "waiting for second disc hand sync");
                    } else if (!rightClickEjectObserved.get()) {
                        probeServer(context, (level, player) -> {
                            ModernTurntableBlockEntity turntable = turntable(level);
                            boolean blockHasDisc = level.getBlockState(turntablePos)
                                    .getValue(ModernTurntableBlock.HAS_DISC);
                            boolean blockPlaying = level.getBlockState(turntablePos)
                                    .getValue(ModernTurntableBlock.PLAYING);
                            lastObservation.set("right-click eject: entityHasDisc=" + turntable.hasDisc()
                                    + " entityPlaying=" + turntable.isPlaying() + " blockHasDisc=" + blockHasDisc
                                    + " blockPlaying=" + blockPlaying + " serverHand="
                                    + player.getMainHandItem().getItem());
                            if (!turntable.hasDisc() && !turntable.isPlaying() && !blockHasDisc && !blockPlaying) {
                                rightClickEjectObserved.set(true);
                            }
                        });
                    }
                }
                case 5 -> {
                    if (handReady.get()) {
                        interact(context);
                        advanceTo(6, "second use-item-on packet sent");
                    }
                }
                case 6 -> {
                    if (secondInsertObserved.get() && !serverTaskPending.get()) {
                        extractThroughAutomation(context);
                        advanceTo(7, "automation extraction submitted");
                    } else if (!secondInsertObserved.get()) {
                        probeServer(context, (level, player) -> {
                            ModernTurntableBlockEntity turntable = turntable(level);
                            lastObservation.set("second insert: entityHasDisc=" + turntable.hasDisc()
                                    + " entityPlaying=" + turntable.isPlaying() + " serverHand="
                                    + player.getMainHandItem().getItem());
                            if (turntable.hasDisc() && turntable.isPlaying()) {
                                secondInsertObserved.set(true);
                            }
                        });
                    }
                }
                case 7 -> {
                    ModernTurntableBlockEntity turntable = clientTurntable(context);
                    lastObservation.set("client extraction convergence: extracted=" + automationExtracted.get()
                            + " entity=" + (turntable != null ? "hasDisc=" + turntable.hasDisc()
                                    + " playing=" + turntable.isPlaying() : "missing"));
                    if (automationExtracted.get() && turntable != null
                            && !turntable.hasDisc() && !turntable.isPlaying()
                            && !context.level().getBlockState(turntablePos).getValue(ModernTurntableBlock.HAS_DISC)
                            && !context.level().getBlockState(turntablePos).getValue(ModernTurntableBlock.PLAYING)) {
                        return BenchClientStepResult.COMPLETE;
                    }
                }
                default -> throw new AssertionError("Unexpected turntable interaction phase " + phase);
            }
            if (phaseTicks > 100) {
                throw new AssertionError("Modern turntable interaction stalled in phase " + phase
                        + " after " + phaseTicks + " ticks; " + lastObservation.get());
            }
            return BenchClientStepResult.CONTINUE;
        }

        private void advanceTo(int nextPhase, String observation) {
            phase = nextPhase;
            phaseTicks = 0;
            lastObservation.set(observation);
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfFailed();
            if (!firstInsertObserved.get() || !rightClickEjectObserved.get()
                    || !secondInsertObserved.get() || !automationExtracted.get()) {
                throw new AssertionError("Modern turntable interactions did not all complete: firstInsert="
                        + firstInsertObserved + " rightClickEject=" + rightClickEjectObserved + " secondInsert="
                        + secondInsertObserved + " automationExtract=" + automationExtracted);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            context.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            var server = context.minecraft().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null && player.level() instanceof ServerLevel level) {
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        level.getEntitiesOfClass(ItemEntity.class, new AABB(turntablePos).inflate(3.0D))
                                .forEach(ItemEntity::discard);
                        level.setBlockAndUpdate(turntablePos, Blocks.AIR.defaultBlockState());
                    }
                });
            }
        }

        private void prepareHand(BenchClientContext context, ItemStack stack) {
            pendingHand = stack.copy();
            handReady.set(false);
            context.player().setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
            submitServer(context, (level, player) -> {
                player.setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
                handReady.set(true);
            });
        }

        private void interact(BenchClientContext context) {
            handReady.set(false);
            context.player().setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(turntablePos), Direction.UP,
                    turntablePos, false);
            context.minecraft().gameMode.useItemOn(context.player(), InteractionHand.MAIN_HAND, hit);
        }

        private void extractThroughAutomation(BenchClientContext context) {
            submitServer(context, (level, player) -> {
                ModernTurntableBlockEntity turntable = turntable(level);
                ItemResource resource = turntable.getItemHandler().getResource(0);
                try (Transaction transaction = Transaction.openRoot()) {
                    int blocked = turntable.getItemHandler().extract(0, resource, 1, transaction);
                    if (blocked != 0) {
                        throw new AssertionError("Default after-playback extraction removed a playing disc");
                    }
                }
                turntable.cycleExtractionMode();
                resource = turntable.getItemHandler().getResource(0);
                try (Transaction transaction = Transaction.openRoot()) {
                    int extracted = turntable.getItemHandler().extract(0, resource, 1, transaction);
                    if (extracted != 1) {
                        throw new AssertionError("Always extraction mode removed " + extracted + " disc(s)");
                    }
                    transaction.commit();
                }
                if (turntable.hasDisc() || turntable.isPlaying()
                        || level.getBlockState(turntablePos).getValue(ModernTurntableBlock.HAS_DISC)
                        || level.getBlockState(turntablePos).getValue(ModernTurntableBlock.PLAYING)) {
                    throw new AssertionError("Automation extraction left turntable state active");
                }
                automationExtracted.set(true);
            });
        }

        private void probeServer(BenchClientContext context, ServerAction action) {
            submitServer(context, action);
        }

        private void submitServer(BenchClientContext context, ServerAction action) {
            if (!serverTaskPending.compareAndSet(false, true)) {
                return;
            }
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                serverTaskPending.set(false);
                failure.compareAndSet(null, new IllegalStateException("Integrated server is unavailable"));
                return;
            }
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || !(player.level() instanceof ServerLevel level)) {
                        throw new IllegalStateException("Integrated server player is unavailable");
                    }
                    action.run(level, player);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    serverTaskPending.set(false);
                }
            });
        }

        private ModernTurntableBlockEntity turntable(ServerLevel level) {
            if (level.getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable) {
                return turntable;
            }
            throw new AssertionError("Modern turntable block entity is missing at " + turntablePos);
        }

        private ModernTurntableBlockEntity clientTurntable(BenchClientContext context) {
            return context.level().getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable
                    ? turntable : null;
        }

        private static ItemStack disc(String name) {
            ItemStack stack = new ItemStack(InitItems.MUSIC_CD.get());
            return ItemMusicCD.setSongInfo(new ItemMusicCD.SongInfo(
                    "https://example.test/bench.mp3", name, 120, false), stack);
        }

        private void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Modern turntable block interaction failed", error);
            }
        }

        @FunctionalInterface
        private interface ServerAction {
            void run(ServerLevel level, ServerPlayer player) throws Exception;
        }
    }

    private static final class RealMp3SoundEngineScenario implements BenchClientScenario {
        private static final long FIRST_OFFSET_MILLIS = 5_000L;
        private static final long SECOND_OFFSET_MILLIS = 12_000L;
        private static final long PAUSE_HOLD_NANOS = 750_000_000L;
        private static final long PAUSE_POSITION_TOLERANCE_MILLIS = 80L;
        private static final long RESUME_PROGRESS_MILLIS = 250L;
        private static final long TOTAL_MILLIS = 360_000L;
        private static final BenchMetricDescriptor SOUND_ACTIVE = new BenchMetricDescriptor(
                "ncpb.real_mp3_sound_engine.sound_active", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor CHANNEL_STARTS = new BenchMetricDescriptor(
                "ncpb.real_mp3_sound_engine.channel_starts", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor TAP_ACTIVE = new BenchMetricDescriptor(
                "ncpb.real_mp3_sound_engine.tap_active", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor OPENAL_ACTIVE = new BenchMetricDescriptor(
                "ncpb.real_mp3_sound_engine.openal_active", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor PAUSED = new BenchMetricDescriptor(
                "ncpb.real_mp3_sound_engine.paused", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor AUDIBLE_MILLIS = new BenchMetricDescriptor(
                "ncpb.real_mp3_sound_engine.audible_millis", "milliseconds", MetricDirection.NEUTRAL);

        private final AudioStreamProperties.RealMp3Bench properties = AudioStreamProperties.realMp3Bench();
        private final PlaybackSessionId firstSession = PlaybackSessionId.of("bench-real-mp3-sound-engine-first");
        private final PlaybackSessionId secondSession = PlaybackSessionId.of("bench-real-mp3-sound-engine-second");
        private final BlockPos turntablePos = new BlockPos(31, 64, 33);
        private final AtomicInteger streamingChannelStarts = new AtomicInteger();
        private final AtomicReference<Throwable> streamFailure = new AtomicReference<>();
        private BenchSound firstSound;
        private BenchSound secondSound;
        private final Consumer<PlayStreamingSourceEvent> streamingListener = event -> {
            if (event.getSound() == firstSound || event.getSound() == secondSound) {
                streamingChannelStarts.incrementAndGet();
            }
        };
        private HttpAudioStreamHandler.RegisteredRequest firstRequest;
        private HttpAudioStreamHandler.RegisteredRequest secondRequest;
        private UUID ownerId;
        private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
        private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
        private long audioStagingBaseline;
        private StereoOpenALHandler.PcmQuality firstPcm =
                new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
        private StereoOpenALHandler.PcmQuality secondPcm =
                new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
        private boolean listenerRegistered;
        private int phase;
        private boolean converged;
        private long pausePositionMillis = -1L;
        private long pauseDeadlineNanos;
        private long resumePositionMillis = -1L;
        private boolean pauseContinuityVerified;
        private boolean exactMuteStopVerified;
        private boolean exactRangeStopVerified;

        @Override
        public void setup(BenchClientContext context) {
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
            ModernTurntablePlaybackTracker.stopAllSounds();
            ownerId = context.player().getUUID();
            ClientAudioOutputRegistry.setOwnerVolume(ownerId, 1.0F);
            tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
            stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
            audioStagingBaseline = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING)
                    .currentBytes();

            NeoForge.EVENT_BUS.addListener(PlayStreamingSourceEvent.class, streamingListener);
            listenerRegistered = true;
            firstRequest = startSound(context, firstSession, FIRST_OFFSET_MILLIS, true);
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
            throwIfStreamFailed();
            ClientAudioOutputRegistry.updatePositions(new float[] {
                    (float) context.player().getX(), (float) context.player().getEyeY(),
                    (float) context.player().getZ()
            });
            OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());

            StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry
                    .getOwnerStereoSnapshot(ownerId).orElse(null);
            if (output != null && output.firstPcm().samples() > 0L) {
                if (phase == 0) {
                    firstPcm = output.firstPcm();
                } else {
                    secondPcm = output.firstPcm();
                }
            }
            record(context);

            if (phase == 0 && firstSound.streamReady() && streamingChannelStarts.get() == 1
                    && context.minecraft().getSoundManager().isActive(firstSound)
                    && output != null && output.started() && output.firstPcm().samples() >= 1_024L) {
                requireHealthy("first SoundEngine channel", firstSound, 1, output);
                firstPcm = output.firstPcm();
                if (!ModernTurntablePlaybackTracker.tryStart(turntablePos, firstSession.value(),
                        (int) (TOTAL_MILLIS / 1_000L))) {
                    throw new AssertionError("Could not bind first sound to turntable mute session");
                }
                ModernTurntablePlaybackTracker.registerSound(firstSound, turntablePos, firstSession.value());
                ModernTurntablePlaybackCoordinator.stop(turntablePos, firstSession.value());
                if (ModernTurntablePlaybackTracker.isActiveSession(turntablePos, firstSession.value())) {
                    throw new AssertionError("Exact turntable mute stop left its client session active");
                }
                exactMuteStopVerified = true;
                phase = 1;
                return BenchClientStepResult.CONTINUE;
            }
            if (phase == 1 && firstChannelConverged(context)) {
                secondRequest = startSound(context, secondSession, SECOND_OFFSET_MILLIS, false);
                phase = 2;
                return BenchClientStepResult.CONTINUE;
            }
            if (phase == 2 && secondSound.streamReady() && streamingChannelStarts.get() == 2
                    && context.minecraft().getSoundManager().isActive(secondSound)
                    && output != null && output.started() && output.firstPcm().samples() >= 1_024L) {
                requireHealthy("replacement SoundEngine channel", secondSound, 2, output);
                secondPcm = output.firstPcm();
                pausePositionMillis = output.positionMillis();
                if (pausePositionMillis < 0L) {
                    throw new AssertionError("Replacement output had no audible position before pause: " + output);
                }
                setPaused(context, true);
                pauseDeadlineNanos = System.nanoTime() + PAUSE_HOLD_NANOS;
                phase = 3;
                return BenchClientStepResult.CONTINUE;
            }
            if (phase == 3 && System.nanoTime() >= pauseDeadlineNanos) {
                requirePausedContinuity(context, output);
                setPaused(context, false);
                resumePositionMillis = output.positionMillis();
                phase = 4;
                return BenchClientStepResult.CONTINUE;
            }
            if (phase == 4 && output != null && !output.paused()
                    && output.positionMillis() >= resumePositionMillis + RESUME_PROGRESS_MILLIS) {
                if (!isActive(context, secondSound) || streamingChannelStarts.get() != 2
                        || OpenALTappedAudioInputStream.lifecycleSnapshot().instancesCreated()
                                != tapBaseline.instancesCreated() + 2L
                        || StereoOpenALHandler.lifecycleSnapshot().instancesCreated()
                                != stereoBaseline.instancesCreated() + 2L) {
                    throw new AssertionError("Pause/resume recreated the streaming channel or OpenAL pipeline");
                }
                pauseContinuityVerified = true;
                if (!ModernTurntablePlaybackTracker.tryStart(turntablePos, secondSession.value(),
                        (int) (TOTAL_MILLIS / 1_000L))) {
                    throw new AssertionError("Could not bind replacement sound to turntable eject session");
                }
                ModernTurntablePlaybackTracker.registerSound(secondSound, turntablePos, secondSession.value());
                ModernTurntablePlaybackCoordinator.stop(turntablePos, firstSession.value());
                if (!ModernTurntablePlaybackTracker.isActiveSession(turntablePos, secondSession.value())
                        || !isActive(context, secondSound)) {
                    throw new AssertionError("Stale turntable stop terminated the replacement disc session");
                }
                ModernTurntablePlaybackCoordinator.stop(turntablePos, secondSession.value());
                if (ModernTurntablePlaybackTracker.isActiveSession(turntablePos, secondSession.value())) {
                    throw new AssertionError("Exact turntable range stop left its client session active");
                }
                exactRangeStopVerified = true;
                phase = 5;
                return BenchClientStepResult.CONTINUE;
            }
            if (phase == 5 && resourcesConverged(context)) {
                converged = true;
                return BenchClientStepResult.COMPLETE;
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfStreamFailed();
            if (!converged || !pauseContinuityVerified || !exactMuteStopVerified || !exactRangeStopVerified
                    || !resourcesConverged(context)) {
                throw new AssertionError("Minecraft SoundEngine streaming channels did not converge: firstActive="
                        + context.minecraft().getSoundManager().isActive(firstSound) + " secondActive="
                        + context.minecraft().getSoundManager().isActive(secondSound) + " tap="
                        + OpenALTappedAudioInputStream.lifecycleSnapshot() + " stereo="
                        + StereoOpenALHandler.lifecycleSnapshot());
            }
            if (streamingChannelStarts.get() != 2) {
                throw new AssertionError("Expected exactly two Minecraft streaming-channel starts, got "
                        + streamingChannelStarts.get());
            }
            OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
            StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
            if (tap.instancesCreated() != tapBaseline.instancesCreated() + 2L
                    || tap.closesCompleted() != tapBaseline.closesCompleted() + 2L
                    || stereo.instancesCreated() != stereoBaseline.instancesCreated() + 2L
                    || stereo.cleanupsStarted() != stereoBaseline.cleanupsStarted() + 2L
                    || stereo.cleanupsCompleted() != stereoBaseline.cleanupsCompleted() + 2L) {
                throw new AssertionError("SoundEngine replacement must close both tapped streams and OpenAL outputs exactly once: "
                        + "tapBaseline=" + tapBaseline + " tap=" + tap + " stereoBaseline=" + stereoBaseline
                        + " stereo=" + stereo);
            }
            requirePcmQuality("first SoundEngine channel", firstPcm);
            requirePcmQuality("replacement SoundEngine channel", secondPcm);
        }

        @Override
        public void teardown(BenchClientContext context) {
            setPaused(context, false);
            stopSound(context, firstSound);
            stopSound(context, secondSound);
            ModernTurntablePlaybackTracker.stopAllSounds();
            cancelRequest(firstRequest);
            cancelRequest(secondRequest);
            if (listenerRegistered) {
                NeoForge.EVENT_BUS.unregister(streamingListener);
                listenerRegistered = false;
            }
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
        }

        private boolean resourcesConverged(BenchClientContext context) {
            OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
            StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
            return !isActive(context, firstSound) && !isActive(context, secondSound)
                    && tap.activeInstances() == tapBaseline.activeInstances()
                    && tap.closesCompleted() >= tapBaseline.closesCompleted() + 2L
                    && stereo.activeInstances() == stereoBaseline.activeInstances()
                    && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 2L
                    && ClientAudioOutputRegistry.getOwnerStereoSnapshot(ownerId).isEmpty()
                    && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                    && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                    && MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING).currentBytes()
                            == audioStagingBaseline;
        }

        private boolean firstChannelConverged(BenchClientContext context) {
            OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
            StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
            return !isActive(context, firstSound)
                    && tap.activeInstances() == tapBaseline.activeInstances()
                    && tap.closesCompleted() >= tapBaseline.closesCompleted() + 1L
                    && stereo.activeInstances() == stereoBaseline.activeInstances()
                    && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 1L
                    && ClientAudioOutputRegistry.getOwnerStereoSnapshot(ownerId).isEmpty();
        }

        private void requireHealthy(String phase, BenchSound currentSound, int expectedStarts,
                StereoOpenALHandler.DiagnosticSnapshot output) {
            if (streamingChannelStarts.get() != expectedStarts || !currentSound.streamReady()
                    || output == null || !output.started()) {
                throw new AssertionError("Real MP3 did not fully attach to the Minecraft streaming channel: starts="
                        + streamingChannelStarts.get() + " ready=" + currentSound.streamReady() + " output=" + output);
            }
            requirePcmQuality(phase, output.firstPcm());
        }

        private void throwIfStreamFailed() {
            Throwable failure = streamFailure.get();
            if (failure != null) {
                throw new AssertionError("Real MP3 SoundEngine stream failed", failure);
            }
        }

        private void record(BenchClientContext context) {
            context.metrics().record(SOUND_ACTIVE,
                    (isActive(context, firstSound) ? 1L : 0L) + (isActive(context, secondSound) ? 1L : 0L));
            context.metrics().record(CHANNEL_STARTS, streamingChannelStarts.get());
            context.metrics().record(TAP_ACTIVE,
                    OpenALTappedAudioInputStream.lifecycleSnapshot().activeInstances());
            context.metrics().record(OPENAL_ACTIVE, StereoOpenALHandler.lifecycleSnapshot().activeInstances());
            StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry
                    .getOwnerStereoSnapshot(ownerId).orElse(null);
            context.metrics().record(PAUSED, output != null && output.paused() ? 1L : 0L);
            context.metrics().record(AUDIBLE_MILLIS, output != null ? output.positionMillis() : -1L);
        }

        private void requirePausedContinuity(BenchClientContext context,
                StereoOpenALHandler.DiagnosticSnapshot output) {
            long pausedPosition = output != null ? output.positionMillis() : -1L;
            if (output == null || !output.paused()
                    || Math.abs(pausedPosition - pausePositionMillis) > PAUSE_POSITION_TOLERANCE_MILLIS
                    || streamingChannelStarts.get() != 2 || !isActive(context, secondSound)
                    || OpenALTappedAudioInputStream.lifecycleSnapshot().instancesCreated()
                            != tapBaseline.instancesCreated() + 2L
                    || StereoOpenALHandler.lifecycleSnapshot().instancesCreated()
                            != stereoBaseline.instancesCreated() + 2L) {
                throw new AssertionError("Real SoundEngine pause did not freeze the existing output: before="
                        + pausePositionMillis + " after=" + pausedPosition + " output=" + output + " starts="
                        + streamingChannelStarts.get());
            }
        }

        private static void setPaused(BenchClientContext context, boolean paused) {
            ClientAudioOutputRegistry.setPaused(paused);
            if (paused) {
                context.minecraft().getSoundManager().pauseAllExcept();
            } else {
                context.minecraft().getSoundManager().resume();
            }
        }

        private HttpAudioStreamHandler.RegisteredRequest startSound(BenchClientContext context,
                PlaybackSessionId sessionId, long offsetMillis, boolean first) {
            PlaybackRequest request = PlaybackRequest.now(properties.url(), null, sessionId.value(), offsetMillis,
                    TOTAL_MILLIS, ownerId, null);
            HttpAudioStreamHandler.RegisteredRequest registered = HttpAudioStreamHandler.registerRequest(request);
            BenchSound created;
            try {
                created = new BenchSound(URI.create(registered.url()).toURL(), sessionId, offsetMillis, streamFailure);
            } catch (Exception error) {
                cancelRequest(registered);
                throw new AssertionError("Could not create real MP3 SoundEngine bench sound", error);
            }
            if (first) {
                firstSound = created;
            } else {
                secondSound = created;
            }
            SoundEngine.PlayResult result = context.minecraft().getSoundManager().play(created);
            if (result == SoundEngine.PlayResult.NOT_STARTED) {
                cancelRequest(registered);
                throw new AssertionError("Minecraft SoundEngine did not allocate the real MP3 streaming sound");
            }
            return registered;
        }

        private static void stopSound(BenchClientContext context, BenchSound sound) {
            if (sound != null) {
                context.minecraft().getSoundManager().stop(sound);
                sound.requestStop();
            }
        }

        private static boolean isActive(BenchClientContext context, BenchSound sound) {
            return sound != null && context.minecraft().getSoundManager().isActive(sound);
        }

        private static void cancelRequest(HttpAudioStreamHandler.RegisteredRequest request) {
            if (request != null) {
                request.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
            }
        }

        private static final class BenchSound extends SyncedMediaSound {
            private final AtomicReference<Throwable> failure;
            private final AtomicBoolean streamReady = new AtomicBoolean();

            private BenchSound(URL url, PlaybackSessionId sessionId, long offsetMillis,
                    AtomicReference<Throwable> failure) {
                super(url, (int) (TOTAL_MILLIS / 1_000L), null, sessionId.value(), offsetMillis);
                this.failure = failure;
                this.relative = true;
                this.attenuation = SoundInstance.Attenuation.NONE;
                this.volume = 1.0F;
            }

            @Override
            public void tick() {
                tick++;
            }

            @Override
            protected void onStreamReady() {
                streamReady.set(true);
            }

            @Override
            protected void onStreamFailure(Exception error) {
                failure.compareAndSet(null, error);
                super.onStreamFailure(error);
            }

            @Override
            protected void finishSession() {
            }

            @Override
            protected String streamDebugName() {
                return "real MP3 SoundEngine bench";
            }

            private boolean streamReady() {
                return streamReady.get();
            }

            private void requestStop() {
                stop();
            }
        }
    }

    private static final class RealMp3RetainedRetryScenario implements BenchClientScenario {
        private static final long FIRST_OFFSET_MILLIS = 5_000L;
        private static final long REFRESH_OFFSET_MILLIS = 12_000L;
        private static final long TOTAL_MILLIS = 360_000L;
        private static final long RETRY_DELAY_MILLIS = 750L;
        private static final long RETRY_SETTLE_NANOS = 1_000_000_000L;
        private static final BenchMetricDescriptor SOUND_ACTIVE = new BenchMetricDescriptor(
                "ncpb.real_mp3_retained_retry.sound_active", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor CHANNEL_STARTS = new BenchMetricDescriptor(
                "ncpb.real_mp3_retained_retry.channel_starts", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor PREPARES = new BenchMetricDescriptor(
                "ncpb.real_mp3_retained_retry.prepares", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor RETRY_DISPATCHES = new BenchMetricDescriptor(
                "ncpb.real_mp3_retained_retry.retry_dispatches", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor AUDIBLE_MILLIS = new BenchMetricDescriptor(
                "ncpb.real_mp3_retained_retry.audible_millis", "milliseconds", MetricDirection.NEUTRAL);

        private final AudioStreamProperties.RealMp3Bench properties = AudioStreamProperties.realMp3Bench();
        private final PlaybackSessionId retainedSession = PlaybackSessionId.of("bench-real-mp3-retained-retry");
        private final UUID sourceId = UUID.fromString("00000000-0000-0000-0000-00000000a031");
        private final RetainedRetrySyncPolicy policy = new RetainedRetrySyncPolicy();
        private final AtomicInteger streamingChannelStarts = new AtomicInteger();
        private final AtomicReference<Throwable> streamFailure = new AtomicReference<>();
        private final Consumer<PlayStreamingSourceEvent> streamingListener = event -> {
            if (event.getSound() instanceof RetainedRetrySound) {
                streamingChannelStarts.incrementAndGet();
            }
        };
        private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
        private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
        private long audioStagingBaseline;
        private long retrySettleDeadlineNanos;
        private boolean listenerRegistered;
        private boolean converged;
        private int phase;

        @Override
        public void setup(BenchClientContext context) {
            ClientMediaPlaybackSessions.clearAll(null);
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
            ClientAudioOutputRegistry.setOwnerVolume(sourceId, 1.0F);
            tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
            stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
            audioStagingBaseline = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING)
                    .currentBytes();
            NeoForge.EVENT_BUS.addListener(PlayStreamingSourceEvent.class, streamingListener);
            listenerRegistered = true;
            accept(context, "initial", FIRST_OFFSET_MILLIS);
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
            throwIfStreamFailed();
            ClientAudioOutputRegistry.updatePositions(new float[] {
                    (float) context.player().getX(), (float) context.player().getEyeY(),
                    (float) context.player().getZ()
            });
            OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
            StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry
                    .getOwnerStereoSnapshot(sourceId).orElse(null);
            record(context, output);

            if (phase == 0 && ready(context, 0, 1, output)) {
                requirePcmQuality("retained retry initial transport", output.firstPcm());
                RetainedRetrySound failed = policy.sound(0);
                failed.failTransport();
                if (!ClientMediaRetryHandler.retryAfterStreamFailure(sourceId, retainedSession,
                        new IOException("bench retained-session transport failure"), policy.retryPolicy())) {
                    throw new AssertionError("Real retained-session retry was not admitted");
                }
                if (!ClientMediaRetryHandler.isPending(sourceId, retainedSession)) {
                    throw new AssertionError("Real retained-session retry owner was not recorded");
                }
                accept(context, "refreshed", REFRESH_OFFSET_MILLIS);
                if (ClientMediaRetryHandler.isPending(sourceId, retainedSession)) {
                    throw new AssertionError("Authoritative transport refresh did not clear exact retry owner");
                }
                requireRetainedSession(REFRESH_OFFSET_MILLIS);
                retrySettleDeadlineNanos = System.nanoTime() + RETRY_SETTLE_NANOS;
                phase = 1;
                return BenchClientStepResult.CONTINUE;
            }
            if (phase == 1 && ready(context, 1, 2, output)
                    && System.nanoTime() >= retrySettleDeadlineNanos) {
                requirePcmQuality("retained retry refreshed transport", output.firstPcm());
                if (policy.retryDispatches() != 0 || policy.prepareCount() != 2 || policy.rebuildCount() != 1
                        || policy.launchUrls().size() != 2
                        || policy.launchUrls().get(0).equals(policy.launchUrls().get(1))) {
                    throw new AssertionError("Retained-session refresh did not win exact transport replacement: "
                            + policy.summary());
                }
                RetainedRetrySound first = policy.sound(0);
                if (first.discards() != 1 || !first.stopped()
                        || OpenALTappedAudioInputStream.lifecycleSnapshot().instancesCreated()
                                != tapBaseline.instancesCreated() + 2L
                        || StereoOpenALHandler.lifecycleSnapshot().instancesCreated()
                                != stereoBaseline.instancesCreated() + 2L) {
                    throw new AssertionError("Refreshed transport recreated or retired the wrong resources: "
                            + policy.summary());
                }
                if (!ClientMediaRetryHandler.retryAfterStreamFailure(sourceId, retainedSession,
                        new IOException("bench world-unload pending retry"), policy.retryPolicy())
                        || !ClientMediaRetryHandler.isPending(sourceId, retainedSession)) {
                    throw new AssertionError("World-unload retry owner was not recorded before cleanup");
                }
                if (context.minecraft().level == null) {
                    throw new AssertionError("Integrated-client level disappeared before unload cleanup");
                }
                ClientMediaLifecycleHandler.onLevelUnload(new LevelEvent.Unload(context.minecraft().level));
                if (ClientMediaRetryHandler.isPending(sourceId, retainedSession)) {
                    throw new AssertionError("World unload did not clear exact retry owner");
                }
                retrySettleDeadlineNanos = System.nanoTime() + RETRY_SETTLE_NANOS;
                phase = 2;
                return BenchClientStepResult.CONTINUE;
            }
            if (phase == 2 && System.nanoTime() >= retrySettleDeadlineNanos && resourcesConverged(context)) {
                if (policy.retryDispatches() != 0) {
                    throw new AssertionError("World-unload retry timer dispatched after cleanup: "
                            + policy.summary());
                }
                converged = true;
                return BenchClientStepResult.COMPLETE;
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfStreamFailed();
            if (!converged || !resourcesConverged(context) || policy.retryDispatches() != 0
                    || streamingChannelStarts.get() != 2 || policy.sounds().size() != 2
                    || policy.sounds().stream().anyMatch(sound -> sound.discards() != 1)) {
                throw new AssertionError("Real retained-session retry did not converge: channels="
                        + streamingChannelStarts.get() + " policy=" + policy.summary() + " tap="
                        + OpenALTappedAudioInputStream.lifecycleSnapshot() + " stereo="
                        + StereoOpenALHandler.lifecycleSnapshot());
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            ClientMediaPlaybackSessions.clearAll(null);
            if (listenerRegistered) {
                NeoForge.EVENT_BUS.unregister(streamingListener);
                listenerRegistered = false;
            }
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
        }

        private void accept(BenchClientContext context, String transport, long elapsedMillis) {
            String transportUrl = properties.url() + "#ncpb-transport-" + transport;
            ClientMediaSyncPayload payload = new MP4PlaybackSyncPacket(context.player().getUUID(), sourceId,
                    ClientMediaSyncPayload.SOURCE_PLAYER, context.player().getId(), context.player().getX(),
                    context.player().getY(), context.player().getZ(), true, 0, transportUrl, transportUrl,
                    "real MP3 retained retry", (int) (TOTAL_MILLIS / 1_000L), 1_000,
                    retainedSession.value(), elapsedMillis, false);
            ClientMediaSyncHandler.handleSync(payload, policy);
        }

        private boolean ready(BenchClientContext context, int soundIndex, int expectedStarts,
                StereoOpenALHandler.DiagnosticSnapshot output) {
            return policy.sounds().size() > soundIndex && policy.sound(soundIndex).streamReady()
                    && context.minecraft().getSoundManager().isActive(policy.sound(soundIndex))
                    && streamingChannelStarts.get() == expectedStarts && output != null && output.started()
                    && output.firstPcm().samples() >= 1_024L;
        }

        private void requireRetainedSession(long expectedElapsedMillis) {
            ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(sourceId);
            if (active == null || !active.playbackSessionId().filter(retainedSession::equals).isPresent()
                    || active.timelineSnapshot().serverMillis() != expectedElapsedMillis) {
                throw new AssertionError("Transport refresh changed the logical playback session: " + active);
            }
        }

        private boolean resourcesConverged(BenchClientContext context) {
            OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
            StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
            return !ClientMediaPlaybackRegistry.contains(sourceId) && ClientMediaSoundRegistry.get(sourceId) == null
                    && !ClientMediaRetryHandler.isPending(sourceId, retainedSession)
                    && policy.sounds().stream().noneMatch(context.minecraft().getSoundManager()::isActive)
                    && tap.activeInstances() == tapBaseline.activeInstances()
                    && tap.closesCompleted() >= tapBaseline.closesCompleted() + 2L
                    && stereo.activeInstances() == stereoBaseline.activeInstances()
                    && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 2L
                    && ClientAudioOutputRegistry.getOwnerStereoSnapshot(sourceId).isEmpty()
                    && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                    && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                    && MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING).currentBytes()
                            == audioStagingBaseline;
        }

        private void record(BenchClientContext context, StereoOpenALHandler.DiagnosticSnapshot output) {
            context.metrics().record(SOUND_ACTIVE, policy.sounds().stream()
                    .filter(context.minecraft().getSoundManager()::isActive).count());
            context.metrics().record(CHANNEL_STARTS, streamingChannelStarts.get());
            context.metrics().record(PREPARES, policy.prepareCount());
            context.metrics().record(RETRY_DISPATCHES, policy.retryDispatches());
            context.metrics().record(AUDIBLE_MILLIS, output != null ? output.positionMillis() : -1L);
        }

        private void throwIfStreamFailed() {
            Throwable failure = streamFailure.get();
            if (failure != null) {
                throw new AssertionError("Real retained-session MP3 stream failed", failure);
            }
        }

        private final class RetainedRetrySyncPolicy implements ClientMediaSyncPolicy {
            private final CopyOnWriteArrayList<RetainedRetrySound> sounds = new CopyOnWriteArrayList<>();
            private final CopyOnWriteArrayList<String> launchUrls = new CopyOnWriteArrayList<>();
            private final AtomicInteger prepares = new AtomicInteger();
            private final AtomicInteger rebuilds = new AtomicInteger();
            private final AtomicInteger retryDispatches = new AtomicInteger();
            private final ClientMediaRetryPolicy retryPolicy = new ClientMediaRetryPolicy() {
                @Override
                public long retryDelayMillis() {
                    return RETRY_DELAY_MILLIS;
                }

                @Override
                public void scheduleRetry(UUID deviceId, String sessionId,
                        ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
                    tryScheduleRetry(deviceId, sessionId, active, error);
                }

                @Override
                public boolean tryScheduleRetry(UUID deviceId, String sessionId,
                        ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
                    retryDispatches.incrementAndGet();
                    return true;
                }
            };
            private final ClientMediaPreparePolicy preparePolicy = new ClientMediaPreparePolicy() {
                @Override
                public long prepareTimeoutSeconds() {
                    return 30L;
                }

                @Override
                public boolean canHear(UUID ignored, boolean headphoneRouted) {
                    return true;
                }

                @Override
                public void stop(UUID ignored) {
                    ClientMediaPlaybackSessions.stop(sourceId, null);
                }

                @Override
                public boolean allowDolby(ClientMediaSyncPayload payload, UUID ignored) {
                    return false;
                }

                @Override
                public boolean shouldLoadLyrics(ClientMediaSyncPayload payload, UUID ignored) {
                    return false;
                }

                @Override
                public String lyricLogLabel() {
                    return "Bench retained retry";
                }

                @Override
                public SoundInstance createSound(UUID ignored, ClientMediaSyncPayload payload, URL url,
                        LyricRecord lyricRecord, long startOffsetMillis) {
                    RetainedRetrySound sound = new RetainedRetrySound(url, payload.durationSeconds(),
                            payload.sessionId(), startOffsetMillis);
                    sounds.add(sound);
                    if (!ClientMediaSoundRegistry.tryRegister(sourceId, retainedSession, sound)) {
                        throw new AssertionError("Refreshed retained-session sound registration was rejected");
                    }
                    return sound;
                }

                @Override
                public void onLaunch(ClientMediaSyncPayload payload, UUID ignored, long startOffsetMillis,
                        String playUrl) {
                    launchUrls.add(playUrl);
                }
            };

            @Override
            public boolean canHear(UUID ignored, boolean headphoneRouted) {
                return true;
            }

            @Override
            public void stop(UUID ignored) {
                ClientMediaPlaybackSessions.stop(sourceId, null);
            }

            @Override
            public void updateVolume(UUID ignored, float volume) {
                ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
                if (sound != null) {
                    sound.setMediaVolume(volume);
                }
            }

            @Override
            public boolean shouldRebuildSound(UUID ignored, ClientMediaSyncPayload payload) {
                ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
                return sound == null || sound.stopped()
                        || !payload.playbackSessionId().equals(sound.playbackSession());
            }

            @Override
            public void preparePlayback(ClientMediaSyncPayload payload, UUID ignored) {
                prepares.incrementAndGet();
                ClientMediaPrepareLauncher.preparePlaybackAsync(payload, sourceId, preparePolicy);
            }

            @Override
            public void onRebuildSound(ClientMediaSyncPayload payload, UUID ignored) {
                rebuilds.incrementAndGet();
            }

            ClientMediaRetryPolicy retryPolicy() {
                return retryPolicy;
            }

            int prepareCount() {
                return prepares.get();
            }

            int rebuildCount() {
                return rebuilds.get();
            }

            int retryDispatches() {
                return retryDispatches.get();
            }

            List<RetainedRetrySound> sounds() {
                return List.copyOf(sounds);
            }

            RetainedRetrySound sound(int index) {
                return sounds.get(index);
            }

            List<String> launchUrls() {
                return List.copyOf(launchUrls);
            }

            String summary() {
                return "prepares=" + prepareCount() + ", rebuilds=" + rebuildCount() + ", retryDispatches="
                        + retryDispatches() + ", launches=" + launchUrls() + ", discards="
                        + sounds.stream().map(RetainedRetrySound::discards).toList();
            }
        }

        private final class RetainedRetrySound extends SyncedMediaSound implements ClientMediaSoundHandle {
            private final AtomicBoolean streamReady = new AtomicBoolean();
            private final AtomicBoolean transportFailed = new AtomicBoolean();
            private final AtomicBoolean discarded = new AtomicBoolean();
            private final AtomicInteger discards = new AtomicInteger();

            private RetainedRetrySound(URL url, int durationSeconds, String sessionId, long startOffsetMillis) {
                super(url, durationSeconds, null, sessionId, startOffsetMillis);
                relative = true;
                attenuation = SoundInstance.Attenuation.NONE;
                volume = 1.0F;
            }

            @Override
            public void tick() {
                tick++;
            }

            @Override
            public boolean headphoneRouted() {
                return false;
            }

            @Override
            public boolean stopped() {
                return transportFailed.get() || isStopped();
            }

            @Override
            public void discardWithoutFinishing() {
                if (discarded.compareAndSet(false, true)) {
                    discards.incrementAndGet();
                    stop();
                }
            }

            @Override
            public void setMediaVolume(float volume) {
                this.volume = Math.max(0.0F, Math.min(1.0F, volume));
            }

            @Override
            protected void onStreamReady() {
                streamReady.set(true);
            }

            @Override
            protected void onStreamFailure(Exception error) {
                streamFailure.compareAndSet(null, error);
            }

            @Override
            protected void finishSession() {
            }

            @Override
            protected String streamDebugName() {
                return "real retained-session retry MP3";
            }

            void failTransport() {
                transportFailed.set(true);
                stop();
            }

            boolean streamReady() {
                return streamReady.get();
            }

            int discards() {
                return discards.get();
            }
        }
    }

    private static void requirePcmQuality(String phase, StereoOpenALHandler.PcmQuality pcm) {
        if (pcm.samples() < 1_024L || pcm.peak() < 0.001F || pcm.rms() < 0.0001D
                || pcm.peak() > 1.001F || pcm.clippedRatio() >= 0.20D) {
            throw new AssertionError("Decoded PCM quality is implausible during " + phase + ": " + pcm);
        }
    }

    private static final class RealMp3SeekScenario implements BenchClientScenario {
        private static final long FIRST_OFFSET_MILLIS = 5_000L;
        private static final long SECOND_OFFSET_MILLIS = 12_000L;
        private static final long TOTAL_MILLIS = 360_000L;
        private static final BenchMetricDescriptor MEDIA_MILLIS = new BenchMetricDescriptor(
                "ncpb.real_mp3.media_millis", "milliseconds", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor FED_MILLIS = new BenchMetricDescriptor(
                "ncpb.real_mp3.fed_millis", "milliseconds", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor PCM_PEAK = new BenchMetricDescriptor(
                "ncpb.real_mp3.pcm_peak", "ratio", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor PCM_RMS = new BenchMetricDescriptor(
                "ncpb.real_mp3.pcm_rms", "ratio", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor PCM_CLIPPED = new BenchMetricDescriptor(
                "ncpb.real_mp3.pcm_clipped_ratio", "ratio", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor ACTIVE_OUTPUTS = new BenchMetricDescriptor(
                "ncpb.real_mp3.active_outputs", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor PENDING_NATIVE = new BenchMetricDescriptor(
                "ncpb.real_mp3.pending_native_deletes", "count", MetricDirection.LOWER_IS_BETTER);

        private final AudioStreamProperties.RealMp3Bench properties = AudioStreamProperties.realMp3Bench();
        private final PlaybackSessionId firstSession = PlaybackSessionId.of("bench-real-mp3-first");
        private final PlaybackSessionId secondSession = PlaybackSessionId.of("bench-real-mp3-second");
        private RealMp3Stage first;
        private RealMp3Stage second;
        private UUID ownerId;
        private StereoOpenALHandler.LifecycleSnapshot lifecycleBaseline;
        private long audioStagingBaseline;
        private StereoOpenALHandler.PcmQuality latestPcm = emptyPcm();
        private StereoOpenALHandler.PcmQuality firstPcm = emptyPcm();
        private StereoOpenALHandler.PcmQuality secondPcm = emptyPcm();
        private int phase;
        private boolean cleanupRequested;

        @Override
        public void setup(BenchClientContext context) {
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
            ownerId = context.player().getUUID();
            ClientAudioOutputRegistry.setOwnerVolume(ownerId, 1.0F);
            lifecycleBaseline = StereoOpenALHandler.lifecycleSnapshot();
            audioStagingBaseline = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING)
                    .currentBytes();
            first = RealMp3Stage.start(properties.url(), ownerId, firstSession, FIRST_OFFSET_MILLIS);
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
            first.throwIfFailed();
            if (second != null) {
                second.throwIfFailed();
            }
            ClientAudioOutputRegistry.updatePositions(new float[] {
                    (float) context.player().getX(), (float) context.player().getEyeY(),
                    (float) context.player().getZ()
            });

            ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getOwnerAudioTimeline(ownerId);
            StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry.getOwnerStereoSnapshot(ownerId)
                    .orElse(null);
            if (output != null && output.firstPcm().samples() > 0L) {
                latestPcm = output.firstPcm();
            }
            record(context, timeline, latestPcm);

            if (phase == 0 && ready(timeline, output, firstSession, FIRST_OFFSET_MILLIS)) {
                requireHealthy("first seek", timeline, output, firstSession, FIRST_OFFSET_MILLIS);
                firstPcm = output.firstPcm();
                second = RealMp3Stage.start(properties.url(), ownerId, secondSession, SECOND_OFFSET_MILLIS);
                phase = 1;
                return BenchClientStepResult.CONTINUE;
            }
            if (phase == 1 && ready(timeline, output, secondSession, SECOND_OFFSET_MILLIS)) {
                requireHealthy("replacement seek", timeline, output, secondSession, SECOND_OFFSET_MILLIS);
                secondPcm = output.firstPcm();
                StereoOpenALHandler.LifecycleSnapshot lifecycle = StereoOpenALHandler.lifecycleSnapshot();
                if (lifecycle.instancesCreated() < lifecycleBaseline.instancesCreated() + 2L
                        || lifecycle.cleanupsStarted() < lifecycleBaseline.cleanupsStarted() + 1L) {
                    return BenchClientStepResult.CONTINUE;
                }
                first.stop();
                phase = 2;
                return BenchClientStepResult.CONTINUE;
            }
            if (phase == 2 && first.finished()) {
                if (!first.streamClosed()) {
                    throw new AssertionError("Replaced MP3 stream did not close");
                }
                if (!cleanupRequested) {
                    cleanupRequested = true;
                    second.stop();
                    ClientAudioOutputRegistry.cleanup();
                    HttpAudioStreamHandler.closeModernStreams();
                    return BenchClientStepResult.CONTINUE;
                }
                OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
                if (resourcesConverged()) {
                    phase = 3;
                    return BenchClientStepResult.COMPLETE;
                }
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (phase != 3 || !resourcesConverged()) {
                throw new AssertionError("Real MP3/OpenAL resources did not converge: lifecycle="
                        + StereoOpenALHandler.lifecycleSnapshot() + " audio="
                        + AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()) + " pendingNative="
                        + OpenALSpatialAudio.pendingNativeDeleteBatches());
            }
            StereoOpenALHandler.LifecycleSnapshot lifecycle = StereoOpenALHandler.lifecycleSnapshot();
            if (lifecycle.instancesCreated() != lifecycleBaseline.instancesCreated() + 2L
                    || lifecycle.cleanupsStarted() != lifecycleBaseline.cleanupsStarted() + 2L
                    || lifecycle.cleanupsCompleted() != lifecycleBaseline.cleanupsCompleted() + 2L) {
                throw new AssertionError("Each real MP3 output must be cleaned exactly once: baseline="
                        + lifecycleBaseline + " final=" + lifecycle);
            }
            if (firstPcm.samples() == 0L || secondPcm.samples() == 0L) {
                throw new AssertionError("Both real MP3 seek stages must retain scalar PCM verification results");
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            if (first != null) {
                first.stop();
            }
            if (second != null) {
                second.stop();
            }
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
        }

        private boolean resourcesConverged() {
            StereoOpenALHandler.LifecycleSnapshot lifecycle = StereoOpenALHandler.lifecycleSnapshot();
            return first.finished() && second != null && second.finished()
                    && !ClientAudioOutputRegistry.isActive()
                    && lifecycle.activeInstances() == lifecycleBaseline.activeInstances()
                    && lifecycle.cleanupsCompleted() >= lifecycleBaseline.cleanupsCompleted() + 2L
                    && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                    && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                    && MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING).currentBytes()
                            == audioStagingBaseline;
        }

        private static boolean ready(ClientAudioOutputRegistry.AudioTimeline timeline,
                StereoOpenALHandler.DiagnosticSnapshot output, PlaybackSessionId sessionId, long minimumMillis) {
            return output != null && output.started() && output.firstPcm().samples() > 0L
                    && timeline.playbackSessionId().filter(sessionId::equals).isPresent()
                    && timeline.fedMillis() >= minimumMillis;
        }

        private static void requireHealthy(String phase, ClientAudioOutputRegistry.AudioTimeline timeline,
                StereoOpenALHandler.DiagnosticSnapshot output, PlaybackSessionId sessionId, long minimumMillis) {
            if (!ready(timeline, output, sessionId, minimumMillis)) {
                throw new AssertionError("Real MP3 output was not ready during " + phase + ": timeline=" + timeline
                        + " output=" + output);
            }
            if (timeline.fedMillis() > minimumMillis + 60_000L || timeline.mainMillis() < minimumMillis - 1_000L) {
                throw new AssertionError("Real MP3 seek started outside the target window during " + phase
                        + ": target=" + minimumMillis + " timeline=" + timeline);
            }
            requirePcmQuality(phase, output.firstPcm());
        }

        private static void record(BenchClientContext context, ClientAudioOutputRegistry.AudioTimeline timeline,
                StereoOpenALHandler.PcmQuality pcm) {
            context.metrics().record(MEDIA_MILLIS, Math.max(0L, timeline.mainMillis()));
            context.metrics().record(FED_MILLIS, Math.max(0L, timeline.fedMillis()));
            context.metrics().record(PCM_PEAK, pcm.peak());
            context.metrics().record(PCM_RMS, pcm.rms());
            context.metrics().record(PCM_CLIPPED, pcm.clippedRatio());
            context.metrics().record(ACTIVE_OUTPUTS, StereoOpenALHandler.lifecycleSnapshot().activeInstances());
            context.metrics().record(PENDING_NATIVE, OpenALSpatialAudio.pendingNativeDeleteBatches());
        }

        private static StereoOpenALHandler.PcmQuality emptyPcm() {
            return new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
        }

        private static final class RealMp3Stage {
            private static final int READ_BUFFER_BYTES = 32 * 1024;
            private final AtomicReference<AudioInputStream> stream = new AtomicReference<>();
            private final AtomicReference<Throwable> failure = new AtomicReference<>();
            private final AtomicReference<HttpAudioStreamHandler.RegisteredRequest> registered = new AtomicReference<>();
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private final AtomicBoolean stopRequested = new AtomicBoolean();
            private final AtomicBoolean finished = new AtomicBoolean();
            private final Thread reader;

            private RealMp3Stage(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId, long offsetMillis) {
                reader = NetMusicThreadFactory.daemonThread("RealMp3SeekBench-" + sessionId.value(),
                        () -> run(mediaUrl, ownerId, sessionId, offsetMillis));
                reader.start();
            }

            static RealMp3Stage start(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId, long offsetMillis) {
                return new RealMp3Stage(mediaUrl, ownerId, sessionId, offsetMillis);
            }

            private void run(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId, long offsetMillis) {
                try {
                    PlaybackRequest request = PlaybackRequest.now(mediaUrl, null, sessionId.value(), offsetMillis,
                            TOTAL_MILLIS, ownerId, null);
                    HttpAudioStreamHandler.RegisteredRequest requestUrl = HttpAudioStreamHandler.registerRequest(request);
                    registered.set(requestUrl);
                    if (cancelled.get()) {
                        requestUrl.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
                        return;
                    }
                    AudioInputStream opened = new HttpAudioStreamHandler().handle(URI.create(requestUrl.url()).toURL());
                    stream.set(opened);
                    if (!(opened instanceof OpenALTappedAudioInputStream)) {
                        throw new IOException("real MP3 did not enter the modern OpenAL fallback pipeline: "
                                + opened.getClass().getName());
                    }
                    byte[] buffer = new byte[READ_BUFFER_BYTES];
                    while (!cancelled.get() && opened.read(buffer, 0, buffer.length) >= 0) {
                        // Decoding and queue backpressure intentionally stay on this daemon worker.
                    }
                } catch (Throwable error) {
                    if (!cancelled.get()) {
                        failure.compareAndSet(null, error);
                    }
                } finally {
                    closeStream();
                    finished.set(true);
                }
            }

            void stop() {
                cancelled.set(true);
                if (!stopRequested.compareAndSet(false, true)) {
                    return;
                }
                reader.interrupt();
                NetMusicThreadFactory.daemonThread("RealMp3SeekBench-close", () -> {
                    HttpAudioStreamHandler.RegisteredRequest request = registered.get();
                    if (request != null) {
                        request.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
                    }
                    closeStream();
                }).start();
            }

            void throwIfFailed() {
                Throwable error = failure.get();
                if (error != null) {
                    throw new AssertionError("Real MP3 stage failed", error);
                }
            }

            boolean finished() {
                return finished.get();
            }

            boolean streamClosed() {
                AudioInputStream value = stream.get();
                return value instanceof OpenALTappedAudioInputStream tapped && tapped.isClosed();
            }

            private void closeStream() {
                AudioInputStream value = stream.get();
                if (value == null) {
                    return;
                }
                try {
                    value.close();
                } catch (IOException error) {
                    if (!cancelled.get()) {
                        failure.compareAndSet(null, error);
                    }
                }
            }
        }
    }

    private static final class PlaybackSessionRaceScenario implements BenchClientScenario {
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

    private static final class RaceSyncPolicy implements ClientMediaSyncPolicy {
        private final List<RaceSound> sounds = new ArrayList<>();
        private final AtomicInteger prepares = new AtomicInteger();
        private final AtomicInteger rebuilds = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();
        private final AtomicInteger retryDispatches = new AtomicInteger();
        private final ClientMediaRetryPolicy retryPolicy = new ClientMediaRetryPolicy() {
            @Override
            public long retryDelayMillis() {
                return 0L;
            }

            @Override
            public void scheduleRetry(UUID deviceId, String sessionId,
                    ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
                tryScheduleRetry(deviceId, sessionId, active, error);
            }

            @Override
            public boolean tryScheduleRetry(UUID deviceId, String sessionId,
                    ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
                retryDispatches.incrementAndGet();
                return true;
            }
        };

        @Override
        public boolean canHear(UUID sourceId, boolean headphoneRouted) {
            return true;
        }

        @Override
        public void stop(UUID sourceId) {
            stops.incrementAndGet();
            ClientMediaPlaybackSessions.stop(sourceId, null);
        }

        @Override
        public void updateVolume(UUID sourceId, float volume) {
            ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
            if (sound != null) {
                sound.setMediaVolume(volume);
            }
        }

        @Override
        public boolean shouldRebuildSound(UUID sourceId, ClientMediaSyncPayload payload) {
            ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
            return sound == null || sound.stopped() || !payload.playbackSessionId().equals(sound.playbackSession());
        }

        @Override
        public void preparePlayback(ClientMediaSyncPayload payload, UUID sourceId) {
            RaceSound sound = new RaceSound(payload.sessionId());
            sounds.add(sound);
            prepares.incrementAndGet();
            if (!ClientMediaSoundRegistry.tryRegister(sourceId, payload.sessionId(), sound)) {
                throw new AssertionError("Deterministic sound registration was rejected: " + payload.sessionId());
            }
        }

        @Override
        public void onRebuildSound(ClientMediaSyncPayload payload, UUID sourceId) {
            rebuilds.incrementAndGet();
        }

        ClientMediaRetryPolicy retryPolicy() {
            return retryPolicy;
        }

        List<RaceSound> sounds() {
            return List.copyOf(sounds);
        }

        RaceSound latestSound() {
            return sounds.getLast();
        }

        int prepareCount() {
            return prepares.get();
        }

        int rebuildCount() {
            return rebuilds.get();
        }

        int stopCount() {
            return stops.get();
        }

        int retryDispatches() {
            return retryDispatches.get();
        }

        int totalDiscards() {
            return sounds.stream().mapToInt(RaceSound::discards).sum();
        }

        String summary() {
            return "prepares=" + prepareCount() + ", rebuilds=" + rebuildCount() + ", stops=" + stopCount()
                    + ", retryDispatches=" + retryDispatches() + ", discards="
                    + sounds.stream().map(RaceSound::discards).toList();
        }
    }

    private static final class RaceSound implements ClientMediaSoundHandle {
        private final String sessionId;
        private final AtomicBoolean transportFailed = new AtomicBoolean();
        private final AtomicBoolean discarded = new AtomicBoolean();
        private final AtomicInteger discards = new AtomicInteger();

        private RaceSound(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public boolean headphoneRouted() {
            return false;
        }

        @Override
        public boolean stopped() {
            return transportFailed.get() || discarded.get();
        }

        @Override
        public void discardWithoutFinishing() {
            if (discarded.compareAndSet(false, true)) {
                discards.incrementAndGet();
            }
        }

        @Override
        public void setMediaVolume(float volume) {
        }

        void failTransport() {
            transportFailed.set(true);
        }

        int discards() {
            return discards.get();
        }
    }

    /**
     * Drives the production projection candidate loop with a real AV1/H.264 plan and a deterministic AV1 startup
     * failure. The AV1 decoder must physically terminate before H.264 opens; the replacement must retain the
     * original playback session and non-zero media offset, then every owned native/GPU resource must converge.
     */
    private static final class RealAv1H264FallbackScenario implements BenchClientScenario {
        private static final long START_OFFSET_MILLIS = 5_000L;
        private static final String REJECTED_AV1_URL =
                "http://127.0.0.1:1/ncpb-bench-av1-startup-failure.m4s";
        private static final BenchMetricDescriptor FALLBACK_CODEC = new BenchMetricDescriptor(
                "ncpb.real_av1_fallback.codec_id", "codec", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor MEDIA_MILLIS = new BenchMetricDescriptor(
                "ncpb.real_av1_fallback.media_millis", "milliseconds", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor OWNED_BYTES = new BenchMetricDescriptor(
                "ncpb.real_av1_fallback.owned_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor NATIVE_BYTES = new BenchMetricDescriptor(
                "ncpb.real_av1_fallback.native_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

        private final VideoFeatureProperties.RealMediaLifecycle properties =
                VideoFeatureProperties.realMediaLifecycle();
        private final AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
        private CompletableFuture<FallbackMedia> resolution;
        private FallbackMedia media;
        private BlockPos projectorPos;
        private long[] memoryBaseline;
        private VideoBillboardPreview.BenchUploadResources uploadBaseline;
        private VideoNativeDecoder.NativeMemoryStats nativeBaseline;
        private int closeBaseline;
        private int httpBaseline;
        private int ticks;
        private boolean started;
        private boolean fallbackObserved;
        private boolean stopIssued;
        private boolean converged;
        private String observedBackend = "unknown";
        private String observedReason = "";
        private long observedMediaMillis = -1L;

        @Override
        public void setup(BenchClientContext context) {
            cleanup();
            projectorPos = context.player().blockPosition().relative(Direction.NORTH, 2).immutable();
            resolution = CompletableFuture.supplyAsync(this::resolveMedia)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            resolutionFailure.compareAndSet(null,
                                    RealMediaLifecycleScenario.unwrapCompletion(error));
                        }
                    });
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            tickClosures();
            throwResolutionFailure();
            if (!context.environment().readiness().ready() || context.frames().sampleCount() < 2
                    || resolution == null || !resolution.isDone()) {
                return BenchClientStepResult.CONTINUE;
            }
            if (media == null) {
                media = resolution.join();
            }
            if (!idleForBaseline()) {
                return BenchClientStepResult.CONTINUE;
            }
            memoryBaseline = RealMediaLifecycleScenario.currentOwnedBytesByCategory();
            uploadBaseline = VideoBillboardPreview.benchUploadResources();
            nativeBaseline = VideoNativeDecoder.nativeMemoryStats();
            closeBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
            httpBaseline = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests();
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            ticks++;
            tickClosures();
            throwResolutionFailure();
            record(context);
            if (!started) {
                started = true;
                VideoBillboardPreview.startSyncedCandidates(
                        media.candidates(), media.width(), media.height(), media.fps(), media.sessionId(),
                        START_OFFSET_MILLIS, media.durationMillis(), List.of(projectorPos), projectorPos,
                        true, null);
                // The benchmark does not place a projector block. Keep a GUI
                // consumer attached so the real candidate loop and upload pump
                // remain active while the injected backend rejection settles.
                VideoBillboardPreview.pumpPreviewFrame(media.sessionId());
                return BenchClientStepResult.CONTINUE;
            }
            VideoBillboardPreview.pumpPreviewFrame(media.sessionId());
            if (!fallbackObserved) {
                VideoBillboardPreview.VideoStatus status = VideoBillboardPreview.getStatusForProjector(projectorPos);
                VideoBillboardPreview.VideoSyncStatus sync = VideoBillboardPreview.getSyncStatus(media.sessionId());
                if (status.hasFrame()) {
                    if (status.codecId() != 7) {
                        throw new AssertionError("Expected real AV1 hardware rejection to select H.264, got "
                                + status);
                    }
                    if (status.fallbackReason().isBlank()) {
                        throw new AssertionError("AV1 to H.264 fallback did not expose a stable reason: " + status);
                    }
                    if (!sync.running() || sync.mediaMillis() < START_OFFSET_MILLIS - 1_000L) {
                        throw new AssertionError("Fallback reset or detached the session timeline: " + sync);
                    }
                    fallbackObserved = true;
                    observedBackend = status.backend();
                    observedReason = status.fallbackReason();
                    observedMediaMillis = sync.mediaMillis();
                    VideoBillboardPreview.stopIfSession(media.sessionId());
                    stopIssued = true;
                }
            } else if (resourcesAtBaseline()) {
                converged = true;
                record(context);
                return BenchClientStepResult.COMPLETE;
            }
            if (ticks > 1_200) {
                throw new AssertionError("Real AV1 fallback timed out: " + describe());
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (!started || !fallbackObserved || !stopIssued || !converged) {
                throw new AssertionError("Real AV1 fallback did not complete: " + describe());
            }
            if (observedBackend.equalsIgnoreCase("unknown") || observedMediaMillis < START_OFFSET_MILLIS - 1_000L) {
                throw new AssertionError("Fallback backend/timeline evidence is incomplete: " + describe());
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            cleanup();
        }

        private FallbackMedia resolveMedia() {
            try {
                BiliApiClient.VideoId id = BiliApiClient.extractVideoId(properties.videoId());
                if (id == null) {
                    throw new IOException("invalid Bilibili video id: " + properties.videoId());
                }
                BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(id);
                BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(
                        id, info.cid(), properties.quality());
                List<BiliVideoStreamResolver.VideoCandidate> candidates = plan.candidateOrder().stream()
                        .map(candidate -> {
                            BiliApiClient.VideoStream stream = candidate.stream();
                            BiliVideoStreamResolver.DecodeMode mode = switch (candidate.decodePreference()) {
                                case HARDWARE_REQUIRED -> BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED;
                                case AUTO -> BiliVideoStreamResolver.DecodeMode.AUTO;
                                case SOFTWARE_ONLY -> BiliVideoStreamResolver.DecodeMode.SOFTWARE_ONLY;
                            };
                            return new BiliVideoStreamResolver.VideoCandidate(stream.baseUrl(), stream.codecId(),
                                    Math.max(1, stream.width()), Math.max(1, stream.height()),
                                    BiliVideoStreamResolver.parseFrameRate(stream.frameRate(), 30),
                                    stream.quality(), mode);
                        })
                        .toList();
                BiliVideoStreamResolver.VideoCandidate av1 = candidates.stream()
                        .filter(candidate -> candidate.codecId() == 13
                                && candidate.decodeMode() == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED)
                        .findFirst().orElseThrow(() -> new IOException("playurl plan has no AV1 hardware candidate"));
                BiliVideoStreamResolver.VideoCandidate h264 = candidates.stream()
                        .filter(candidate -> candidate.codecId() == 7)
                        .findFirst().orElseThrow(() -> new IOException("playurl plan has no H.264 fallback"));
                // Keep the real candidate metadata and production codec-13
                // native construction, but make its transport fail immediately
                // and deterministically on every host. This validates the
                // physical close barrier plus the real H.264 fallback without
                // assuming that the current machine lacks AV1 hardware.
                BiliVideoStreamResolver.VideoCandidate rejectedAv1 =
                        new BiliVideoStreamResolver.VideoCandidate(
                                REJECTED_AV1_URL, av1.codecId(), av1.sourceWidth(), av1.sourceHeight(),
                                av1.fps(), av1.quality(), av1.decodeMode());
                return new FallbackMedia("bench-real-av1-h264-fallback", Math.max(1L, info.duration() * 1_000L),
                        Math.max(1, av1.sourceWidth()), Math.max(1, av1.sourceHeight()),
                        Math.max(1, av1.fps()), List.of(rejectedAv1, h264));
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }

        private void record(BenchClientContext context) {
            VideoBillboardPreview.VideoStatus status = VideoBillboardPreview.getStatusForProjector(projectorPos);
            VideoNativeDecoder.NativeMemoryStats stats = VideoNativeDecoder.nativeMemoryStats();
            context.metrics().record(FALLBACK_CODEC, status.codecId());
            long currentMediaMillis = VideoBillboardPreview.getSyncStatus(
                    media != null ? media.sessionId() : "").mediaMillis();
            context.metrics().record(MEDIA_MILLIS,
                    Math.max(0L, Math.max(observedMediaMillis, currentMediaMillis)));
            context.metrics().record(OWNED_BYTES, RealMediaLifecycleScenario.currentOwnedBytes());
            context.metrics().record(NATIVE_BYTES, stats.available() ? stats.ffmpegCurrentBytes() : 0L);
        }

        private boolean idleForBaseline() {
            return VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                    && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() == 0
                    && !VideoBillboardPreview.benchUploadResources().rgbaTexture()
                    && !VideoBillboardPreview.benchUploadResources().yuvTextures();
        }

        private boolean resourcesAtBaseline() {
            if (VideoBillboardPreview.getSyncStatus(media.sessionId()).running()
                    || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != closeBaseline
                    || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != httpBaseline
                    || !Arrays.equals(memoryBaseline, RealMediaLifecycleScenario.currentOwnedBytesByCategory())) {
                return false;
            }
            VideoBillboardPreview.BenchUploadResources upload = VideoBillboardPreview.benchUploadResources();
            if (upload.rgbaTexture() != uploadBaseline.rgbaTexture()
                    || upload.yuvTextures() != uploadBaseline.yuvTextures()
                    || upload.textureStagingBytes() != uploadBaseline.textureStagingBytes()
                    || upload.gpuPboBytes() != uploadBaseline.gpuPboBytes()) {
                return false;
            }
            VideoNativeDecoder.NativeMemoryStats current = VideoNativeDecoder.nativeMemoryStats();
            return !nativeBaseline.available() || !current.available()
                    || current.ffmpegCurrentBytes() == nativeBaseline.ffmpegCurrentBytes()
                    && current.d3d11TextureCurrent() == nativeBaseline.d3d11TextureCurrent()
                    && current.d3d11SurfaceCurrent() == nativeBaseline.d3d11SurfaceCurrent()
                    && current.d3d11LogicalBytesCurrent() == nativeBaseline.d3d11LogicalBytesCurrent();
        }

        private void tickClosures() {
            VideoCloseDiagnostics.tickGlobal();
        }

        private void throwResolutionFailure() {
            Throwable error = resolutionFailure.get();
            if (error != null) {
                throw new AssertionError("Failed to resolve real AV1/H.264 fallback media", error);
            }
        }

        private void cleanup() {
            if (media != null) {
                VideoBillboardPreview.stopIfSession(media.sessionId());
            }
            VideoBillboardPreview.stop();
            VideoBillboardPreview.releaseBenchUploadResources();
        }

        private String describe() {
            VideoNativeDecoder.NativeMemoryStats nativeStats = VideoNativeDecoder.nativeMemoryStats();
            return "started=" + started + " fallback=" + fallbackObserved + " stop=" + stopIssued
                    + " converged=" + converged + " backend=" + observedBackend + " reason=" + observedReason
                    + " mediaMillis=" + observedMediaMillis + " status="
                    + VideoBillboardPreview.getStatusForProjector(projectorPos) + " upload="
                    + VideoBillboardPreview.benchUploadResources() + " native=" + nativeStats
                    + " closes=" + VideoCloseDiagnostics.global().snapshot(System.nanoTime())
                    + " http=" + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime())
                    + " memory=" + Arrays.toString(RealMediaLifecycleScenario.currentOwnedBytesByCategory());
        }

    private record FallbackMedia(String sessionId, long durationMillis, int width, int height, int fps,
                List<BiliVideoStreamResolver.VideoCandidate> candidates) {
        }
    }

    /**
     * Proves real AV1 hardware decode and a production in-instance forward range seek. The scenario keeps one
     * session and one owner, requires a new decoder generation at the requested offset, rejects any displayed PTS
     * regression, then waits for native/GPU/queue/HTTP ownership to return to the captured baseline.
     */
    private static final class RealAv1HardwareSeekScenario implements BenchClientScenario {
        private static final long LIVE_START_OFFSET_MILLIS = 5_000L;
        private static final long FIXTURE_SEEK_OFFSET_MILLIS = 35_000L;
        private static final long MIN_SEGMENT_ADVANCE_MILLIS = 2_000L;
        private static final int MIN_DISTINCT_PTS_SAMPLES = 12;
        private static final BenchMetricDescriptor MEDIA_MILLIS = new BenchMetricDescriptor(
                "ncpb.real_av1_hardware_seek.media_millis", "milliseconds", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor GENERATION = new BenchMetricDescriptor(
                "ncpb.real_av1_hardware_seek.generation", "generation", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor NATIVE_BYTES = new BenchMetricDescriptor(
                "ncpb.real_av1_hardware_seek.native_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

        private final boolean frozenFixture;
        private final VideoFeatureProperties.RealMediaLifecycle properties =
                VideoFeatureProperties.realMediaLifecycle();
        private final AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
        private CompletableFuture<Av1Media> resolution;
        private Av1Media media;
        private BlockPos projectorPos;
        private long[] memoryBaseline;
        private VideoBillboardPreview.BenchUploadResources uploadBaseline;
        private VideoNativeDecoder.NativeMemoryStats nativeBaseline;
        private int closeBaseline;
        private long failedCloseBaseline;
        private int httpBaseline;
        private int ticks;
        private boolean started;
        private boolean initialPlaybackObserved;
        private boolean seekIssued;
        private boolean seekObserved;
        private boolean stopIssued;
        private boolean converged;
        private long initialMediaMillis = -1L;
        private long lastMediaMillis = -1L;
        private long postSeekMediaMillis = -1L;
        private long seekTargetMillis = -1L;
        private long generationBeforeSeek = -1L;
        private int preSeekPtsSamples;
        private int postSeekPtsSamples;
        private String observedBackend = "unknown";
        private FrozenRealAv1RangeServer fixtureServer;

        private RealAv1HardwareSeekScenario(boolean frozenFixture) {
            this.frozenFixture = frozenFixture;
        }

        @Override
        public void setup(BenchClientContext context) {
            cleanup();
            projectorPos = context.player().blockPosition().relative(Direction.NORTH, 3).immutable();
            resolution = (frozenFixture
                    ? CompletableFuture.supplyAsync(this::resolveFrozenMedia)
                    : CompletableFuture.supplyAsync(this::resolveMedia))
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            resolutionFailure.compareAndSet(null,
                                    RealMediaLifecycleScenario.unwrapCompletion(error));
                        }
                    });
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            tickClosures();
            throwResolutionFailure();
            if (!context.environment().readiness().ready() || context.frames().sampleCount() < 2
                    || resolution == null || !resolution.isDone()) {
                return BenchClientStepResult.CONTINUE;
            }
            if (media == null) {
                media = resolution.join();
            }
            if (!idleForBaseline()) {
                return BenchClientStepResult.CONTINUE;
            }
            memoryBaseline = RealMediaLifecycleScenario.currentOwnedBytesByCategory();
            uploadBaseline = VideoBillboardPreview.benchUploadResources();
            nativeBaseline = VideoNativeDecoder.nativeMemoryStats();
            closeBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
            failedCloseBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).failedConvergences();
            httpBaseline = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests();
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            ticks++;
            tickClosures();
            throwResolutionFailure();
            record(context);
            if (!started) {
                started = true;
                startAt(frozenFixture ? 0L : LIVE_START_OFFSET_MILLIS);
                return BenchClientStepResult.CONTINUE;
            }
            VideoBillboardPreview.pumpPreviewFrame(media.sessionId());
            VideoBillboardPreview.VideoStatus status = VideoBillboardPreview.getStatusForProjector(projectorPos);
            VideoBillboardPreview.VideoSyncStatus sync = VideoBillboardPreview.getSyncStatus(media.sessionId());
            VideoBillboardPreview.BenchDecoderState decoderState =
                    VideoBillboardPreview.benchDecoderState(media.sessionId());
            if (VideoBillboardPreview.resourceDiagnostics().failedInstances() > 0) {
                throw new AssertionError("Real AV1 hardware decoder entered terminal failure: " + describe());
            }
            if (status.hasFrame()) {
                validateAv1Hardware(status);
                observePts(sync.mediaMillis());
            }
            long startOffsetMillis = frozenFixture ? 0L : LIVE_START_OFFSET_MILLIS;
            if (!initialPlaybackObserved && status.hasFrame() && sync.mediaMillis() >= startOffsetMillis - 500L) {
                initialPlaybackObserved = true;
                initialMediaMillis = sync.mediaMillis();
                lastMediaMillis = initialMediaMillis;
                observedBackend = status.backend();
            } else if (initialPlaybackObserved && !seekIssued
                    && sync.mediaMillis() - initialMediaMillis >= MIN_SEGMENT_ADVANCE_MILLIS
                    && preSeekPtsSamples >= MIN_DISTINCT_PTS_SAMPLES) {
                seekTargetMillis = frozenFixture
                        ? FIXTURE_SEEK_OFFSET_MILLIS
                        : Math.min(media.durationMillis() - 5_000L, sync.mediaMillis() + 20_000L);
                if (seekTargetMillis <= sync.mediaMillis() + 10_000L) {
                    throw new AssertionError("Resolved media is too short for a forward range seek: " + describe());
                }
                generationBeforeSeek = decoderState.generation();
                seekIssued = true;
                startAt(seekTargetMillis);
            } else if (seekIssued && !seekObserved
                    && decoderState.generation() > generationBeforeSeek
                    && "ACTIVE".equals(decoderState.restartState())
                    && decoderState.decoderStartOffsetMillis() == seekTargetMillis
                    && status.hasFrame() && sync.mediaMillis() >= seekTargetMillis - 500L) {
                seekObserved = true;
                postSeekMediaMillis = sync.mediaMillis();
            } else if (seekObserved && !stopIssued
                    && sync.mediaMillis() - postSeekMediaMillis >= MIN_SEGMENT_ADVANCE_MILLIS
                    && postSeekPtsSamples >= MIN_DISTINCT_PTS_SAMPLES) {
                VideoBillboardPreview.stopIfSession(media.sessionId());
                stopIssued = true;
            } else if (stopIssued && resourcesAtBaseline()) {
                converged = true;
                record(context);
                return BenchClientStepResult.COMPLETE;
            }
            if (stopIssued && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).failedConvergences()
                    != failedCloseBaseline) {
                throw new AssertionError("Real AV1 hardware close converged exceptionally: " + describe());
            }
            VideoCloseDiagnostics.Snapshot closeSnapshot = VideoCloseDiagnostics.global().snapshot(
                    System.nanoTime());
            if (stopIssued && closeSnapshot.activeOperations() != closeBaseline
                    && closeSnapshot.oldestPendingNanos() >= TimeUnit.SECONDS.toNanos(7L)) {
                throw new AssertionError("Real AV1 hardware close did not converge: " + describe());
            }
            if (ticks > 2_400) {
                throw new AssertionError("Real AV1 hardware seek timed out: " + describe());
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (!started || !initialPlaybackObserved || !seekIssued || !seekObserved || !stopIssued || !converged) {
                throw new AssertionError("Real AV1 hardware seek did not complete: " + describe());
            }
            if (isSoftwareBackend(observedBackend) || preSeekPtsSamples < MIN_DISTINCT_PTS_SAMPLES
                    || postSeekPtsSamples < MIN_DISTINCT_PTS_SAMPLES) {
                throw new AssertionError("AV1 hardware/PTS evidence is incomplete: " + describe());
            }
            if (frozenFixture && (fixtureServer == null || fixtureServer.fullRequests() < 1
                    || fixtureServer.rangeRequests() < 3
                    || !fixtureServer.servedRangeStartingAt(FrozenRealAv1RangeServer.SEEK_FRAGMENT_START))) {
                throw new AssertionError("Frozen AV1 fixture did not exercise the exact 35s byte range: "
                        + describe());
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            cleanup();
        }

        private void startAt(long offsetMillis) {
            VideoBillboardPreview.startSyncedCandidates(
                    media.candidates(), media.width(), media.height(), media.fps(), media.sessionId(),
                    offsetMillis, media.durationMillis(), List.of(projectorPos), projectorPos, true, null);
            VideoBillboardPreview.pumpPreviewFrame(media.sessionId());
        }

        private Av1Media resolveMedia() {
            try {
                BiliApiClient.VideoId id = BiliApiClient.extractVideoId(properties.videoId());
                if (id == null) {
                    throw new IOException("invalid Bilibili video id: " + properties.videoId());
                }
                BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(id);
                BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(
                        id, info.cid(), properties.quality());
                BiliVideoStreamResolver.VideoCandidate candidate = plan.candidateOrder().stream()
                        .filter(value -> value.stream().codecId() == BiliApiClient.CODEC_AV1
                                && value.decodePreference() == BiliApiClient.VideoDecodePreference.HARDWARE_REQUIRED)
                        .map(value -> {
                            BiliApiClient.VideoStream stream = value.stream();
                            return new BiliVideoStreamResolver.VideoCandidate(stream.baseUrl(), stream.codecId(),
                                    Math.max(1, stream.width()), Math.max(1, stream.height()),
                                    BiliVideoStreamResolver.parseFrameRate(stream.frameRate(), 30), stream.quality(),
                                    BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED);
                        })
                        .findFirst().orElseThrow(() -> new IOException("playurl plan has no AV1 hardware candidate"));
                return new Av1Media("bench-real-av1-hardware-seek", Math.max(1L, info.duration() * 1_000L),
                        candidate.sourceWidth(), candidate.sourceHeight(), candidate.fps(), List.of(candidate));
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }

        private Av1Media resolveFrozenMedia() {
            try {
                fixtureServer = FrozenRealAv1RangeServer.start();
                String videoUrl = fixtureServer.videoUrl().toString();
                Fmp4NativeVideoDecoder.registerSegmentBase(videoUrl, 0L, 939L, 992L, 1_491L);
                BiliVideoStreamResolver.VideoCandidate candidate = new BiliVideoStreamResolver.VideoCandidate(
                        videoUrl, BiliApiClient.CODEC_AV1, 682, 360, 25, 16,
                        BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED);
                return new Av1Media("bench-frozen-real-av1-hardware-seek", 40_000L,
                        682, 360, 25, List.of(candidate));
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }

        private void validateAv1Hardware(VideoBillboardPreview.VideoStatus status) {
            if (status.codecId() != BiliApiClient.CODEC_AV1) {
                throw new AssertionError("Expected AV1 after startup/seek, got " + status);
            }
            if (isSoftwareBackend(status.backend())) {
                throw new AssertionError("AV1 hardware scenario selected a non-hardware backend: " + status);
            }
        }

        private void observePts(long mediaMillis) {
            if (mediaMillis < 0L || stopIssued) {
                return;
            }
            if (lastMediaMillis >= 0L && mediaMillis < lastMediaMillis) {
                throw new AssertionError("Displayed AV1 PTS regressed from " + lastMediaMillis + " to " + mediaMillis
                        + ": " + describe());
            }
            if (mediaMillis != lastMediaMillis) {
                if (seekObserved) {
                    postSeekPtsSamples++;
                } else if (!seekIssued) {
                    preSeekPtsSamples++;
                }
                lastMediaMillis = mediaMillis;
            }
        }

        private static boolean isSoftwareBackend(String backend) {
            String normalized = backend == null ? "" : backend.trim().toLowerCase(Locale.ROOT);
            return normalized.isEmpty() || normalized.equals("unknown") || normalized.equals("none")
                    || normalized.equals("off") || normalized.startsWith("cpu")
                    || normalized.contains("software") || normalized.contains("dav1d");
        }

        private void record(BenchClientContext context) {
            String sessionId = media != null ? media.sessionId() : "";
            VideoBillboardPreview.VideoSyncStatus sync = VideoBillboardPreview.getSyncStatus(sessionId);
            VideoBillboardPreview.BenchDecoderState state = VideoBillboardPreview.benchDecoderState(sessionId);
            VideoNativeDecoder.NativeMemoryStats stats = VideoNativeDecoder.nativeMemoryStats();
            context.metrics().record(MEDIA_MILLIS, Math.max(0L, sync.mediaMillis()));
            context.metrics().record(GENERATION, Math.max(0L, state.generation()));
            context.metrics().record(NATIVE_BYTES, stats.available() ? stats.ffmpegCurrentBytes() : 0L);
        }

        private boolean idleForBaseline() {
            return VideoBillboardPreview.resourceDiagnostics().instances() == 0
                    && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                    && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() == 0
                    && !VideoBillboardPreview.benchUploadResources().rgbaTexture()
                    && !VideoBillboardPreview.benchUploadResources().yuvTextures();
        }

        private boolean resourcesAtBaseline() {
            if (VideoBillboardPreview.getSyncStatus(media.sessionId()).running()
                    || VideoBillboardPreview.resourceDiagnostics().instances() != 0
                    || VideoBillboardPreview.resourceDiagnostics().activeCloseZombies() != 0
                    || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != closeBaseline
                    || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).failedConvergences()
                            != failedCloseBaseline
                    || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != httpBaseline
                    || !Arrays.equals(memoryBaseline, RealMediaLifecycleScenario.currentOwnedBytesByCategory())) {
                return false;
            }
            VideoBillboardPreview.BenchUploadResources upload = VideoBillboardPreview.benchUploadResources();
            if (upload.rgbaTexture() != uploadBaseline.rgbaTexture()
                    || upload.yuvTextures() != uploadBaseline.yuvTextures()
                    || upload.textureStagingBytes() != uploadBaseline.textureStagingBytes()
                    || upload.gpuPboBytes() != uploadBaseline.gpuPboBytes()) {
                return false;
            }
            VideoNativeDecoder.NativeMemoryStats current = VideoNativeDecoder.nativeMemoryStats();
            return !nativeBaseline.available() || !current.available()
                    || current.ffmpegCurrentBytes() == nativeBaseline.ffmpegCurrentBytes()
                    && current.d3d11TextureCurrent() == nativeBaseline.d3d11TextureCurrent()
                    && current.d3d11SurfaceCurrent() == nativeBaseline.d3d11SurfaceCurrent()
                    && current.d3d11LogicalBytesCurrent() == nativeBaseline.d3d11LogicalBytesCurrent();
        }

        private void tickClosures() {
            VideoCloseDiagnostics.tickGlobal();
        }

        private void throwResolutionFailure() {
            Throwable error = resolutionFailure.get();
            if (error != null) {
                throw new AssertionError("Failed to resolve real AV1 hardware media", error);
            }
        }

        private void cleanup() {
            if (media != null) {
                VideoBillboardPreview.stopIfSession(media.sessionId());
            }
            VideoBillboardPreview.stop();
            VideoBillboardPreview.releaseBenchUploadResources();
            if (fixtureServer != null) {
                fixtureServer.close();
                fixtureServer = null;
            }
        }

        private String describe() {
            String sessionId = media != null ? media.sessionId() : "";
            return "started=" + started + " initial=" + initialPlaybackObserved + " seekIssued=" + seekIssued
                    + " seekObserved=" + seekObserved + " stop=" + stopIssued + " converged=" + converged
                    + " backend=" + observedBackend + " media=" + lastMediaMillis + " target=" + seekTargetMillis
                    + " samples=" + preSeekPtsSamples + "/" + postSeekPtsSamples + " status="
                    + VideoBillboardPreview.getStatusForProjector(projectorPos) + " sync="
                    + VideoBillboardPreview.getSyncStatus(sessionId) + " decoder="
                    + VideoBillboardPreview.benchDecoderState(sessionId) + " resources="
                    + VideoBillboardPreview.resourceDiagnostics() + " upload="
                    + VideoBillboardPreview.benchUploadResources() + " native="
                    + VideoNativeDecoder.nativeMemoryStats() + " closes="
                    + VideoCloseDiagnostics.global().snapshot(System.nanoTime()) + " activeCloses="
                    + VideoCloseDiagnostics.global().activeDescriptions(System.nanoTime()) + " http="
                    + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()) + " memory="
                    + Arrays.toString(RealMediaLifecycleScenario.currentOwnedBytesByCategory())
                    + " fixture=" + (fixtureServer == null ? "none"
                            : fixtureServer.fullRequests() + "/" + fixtureServer.rangeRequests());
        }

        private record Av1Media(String sessionId, long durationMillis, int width, int height, int fps,
                List<BiliVideoStreamResolver.VideoCandidate> candidates) {
        }
    }

    private static final class RealBvPlaybackScenario implements BenchClientScenario {
        private static final BenchMetricDescriptor DECODED_STAGES = new BenchMetricDescriptor(
                "ncpb.real_bv.decoded_stages", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor DECODED_FRAMES = new BenchMetricDescriptor(
                "ncpb.real_bv.decoded_frames", "count", MetricDirection.HIGHER_IS_BETTER);
        private static final BenchMetricDescriptor AUDIO_SAMPLES = new BenchMetricDescriptor(
                "ncpb.real_bv.audio_samples", "count", MetricDirection.HIGHER_IS_BETTER);
        private static final BenchMetricDescriptor AUDIO_INPUT_SAMPLES = new BenchMetricDescriptor(
                "ncpb.real_bv.audio_input_samples", "count", MetricDirection.HIGHER_IS_BETTER);
        private static final BenchMetricDescriptor AUDIO_PCM_RMS = new BenchMetricDescriptor(
                "ncpb.real_bv.audio_pcm_rms", "ratio", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor ACTIVE_CLOSES = new BenchMetricDescriptor(
                "ncpb.real_bv.active_closes", "count", MetricDirection.LOWER_IS_BETTER);
        private static final String AUDIO_SESSION_ID = "bench-real-bv-audio";

        private final AtomicReference<Throwable> audioResolutionFailure = new AtomicReference<>();
        private CompletableFuture<ResolvedAudio> audioResolution;
        private ResolvedAudio resolvedAudio;
        private RealMediaLifecycleScenario.RealAudioStage audio;
        private PlaybackSessionId audioSession;
        private UUID audioOwner;
        private BiliRealVideoPlaybackBench.RunSnapshot finalSnapshot;
        private StereoOpenALHandler.PcmQuality decodedAudioPcm =
                new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
        private long decodedAudioInputSamples;
        private boolean audioDecoded;
        private boolean cleanupRequested;

        @Override
        public void setup(BenchClientContext context) {
            cleanupMedia();
            audioOwner = context.player().getUUID();
            audioSession = PlaybackSessionId.of(AUDIO_SESSION_ID);
            ClientAudioOutputRegistry.setOwnerVolume(audioOwner, 1.0F);
            audioResolution = CompletableFuture.supplyAsync(this::resolveAudio)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            audioResolutionFailure.compareAndSet(null,
                                    RealMediaLifecycleScenario.unwrapCompletion(error));
                        }
                    });
            if (!BiliRealVideoPlaybackBench.tryStart()) {
                throw new AssertionError("Real BV bench flags are not enabled");
            }
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            tickResourceClosures(context);
            throwAudioResolutionFailure();
            if (!context.environment().readiness().ready() || context.frames().sampleCount() < 2
                    || audioResolution == null || !audioResolution.isDone()) {
                return BenchClientStepResult.CONTINUE;
            }
            if (resolvedAudio == null) {
                resolvedAudio = audioResolution.join();
            }
            if (audio == null) {
                audio = RealMediaLifecycleScenario.RealAudioStage.start(
                        resolvedAudio.audioUrl(), audioOwner, audioSession, resolvedAudio.durationMillis());
            }
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            tickResourceClosures(context);
            throwAudioResolutionFailure();
            if (audio == null) {
                throw new AssertionError("Real BV audio stage was not started");
            }
            audio.throwIfFailed();
            StereoOpenALHandler.DiagnosticSnapshot audioOutput =
                    ClientAudioOutputRegistry.getSessionStereoSnapshot(audioSession).orElse(null);
            if (!audioDecoded && audioOutput != null && audioOutput.started()
                    && audioOutput.firstAudiblePcm().samples() >= 1_024L
                    && audioOutput.inputSamples() > 0L) {
                requirePcmQuality("real Bilibili DASH audio", audioOutput.firstAudiblePcm());
                decodedAudioPcm = audioOutput.firstAudiblePcm();
                decodedAudioInputSamples = audioOutput.inputSamples();
                audioDecoded = true;
            }

            BiliRealVideoPlaybackBench.RunSnapshot snapshot = BiliRealVideoPlaybackBench.snapshot();
            context.metrics().record(DECODED_STAGES, snapshot.decodedStages());
            context.metrics().record(DECODED_FRAMES, snapshot.decodedFrames());
            context.metrics().record(AUDIO_SAMPLES,
                    audioOutput != null ? audioOutput.firstAudiblePcm().samples() : decodedAudioPcm.samples());
            context.metrics().record(AUDIO_INPUT_SAMPLES,
                    audioOutput != null ? audioOutput.inputSamples() : decodedAudioInputSamples);
            context.metrics().record(AUDIO_PCM_RMS,
                    audioOutput != null ? audioOutput.firstAudiblePcm().rms() : decodedAudioPcm.rms());
            var videoClose = VideoCloseDiagnostics.global().snapshot(System.nanoTime());
            context.metrics().record(ACTIVE_CLOSES, videoClose.activeOperations());
            if (snapshot.state() == BiliRealVideoPlaybackBench.RunState.FAILED) {
                throw new AssertionError("Real BV video decode failed: " + snapshot);
            }
            if (snapshot.state() != BiliRealVideoPlaybackBench.RunState.SUCCEEDED || !audioDecoded) {
                return BenchClientStepResult.CONTINUE;
            }
            finalSnapshot = snapshot;
            if (!cleanupRequested) {
                cleanupRequested = true;
                cleanupMedia();
                return BenchClientStepResult.CONTINUE;
            }
            return resourcesConverged(audio) ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (finalSnapshot == null || finalSnapshot.state() != BiliRealVideoPlaybackBench.RunState.SUCCEEDED
                    || finalSnapshot.decodedStages() <= 0 || finalSnapshot.decodedFrames() <= 0) {
                throw new AssertionError("Real BV bench did not decode video: " + finalSnapshot);
            }
            if (!audioDecoded || decodedAudioPcm.samples() < 1_024L || decodedAudioInputSamples <= 0L) {
                throw new AssertionError("Real BV bench did not decode audible audio: pcm=" + decodedAudioPcm
                        + " inputSamples=" + decodedAudioInputSamples);
            }
            if (!finalSnapshot.videoId().equals(VideoFeatureProperties.realMediaLifecycle().videoId())) {
                throw new AssertionError("Real BV bench decoded an unexpected video: " + finalSnapshot);
            }
            if (!resourcesConverged(audio)) {
                throw new AssertionError("Real BV audio/video resources did not converge: video="
                        + ModernTurntableVideoClient.videoLifecycleDiagnostics() + " audio=" + audio
                        + " audioClose=" + AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime())
                        + " openalPending=" + OpenALSpatialAudio.pendingNativeDeleteBatches());
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            cleanupMedia();
        }

        private ResolvedAudio resolveAudio() {
            VideoFeatureProperties.RealMediaLifecycle properties = VideoFeatureProperties.realMediaLifecycle();
            try {
                BiliApiClient.VideoId videoId = BiliApiClient.extractVideoId(properties.videoId());
                if (videoId == null) {
                    throw new IOException("invalid Bilibili video id: " + properties.videoId());
                }
                BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(videoId);
                String audioUrl = BiliApiClient.getBestAudioUrl(videoId, info.cid(), false);
                if (audioUrl == null || audioUrl.isBlank()) {
                    throw new IOException("Bilibili playurl returned an empty DASH audio URL");
                }
                return new ResolvedAudio(Math.max(1L, info.duration() * 1_000L), audioUrl);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }

        private void tickResourceClosures(BenchClientContext context) {
            ClientAudioOutputRegistry.updatePositions(new float[] {
                    (float) context.player().getX(), (float) context.player().getEyeY(),
                    (float) context.player().getZ()
            });
            OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
            VideoCloseDiagnostics.tickGlobal();
        }

        private void throwAudioResolutionFailure() {
            Throwable error = audioResolutionFailure.get();
            if (error != null) {
                throw new AssertionError("Real BV audio resolve failed before decode", error);
            }
        }

        private void cleanupMedia() {
            if (audio != null) {
                audio.stop();
            }
            ModernTurntableVideoClient.clear();
            VideoBillboardPreview.stop();
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
            VideoBillboardPreview.releaseBenchUploadResources();
            OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
        }

        private static boolean resourcesConverged(RealMediaLifecycleScenario.RealAudioStage audio) {
            var lifecycle = ModernTurntableVideoClient.videoLifecycleDiagnostics();
            if (audio == null || !audio.finished() || !audio.streamClosed()
                    || ClientAudioOutputRegistry.isActive()
                    || lifecycle.activeRequests() != 0 || lifecycle.pendingRequests() != 0
                    || lifecycle.resources().instances() != 0
                    || lifecycle.resources().activeCloseZombies() != 0
                    || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0
                    || AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0
                    || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0) {
                return false;
            }
            if (OpenALSpatialAudio.pendingNativeDeleteBatches() != 0) {
                return false;
            }
            for (MemoryResourceTracker.Category category : MemoryResourceTracker.Category.values()) {
                if (MemoryResourceTracker.usage(category).currentBytes() != 0L) {
                    return false;
                }
            }
            return true;
        }

        private record ResolvedAudio(long durationMillis, String audioUrl) {
        }
    }

    /**
     * Expensive opt-in system gate for the final-consumer lifecycle invariant. Every round opens a real Bilibili
     * DASH video decoder and a real Bilibili DASH audio/OpenAL output at the same time, proves decoded NV12 data,
     * a real GPU texture/PBO and audible PCM, then removes the last synthetic consumer and waits for every owned
     * resource class to return to its captured baseline before another round may start.
     */
    private static final class RealMediaLifecycleScenario implements BenchClientScenario {
        private static final BenchMetricDescriptor COMPLETED_ROUNDS = new BenchMetricDescriptor(
                "ncpb.real_media_lifecycle.completed_rounds", "count", MetricDirection.HIGHER_IS_BETTER);
        private static final BenchMetricDescriptor HTTP_ACTIVE = new BenchMetricDescriptor(
                "ncpb.real_media_lifecycle.http_active", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor VIDEO_FRAME_BYTES = new BenchMetricDescriptor(
                "ncpb.real_media_lifecycle.video_frame_bytes", "bytes", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor GPU_PBO_BYTES = new BenchMetricDescriptor(
                "ncpb.real_media_lifecycle.gpu_pbo_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor AUDIO_SAMPLES = new BenchMetricDescriptor(
                "ncpb.real_media_lifecycle.audio_samples", "count", MetricDirection.HIGHER_IS_BETTER);
        private static final BenchMetricDescriptor OWNED_BYTES = new BenchMetricDescriptor(
                "ncpb.real_media_lifecycle.owned_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor CONVERGENCE_MILLIS = new BenchMetricDescriptor(
                "ncpb.real_media_lifecycle.convergence_millis", "milliseconds", MetricDirection.LOWER_IS_BETTER);

        private final VideoFeatureProperties.RealMediaLifecycle properties =
                VideoFeatureProperties.realMediaLifecycle();
        private final AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
        private CompletableFuture<ResolvedMedia> resolution;
        private ResolvedMedia media;
        private UUID audioOwner;
        private long[] memoryBaseline;
        private VideoBillboardPreview.BenchUploadResources uploadBaseline;
        private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
        private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
        private int videoCloseBaseline;
        private int audioCloseBaseline;
        private int pendingNativeDeleteBaseline;
        private int completedRounds;
        private int cycleTicks;
        private long cycleStartedNanos;
        private long convergenceStartedNanos;
        private long maxConvergenceMillis;
        private long cycleHttpStarted;
        private long cycleStereoCreated;
        private long cycleStereoCleaned;
        private long cycleTapCreated;
        private long cycleTapClosed;
        private RealVideoStage video;
        private RealAudioStage audio;
        private PlaybackSessionId cycleSession;
        private CycleState cycleState = CycleState.READY;
        private boolean baselineCaptured;

        @Override
        public void setup(BenchClientContext context) {
            cleanupGlobalResources();
            audioOwner = context.player().getUUID();
            ClientAudioOutputRegistry.setOwnerVolume(audioOwner, 1.0F);
            resolution = CompletableFuture.supplyAsync(this::resolveMedia)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            resolutionFailure.compareAndSet(null, unwrapCompletion(error));
                        }
                    });
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            tickResourceClosures(context);
            throwResolutionFailure();
            if (!context.environment().readiness().ready() || context.frames().sampleCount() < 2
                    || resolution == null || !resolution.isDone()) {
                return BenchClientStepResult.CONTINUE;
            }
            if (media == null) {
                media = resolution.join();
            }
            if (!idleForBaseline()) {
                return BenchClientStepResult.CONTINUE;
            }
            captureBaseline();
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            tickResourceClosures(context);
            recordMetrics(context);
            if (!baselineCaptured || media == null) {
                throw new AssertionError("real media lifecycle baseline was not captured");
            }
            cycleTicks++;
            if (cycleTicks > properties.cycleTimeoutTicks()) {
                throw new AssertionError("Real media lifecycle cycle timed out: round=" + completedRounds
                        + " state=" + cycleState + " video=" + video + " audio=" + audio
                        + " diagnostics=" + describeResources());
            }

            switch (cycleState) {
                case READY -> startCycle();
                case LOADING -> pollLoadedCycle();
                case CLOSING -> pollCycleConvergence();
            }
            return completedRounds >= properties.rounds()
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (completedRounds != properties.rounds() || cycleState != CycleState.READY
                    || !resourcesAtBaseline()) {
                throw new AssertionError("Expected " + properties.rounds()
                        + " real loaded lifecycle rounds, got " + completedRounds + ": " + describeResources());
            }
            StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
            OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
            if (stereo.instancesCreated() != stereoBaseline.instancesCreated() + properties.rounds()
                    || stereo.cleanupsStarted() != stereoBaseline.cleanupsStarted() + properties.rounds()
                    || stereo.cleanupsCompleted() != stereoBaseline.cleanupsCompleted() + properties.rounds()
                    || tap.instancesCreated() != tapBaseline.instancesCreated()
                    || tap.closesCompleted() != tapBaseline.closesCompleted()) {
                throw new AssertionError("Each real Bilibili audio/OpenAL cycle must close exactly once: stereo="
                        + stereoBaseline + " -> " + stereo + ", tap=" + tapBaseline + " -> " + tap);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            stopCycleResources();
            cleanupGlobalResources();
        }

        private ResolvedMedia resolveMedia() {
            try {
                BiliApiClient.VideoId videoId = BiliApiClient.extractVideoId(properties.videoId());
                if (videoId == null) {
                    throw new IOException("invalid Bilibili video id: " + properties.videoId());
                }
                BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(videoId);
                BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(
                        videoId, info.cid(), properties.quality());
                // Lifecycle convergence is codec-independent. Prefer H.264 here so unsupported AV1 hardware on a
                // matrix host cannot turn this resource test into an unrelated codec-capability failure.
                BiliApiClient.VideoStream videoStream = !plan.h264Candidates().isEmpty()
                        ? plan.h264Candidates().getFirst() : plan.preferred();
                String audioUrl = BiliApiClient.getBestAudioUrl(videoId, info.cid(), false);
                if (videoStream.baseUrl().isBlank() || audioUrl == null || audioUrl.isBlank()) {
                    throw new IOException("Bilibili playurl returned an empty media URL");
                }
                return new ResolvedMedia(info.displayTitle(), Math.max(1L, info.duration() * 1_000L),
                        videoStream, audioUrl);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }

        private void captureBaseline() {
            memoryBaseline = currentOwnedBytesByCategory();
            uploadBaseline = VideoBillboardPreview.benchUploadResources();
            stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
            tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
            videoCloseBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
            audioCloseBaseline = AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
            pendingNativeDeleteBaseline = OpenALSpatialAudio.pendingNativeDeleteBatches();
            if (uploadBaseline.rgbaTexture() || uploadBaseline.yuvTextures()
                    || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0
                    || videoCloseBaseline != 0 || audioCloseBaseline != 0 || pendingNativeDeleteBaseline != 0) {
                throw new AssertionError("Real media lifecycle baseline is not idle: " + describeResources());
            }
            baselineCaptured = true;
        }

        private void startCycle() {
            cycleSession = PlaybackSessionId.of("bench-real-media-lifecycle-" + completedRounds);
            HttpRequestCloseDiagnostics.Snapshot http =
                    HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
            StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
            OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
            cycleHttpStarted = http.startedRequests();
            cycleStereoCreated = stereo.instancesCreated();
            cycleStereoCleaned = stereo.cleanupsCompleted();
            cycleTapCreated = tap.instancesCreated();
            cycleTapClosed = tap.closesCompleted();
            cycleStartedNanos = System.nanoTime();
            convergenceStartedNanos = 0L;
            video = RealVideoStage.start(media.videoStream(), memoryBaseline,
                    uploadBaseline.gpuPboBytes());
            audio = RealAudioStage.start(media.audioUrl(), audioOwner, cycleSession, media.durationMillis());
            cycleState = CycleState.LOADING;
            cycleTicks = 0;
        }

        private void pollLoadedCycle() {
            video.throwIfFailed();
            audio.throwIfFailed();
            StereoOpenALHandler.DiagnosticSnapshot output =
                    ClientAudioOutputRegistry.getSessionStereoSnapshot(cycleSession).orElse(null);
            boolean audioReady = output != null && output.started()
                    && output.firstAudiblePcm().samples() >= 1_024L
                    && output.inputSamples() > 0L;
            if (!video.loaded() || !audioReady) {
                return;
            }
            requirePcmQuality("real Bilibili lifecycle round " + completedRounds, output.firstAudiblePcm());
            if (!video.directFrame() || video.frameBytes() <= 0L || !video.yuvTextureObserved()
                    || !video.decoderNv12Observed() || !video.pboObserved()) {
                throw new AssertionError("Real Bilibili video cycle did not exercise direct NV12 texture/PBO: "
                        + video);
            }
            HttpRequestCloseDiagnostics.Snapshot http =
                    HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
            if (http.startedRequests() <= cycleHttpStarted) {
                throw new AssertionError("Real media cycle did not start an instrumented HTTP request");
            }
            convergenceStartedNanos = System.nanoTime();
            stopCycleResources();
            cycleState = CycleState.CLOSING;
            cycleTicks = 0;
        }

        private void pollCycleConvergence() {
            video.throwIfFailed();
            audio.throwIfFailed();
            if (!resourcesAtBaseline()) {
                return;
            }
            HttpRequestCloseDiagnostics.Snapshot http =
                    HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
            StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
            OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
            if (http.startedRequests() <= cycleHttpStarted
                    || stereo.instancesCreated() != cycleStereoCreated + 1L
                    || stereo.cleanupsCompleted() != cycleStereoCleaned + 1L
                    || tap.instancesCreated() != cycleTapCreated
                    || tap.closesCompleted() != cycleTapClosed
                    || !video.terminatedNormally() || !audio.streamClosed()) {
                throw new AssertionError("Real loaded cycle did not converge exactly once: round="
                        + completedRounds + " video=" + video + " audio=" + audio + " stereo=" + stereo
                        + " tap=" + tap + " http=" + http);
            }
            long convergenceMillis = TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0L, System.nanoTime() - convergenceStartedNanos));
            maxConvergenceMillis = Math.max(maxConvergenceMillis, convergenceMillis);
            completedRounds++;
            video = null;
            audio = null;
            cycleSession = null;
            cycleState = CycleState.READY;
            cycleTicks = 0;
        }

        private void stopCycleResources() {
            if (video != null) {
                video.stop();
            }
            if (audio != null) {
                audio.stop();
            }
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
            VideoBillboardPreview.releaseBenchUploadResources();
        }

        private void tickResourceClosures(BenchClientContext context) {
            ClientAudioOutputRegistry.updatePositions(new float[] {
                    (float) context.player().getX(), (float) context.player().getEyeY(),
                    (float) context.player().getZ()
            });
            OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
            VideoCloseDiagnostics.tickGlobal();
        }

        private void recordMetrics(BenchClientContext context) {
            HttpRequestCloseDiagnostics.Snapshot http =
                    HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
            StereoOpenALHandler.DiagnosticSnapshot output = cycleSession != null
                    ? ClientAudioOutputRegistry.getSessionStereoSnapshot(cycleSession).orElse(null) : null;
            context.metrics().record(COMPLETED_ROUNDS, completedRounds);
            context.metrics().record(HTTP_ACTIVE, http.activeRequests());
            context.metrics().record(VIDEO_FRAME_BYTES, video != null ? video.frameBytes() : 0L);
            context.metrics().record(GPU_PBO_BYTES,
                    MemoryResourceTracker.usage(MemoryResourceTracker.Category.GPU_PBO).currentBytes());
            context.metrics().record(AUDIO_SAMPLES, output != null ? output.firstAudiblePcm().samples() : 0L);
            context.metrics().record(OWNED_BYTES, currentOwnedBytes());
            context.metrics().record(CONVERGENCE_MILLIS, maxConvergenceMillis);
        }

        private boolean idleForBaseline() {
            return !ClientAudioOutputRegistry.isActive()
                    && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() == 0
                    && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                    && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                    && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                    && !VideoBillboardPreview.benchUploadResources().rgbaTexture()
                    && !VideoBillboardPreview.benchUploadResources().yuvTextures();
        }

        private boolean resourcesAtBaseline() {
            if ((video != null && !video.finished()) || (audio != null && !audio.finished())) {
                return false;
            }
            VideoBillboardPreview.BenchUploadResources upload = VideoBillboardPreview.benchUploadResources();
            if (ClientAudioOutputRegistry.isActive()
                    || StereoOpenALHandler.lifecycleSnapshot().activeInstances() != stereoBaseline.activeInstances()
                    || OpenALTappedAudioInputStream.lifecycleSnapshot().activeInstances() != tapBaseline.activeInstances()
                    || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0
                    || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations()
                            != videoCloseBaseline
                    || AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations()
                            != audioCloseBaseline
                    || OpenALSpatialAudio.pendingNativeDeleteBatches() != pendingNativeDeleteBaseline
                    || upload.rgbaTexture() != uploadBaseline.rgbaTexture()
                    || upload.yuvTextures() != uploadBaseline.yuvTextures()
                    || upload.textureStagingBytes() != uploadBaseline.textureStagingBytes()
                    || upload.gpuPboBytes() != uploadBaseline.gpuPboBytes()) {
                return false;
            }
            long[] current = currentOwnedBytesByCategory();
            return Arrays.equals(memoryBaseline, current);
        }

        private String describeResources() {
            return "state=" + cycleState + " round=" + completedRounds + '/' + properties.rounds()
                    + " upload=" + VideoBillboardPreview.benchUploadResources()
                    + " http=" + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime())
                    + " videoClose=" + VideoCloseDiagnostics.global().snapshot(System.nanoTime())
                    + " audioClose=" + AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime())
                    + " stereo=" + StereoOpenALHandler.lifecycleSnapshot()
                    + " tap=" + OpenALTappedAudioInputStream.lifecycleSnapshot()
                    + " pendingNative=" + OpenALSpatialAudio.pendingNativeDeleteBatches()
                    + " memory=" + Arrays.toString(currentOwnedBytesByCategory());
        }

        private void cleanupGlobalResources() {
            ModernTurntableVideoClient.clear();
            VideoBillboardPreview.stop();
            ClientAudioOutputRegistry.cleanup();
            HttpAudioStreamHandler.closeModernStreams();
            VideoBillboardPreview.releaseBenchUploadResources();
            OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
        }

        private void throwResolutionFailure() {
            Throwable error = resolutionFailure.get();
            if (error != null) {
                throw new AssertionError("Failed to resolve real Bilibili lifecycle media", error);
            }
        }

        private static Throwable unwrapCompletion(Throwable error) {
            Throwable current = error;
            while ((current instanceof CompletionException
                    || current instanceof java.util.concurrent.ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }

        private static long[] currentOwnedBytesByCategory() {
            MemoryResourceTracker.Category[] categories = MemoryResourceTracker.Category.values();
            long[] current = new long[categories.length];
            for (int i = 0; i < categories.length; i++) {
                current[i] = MemoryResourceTracker.usage(categories[i]).currentBytes();
            }
            return current;
        }

        private static long currentOwnedBytes() {
            return Arrays.stream(currentOwnedBytesByCategory()).sum();
        }

        private enum CycleState {
            READY,
            LOADING,
            CLOSING
        }

        private record ResolvedMedia(String title, long durationMillis,
                BiliApiClient.VideoStream videoStream, String audioUrl) {
        }

        private static final class RealVideoStage {
            private final BiliApiClient.VideoStream stream;
            private final long decoderNv12Baseline;
            private final long pboBaseline;
            private final AtomicReference<Fmp4NativeVideoDecoder> decoder = new AtomicReference<>();
            private final AtomicReference<Throwable> failure = new AtomicReference<>();
            private final AtomicBoolean stopRequested = new AtomicBoolean();
            private final AtomicBoolean loaded = new AtomicBoolean();
            private final AtomicBoolean finished = new AtomicBoolean();
            private final AtomicBoolean terminatedNormally = new AtomicBoolean();
            private final Thread worker;
            private volatile long frameBytes;
            private volatile long uploadNanos;
            private volatile boolean directFrame;
            private volatile boolean yuvTextureObserved;
            private volatile boolean decoderNv12Observed;
            private volatile boolean pboObserved;

            private RealVideoStage(BiliApiClient.VideoStream stream, long[] memoryBaseline, long pboBaseline) {
                this.stream = stream;
                this.decoderNv12Baseline = memoryBaseline[MemoryResourceTracker.Category.DECODER_NV12.ordinal()];
                this.pboBaseline = pboBaseline;
                worker = NetMusicThreadFactory.daemonThread("RealMediaLifecycle-video", this::run);
                worker.start();
            }

            static RealVideoStage start(BiliApiClient.VideoStream stream, long[] memoryBaseline, long pboBaseline) {
                return new RealVideoStage(stream, memoryBaseline, pboBaseline);
            }

            private void run() {
                Fmp4NativeVideoDecoder opened = null;
                try {
                    opened = openDecoder();
                    decoder.set(opened);
                    try (Fmp4NativeVideoDecoder.DecodedFrame frame = opened.getNextDecodedFrame()) {
                        if (frame == null) {
                            throw new IOException("real Bilibili video decoder reached EOF before its first frame");
                        }
                        frameBytes = frame.byteLength();
                        directFrame = frame.buffer() != null;
                        decoderNv12Observed = MemoryResourceTracker
                                .usage(MemoryResourceTracker.Category.DECODER_NV12).currentBytes()
                                > decoderNv12Baseline;
                        uploadNanos = VideoBillboardPreview.uploadDecodedFrameSyncForBench(
                                frame, Math.max(1, stream.width()), Math.max(1, stream.height()));
                        if (uploadNanos < 0L) {
                            throw new IOException("real Bilibili decoded frame GPU upload failed");
                        }
                        VideoBillboardPreview.BenchUploadResources resources =
                                VideoBillboardPreview.benchUploadResources();
                        yuvTextureObserved = resources.yuvTextures();
                        pboObserved = resources.gpuPboBytes() > pboBaseline;
                        loaded.set(true);
                    }
                    while (!stopRequested.get()) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(10L);
                        } catch (InterruptedException interrupted) {
                            if (!stopRequested.get()) {
                                throw interrupted;
                            }
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Throwable error) {
                    if (!stopRequested.get()) {
                        failure.compareAndSet(null, error);
                    }
                } finally {
                    // stop() interrupts the stage worker only to leave its hold loop. Do not let that expected
                    // cancellation interrupt short-circuit decoder.close()/terminationFuture(), otherwise the
                    // benchmark would report a lifecycle failure before the physical native barrier is observed.
                    if (stopRequested.get()) {
                        Thread.interrupted();
                    }
                    if (opened != null) {
                        try {
                            opened.requestClose();
                            opened.close();
                            awaitExpectedTermination(opened);
                            terminatedNormally.set(true);
                        } catch (Throwable error) {
                            failure.compareAndSet(null, unwrapCompletion(error));
                        }
                    }
                    decoder.set(null);
                    finished.set(true);
                }
            }

            private void awaitExpectedTermination(Fmp4NativeVideoDecoder opened) throws Exception {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20L);
                while (true) {
                    if (stopRequested.get()) {
                        Thread.interrupted();
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        throw new java.util.concurrent.TimeoutException(
                                "real Bilibili video decoder termination timed out");
                    }
                    try {
                        opened.terminationFuture().get(remaining, TimeUnit.NANOSECONDS);
                        return;
                    } catch (InterruptedException interrupted) {
                        if (!stopRequested.get()) {
                            throw interrupted;
                        }
                        // stop() may race with the transition from the hold loop into this barrier.
                        // Expected cancellation must not turn physical termination into a false failure.
                    }
                }
            }

            private Fmp4NativeVideoDecoder openDecoder() throws IOException {
                IOException last = null;
                for (String hwaccel : VideoFeatureFlags.requestedHwaccelCandidates()) {
                    try {
                        return new Fmp4NativeVideoDecoder(stream.baseUrl(), stream.codecId(),
                                Math.max(1, stream.width()), Math.max(1, stream.height()), 10_000, true,
                                Fmp4NativeVideoDecoder.OutputFormat.NV12, hwaccel, 0L, 0L, 30);
                    } catch (IOException error) {
                        last = error;
                    }
                }
                throw last != null ? last : new IOException("no native video hwaccel candidate was available");
            }

            void stop() {
                stopRequested.set(true);
                Fmp4NativeVideoDecoder opened = decoder.get();
                if (opened != null) {
                    try {
                        opened.requestClose();
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }
                worker.interrupt();
            }

            void throwIfFailed() {
                Throwable error = failure.get();
                if (error != null) {
                    throw new AssertionError("Real Bilibili video lifecycle stage failed: " + error, error);
                }
            }

            boolean loaded() {
                return loaded.get();
            }

            boolean finished() {
                return finished.get();
            }

            boolean terminatedNormally() {
                return terminatedNormally.get();
            }

            boolean directFrame() {
                return directFrame;
            }

            long frameBytes() {
                return frameBytes;
            }

            boolean yuvTextureObserved() {
                return yuvTextureObserved;
            }

            boolean decoderNv12Observed() {
                return decoderNv12Observed;
            }

            boolean pboObserved() {
                return pboObserved;
            }

            @Override
            public String toString() {
                return "RealVideoStage[loaded=" + loaded + ", finished=" + finished + ", terminated="
                        + terminatedNormally + ", frameBytes=" + frameBytes + ", uploadNanos=" + uploadNanos
                        + ", direct=" + directFrame + ", yuv=" + yuvTextureObserved + ", pbo=" + pboObserved
                        + ", decoderNv12=" + decoderNv12Observed + ", failure=" + failure + ']';
            }
        }

        private static final class RealAudioStage {
            private static final int READ_BUFFER_BYTES = 32 * 1024;
            private final AtomicReference<AudioInputStream> stream = new AtomicReference<>();
            private final AtomicReference<Throwable> failure = new AtomicReference<>();
            private final AtomicReference<HttpAudioStreamHandler.RegisteredRequest> registered =
                    new AtomicReference<>();
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private final AtomicBoolean stopRequested = new AtomicBoolean();
            private final AtomicBoolean streamCloseCompleted = new AtomicBoolean();
            private final AtomicBoolean finished = new AtomicBoolean();
            private final Thread reader;

            private RealAudioStage(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId,
                    long totalMillis) {
                reader = NetMusicThreadFactory.daemonThread("RealMediaLifecycle-audio-" + sessionId.value(),
                        () -> run(mediaUrl, ownerId, sessionId, totalMillis));
                reader.start();
            }

            static RealAudioStage start(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId,
                    long totalMillis) {
                return new RealAudioStage(mediaUrl, ownerId, sessionId, totalMillis);
            }

            private void run(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId, long totalMillis) {
                try {
                    PlaybackRequest request = PlaybackRequest.now(mediaUrl, null, sessionId.value(), 0L,
                            Math.max(1L, totalMillis), ownerId, null);
                    HttpAudioStreamHandler.RegisteredRequest requestUrl =
                            HttpAudioStreamHandler.registerRequest(request);
                    registered.set(requestUrl);
                    if (cancelled.get()) {
                        requestUrl.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
                        return;
                    }
                    AudioInputStream opened = new HttpAudioStreamHandler().handle(
                            URI.create(requestUrl.url()).toURL());
                    stream.set(opened);
                    byte[] buffer = new byte[READ_BUFFER_BYTES];
                    while (!cancelled.get() && opened.read(buffer, 0, buffer.length) >= 0) {
                        // Decoder/OpenAL backpressure intentionally remains on this daemon worker.
                    }
                } catch (Throwable error) {
                    if (!cancelled.get()) {
                        failure.compareAndSet(null, error);
                    }
                } finally {
                    closeStream();
                    finished.set(true);
                }
            }

            void stop() {
                cancelled.set(true);
                if (!stopRequested.compareAndSet(false, true)) {
                    return;
                }
                reader.interrupt();
                NetMusicThreadFactory.daemonThread("RealMediaLifecycle-audio-close", () -> {
                    HttpAudioStreamHandler.RegisteredRequest request = registered.get();
                    if (request != null) {
                        request.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
                    }
                    closeStream();
                }).start();
            }

            void throwIfFailed() {
                Throwable error = failure.get();
                if (error != null) {
                    throw new AssertionError("Real Bilibili audio lifecycle stage failed: " + error, error);
                }
            }

            boolean finished() {
                return finished.get();
            }

            boolean streamClosed() {
                return streamCloseCompleted.get();
            }

            private void closeStream() {
                AudioInputStream value = stream.get();
                if (value == null) {
                    return;
                }
                try {
                    value.close();
                    streamCloseCompleted.set(true);
                } catch (IOException error) {
                    if (!cancelled.get()) {
                        failure.compareAndSet(null, error);
                    }
                }
            }

            @Override
            public String toString() {
                return "RealAudioStage[finished=" + finished + ", streamClosed=" + streamClosed()
                        + ", failure=" + failure + ']';
            }
        }
    }

    private static final class DeterministicVideoUploadScenario implements BenchClientScenario {
        private static final int WIDTH = 640;
        private static final int HEIGHT = 360;
        private static final int FRAMES_PER_FORMAT = 30;
        private static final VideoBillboardPreview.BenchUploadFormat[] FORMATS =
                VideoBillboardPreview.BenchUploadFormat.values();
        private static final BenchMetricDescriptor UPLOAD_LATENCY = new BenchMetricDescriptor(
                "ncpb.video.upload_latency", "ms", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor UPLOAD_BYTES = new BenchMetricDescriptor(
                "ncpb.video.upload_bytes", "bytes", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor UPLOAD_P95 = new BenchMetricDescriptor(
                "ncpb.video.upload_p95", "ms", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor UPLOAD_P99 = new BenchMetricDescriptor(
                "ncpb.video.upload_p99", "ms", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor STAGING_BYTES = new BenchMetricDescriptor(
                "ncpb.video.texture_staging_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor PBO_BYTES = new BenchMetricDescriptor(
                "ncpb.video.gpu_pbo_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

        private final long[][] uploadNanos = new long[FORMATS.length][FRAMES_PER_FORMAT];
        private long baselineStaging;
        private long baselinePbo;
        private long peakStagingDelta;
        private long peakPboDelta;
        private int formatIndex;
        private int frameIndex;
        private boolean released;

        @Override
        public void setup(BenchClientContext context) {
            ModernTurntableVideoClient.clear();
            ConsoleConsumerLifecycleScenario.requireClean("deterministic upload setup");
            VideoBillboardPreview.releaseBenchUploadResources();
            var resources = VideoBillboardPreview.benchUploadResources();
            baselineStaging = resources.textureStagingBytes();
            baselinePbo = resources.gpuPboBytes();
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            upload(VideoBillboardPreview.BenchUploadFormat.RGBA, -1);
            VideoBillboardPreview.releaseBenchUploadResources();
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            VideoBillboardPreview.BenchUploadFormat format = FORMATS[formatIndex];
            byte[] frame = DeterministicVideoUploadWorkload.frame(format, WIDTH, HEIGHT, frameIndex);
            long elapsedNanos = VideoBillboardPreview.uploadFrameOnClientThreadForBench(
                    format, frame, WIDTH, HEIGHT);
            if (elapsedNanos < 0L) {
                throw new AssertionError("GPU upload failed: format=" + format + ", frame=" + frameIndex);
            }
            uploadNanos[formatIndex][frameIndex] = elapsedNanos;
            context.metrics().record(UPLOAD_LATENCY, elapsedNanos / 1_000_000.0D);
            context.metrics().record(UPLOAD_BYTES, frame.length);
            var resources = VideoBillboardPreview.benchUploadResources();
            long stagingDelta = Math.max(0L, resources.textureStagingBytes() - baselineStaging);
            long pboDelta = Math.max(0L, resources.gpuPboBytes() - baselinePbo);
            peakStagingDelta = Math.max(peakStagingDelta, stagingDelta);
            peakPboDelta = Math.max(peakPboDelta, pboDelta);
            context.metrics().record(STAGING_BYTES, stagingDelta);
            context.metrics().record(PBO_BYTES, pboDelta);

            frameIndex++;
            if (frameIndex < FRAMES_PER_FORMAT) {
                return BenchClientStepResult.CONTINUE;
            }
            recordPercentiles(context, uploadNanos[formatIndex]);
            frameIndex = 0;
            formatIndex++;
            if (formatIndex < FORMATS.length) {
                return BenchClientStepResult.CONTINUE;
            }
            VideoBillboardPreview.releaseBenchUploadResources();
            released = true;
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (!released || formatIndex != FORMATS.length) {
                throw new AssertionError("Deterministic upload workload did not finish all formats");
            }
            if (peakStagingDelta <= 0L) {
                throw new AssertionError("YUV upload did not allocate tracked texture staging memory");
            }
            if (peakPboDelta <= 0L) {
                throw new AssertionError("NV12 upload did not allocate tracked PBO memory");
            }
            var resources = VideoBillboardPreview.benchUploadResources();
            if (resources.rgbaTexture() || resources.yuvTextures()
                    || resources.textureStagingBytes() != baselineStaging
                    || resources.gpuPboBytes() != baselinePbo) {
                throw new AssertionError("GPU upload resources did not return to baseline: " + resources);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            VideoBillboardPreview.releaseBenchUploadResources();
        }

        private static long upload(VideoBillboardPreview.BenchUploadFormat format, int frameIndex) {
            byte[] frame = DeterministicVideoUploadWorkload.frame(format, WIDTH, HEIGHT, frameIndex);
            return VideoBillboardPreview.uploadFrameOnClientThreadForBench(format, frame, WIDTH, HEIGHT);
        }

        private static void recordPercentiles(BenchClientContext context, long[] values) {
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            context.metrics().record(UPLOAD_P95, percentile(sorted, 0.95D) / 1_000_000.0D);
            context.metrics().record(UPLOAD_P99, percentile(sorted, 0.99D) / 1_000_000.0D);
        }

        private static long percentile(long[] sorted, double quantile) {
            int index = Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * quantile) - 1);
            return sorted[Math.max(0, index)];
        }
    }

    private static final class DeviceLinkConfigMatrixScenario implements BenchClientScenario {
        private static final BenchMetricDescriptor DEVICES = new BenchMetricDescriptor(
                "ncpb.device_matrix.devices", "count", MetricDirection.NEUTRAL);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean setupComplete = new AtomicBoolean();
        private final List<BlockPos> fixturePositions = new ArrayList<>();
        private BlockPos turntablePos;
        private BlockPos replacementTurntablePos;
        private BlockPos videoPos;
        private BlockPos lyricPos;
        private BlockPos speakerPos;
        private BlockPos livePos;
        private BlockPos consolePos;
        private UUID playerId;
        private final AtomicInteger linkPhase = new AtomicInteger();

        @Override
        public void setup(BenchClientContext context) {
            playerId = context.player().getUUID();
            BlockPos origin = context.player().blockPosition().offset(2, 0, 2);
            turntablePos = fixture(origin, 0);
            videoPos = fixture(origin, 1);
            lyricPos = fixture(origin, 2);
            speakerPos = fixture(origin, 3);
            livePos = fixture(origin, 4);
            consolePos = fixture(origin, 5);
            replacementTurntablePos = fixture(origin, 6);
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                throw new AssertionError("Integrated server is unavailable");
            }
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || !(player.level() instanceof ServerLevel level)) {
                        throw new IllegalStateException("Integrated server player is unavailable");
                    }
                    level.setBlockAndUpdate(turntablePos, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
                    level.setBlockAndUpdate(videoPos, ModBlocks.VIDEO_PROJECTOR.get().defaultBlockState());
                    level.setBlockAndUpdate(lyricPos, ModBlocks.LYRIC_PROJECTOR.get().defaultBlockState());
                    level.setBlockAndUpdate(speakerPos, ModBlocks.SPEAKER.get().defaultBlockState());
                    level.setBlockAndUpdate(livePos, ModBlocks.LIVE_STREAMER.get().defaultBlockState());
                    level.setBlockAndUpdate(consolePos, ModBlocks.CONTROL_CONSOLE.get().defaultBlockState());
                    level.setBlockAndUpdate(replacementTurntablePos,
                            ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());

                    VideoProjectorBlockEntity video = require(level, videoPos, VideoProjectorBlockEntity.class);
                    video.setProjectionYaw(123.0F);
                    video.setProjectionPitch(-17.0F);
                    video.setProjectionScale(1.5F);
                    video.setProjectionHeight(2.25F);
                    video.setProjectionDistanceX(0.75F);
                    video.setProjectionDistanceZ(-0.5F);
                    video.setPreferredQuality(80);
                    video.linkTo(turntablePos);

                    LyricProjectorBlockEntity lyric = require(level, lyricPos, LyricProjectorBlockEntity.class);
                    lyric.setProjectionYaw(231.0F);
                    lyric.setProjectionPitch(14.0F);
                    lyric.setProjectionScale(0.75F);
                    lyric.setProjectionMode(2);
                    lyric.setAllowAi(true);
                    lyric.linkTo(turntablePos);

                    SpeakerBlockEntity speaker = require(level, speakerPos, SpeakerBlockEntity.class);
                    speaker.setChannelIndex(SpeakerBlockEntity.CH_LTF);
                    speaker.setVolume(1.25F);
                    speaker.setAutoMixJoc(true);
                    speaker.linkTo(turntablePos);

                    ControlConsoleBlockEntity console = require(level, consolePos, ControlConsoleBlockEntity.class);
                    console.linkTo(level.dimension().identifier().toString(), turntablePos);
                    if (!AudioLinkIndex.hasSpeakerLinkedTo(level, turntablePos)) {
                        throw new AssertionError("Speaker reverse link index was not registered");
                    }
                    if (!AudioLinkIndex.hasVideoProjectorLinkedTo(level, turntablePos)) {
                        throw new AssertionError("Video-projector reverse link index was not registered");
                    }
                    setupComplete.set(true);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
        }

        private BlockPos fixture(BlockPos origin, int offset) {
            BlockPos pos = origin.offset(offset, 0, 0).immutable();
            fixturePositions.add(pos);
            return pos;
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            throwIfFailed();
            if (!setupComplete.get()) {
                return BenchClientStepResult.CONTINUE;
            }
            return clientDevicesReady(context) && context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            throwIfFailed();
            int currentPhase = linkPhase.get();
            if (currentPhase < 0) {
                return BenchClientStepResult.CONTINUE;
            }
            if (!clientDevicesReady(context)) {
                return BenchClientStepResult.CONTINUE;
            }
            BlockPos expectedTurntable = currentPhase >= 1 ? replacementTurntablePos : turntablePos;
            VideoProjectorBlockEntity video = require(context.level(), videoPos, VideoProjectorBlockEntity.class);
            LyricProjectorBlockEntity lyric = require(context.level(), lyricPos, LyricProjectorBlockEntity.class);
            SpeakerBlockEntity speaker = require(context.level(), speakerPos, SpeakerBlockEntity.class);
            ControlConsoleBlockEntity console = require(context.level(), consolePos, ControlConsoleBlockEntity.class);
            requireClose(video.getProjectionYaw(), 123.0F, "video yaw");
            requireClose(video.getProjectionPitch(), -17.0F, "video pitch");
            requireClose(video.getProjectionScale(), 1.5F, "video scale");
            if (!expectedTurntable.equals(video.getLinkedTurntablePos()) || video.getPreferredQuality() != 80) {
                throw new AssertionError("Video projector link/quality did not synchronize");
            }
            if (!expectedTurntable.equals(lyric.getLinkedTurntablePos()) || lyric.getProjectionMode() != 2
                    || !lyric.getAllowAi()) {
                throw new AssertionError("Lyric projector link/subtitle settings did not synchronize");
            }
            if (!expectedTurntable.equals(speaker.getLinkedTurntablePos())
                    || speaker.getChannelIndex() != SpeakerBlockEntity.CH_LTF || !speaker.isAutoMixJoc()) {
                throw new AssertionError("Speaker link/channel settings did not synchronize");
            }
            requireClose(speaker.getVolume(), 1.25F, "speaker volume");
            if (!console.document().hasSourceBinding()
                    || !expectedTurntable.equals(new BlockPos(console.document().sourceX(), console.document().sourceY(),
                            console.document().sourceZ()))) {
                throw new AssertionError("Control-console source binding did not synchronize");
            }
            if (!ClientLinkRegistry.getSources(expectedTurntable).contains(videoPos)) {
                throw new AssertionError("Late client projector link was not registered for playback wakeup");
            }
            if (ClientAudioOutputRegistry.getAudioTimeline(expectedTurntable).relayRegisteredCount() < 1) {
                throw new AssertionError("Speaker audio relay was not registered for its turntable");
            }
            if (!ControlConsoleRenderer.consumerLeaseDiagnostic(consolePos).registered()) {
                throw new AssertionError("Control-console consumer was not registered from the block entity");
            }
            if (currentPhase == 0 && linkPhase.compareAndSet(0, -1)) {
                var server = context.minecraft().getSingleplayerServer();
                if (server == null) {
                    throw new AssertionError("Integrated server disappeared before device rebind");
                }
                server.execute(() -> {
                    try {
                        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                        if (player == null || !(player.level() instanceof ServerLevel level)) {
                            throw new IllegalStateException("Integrated server player is unavailable");
                        }
                        require(level, videoPos, VideoProjectorBlockEntity.class).linkTo(replacementTurntablePos);
                        require(level, lyricPos, LyricProjectorBlockEntity.class).linkTo(replacementTurntablePos);
                        require(level, speakerPos, SpeakerBlockEntity.class).linkTo(replacementTurntablePos);
                        require(level, consolePos, ControlConsoleBlockEntity.class).linkTo(
                                level.dimension().identifier().toString(), replacementTurntablePos);
                        if (AudioLinkIndex.hasSpeakerLinkedTo(level, turntablePos)
                                || AudioLinkIndex.hasVideoProjectorLinkedTo(level, turntablePos)
                                || !AudioLinkIndex.hasSpeakerLinkedTo(level, replacementTurntablePos)
                                || !AudioLinkIndex.hasVideoProjectorLinkedTo(level, replacementTurntablePos)) {
                            throw new AssertionError("Server reverse indexes did not move atomically during rebind");
                        }
                        linkPhase.set(1);
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                });
                return BenchClientStepResult.CONTINUE;
            }
            if (currentPhase == 1) {
                if (ClientLinkRegistry.getSources(turntablePos).contains(videoPos)
                        || ClientAudioOutputRegistry.getAudioTimeline(turntablePos).relayRegisteredCount() != 0) {
                    return BenchClientStepResult.CONTINUE;
                }
                context.metrics().record(DEVICES, fixturePositions.size());
                linkPhase.set(2);
                return BenchClientStepResult.COMPLETE;
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfFailed();
            if (linkPhase.get() != 2 || !clientDevicesReady(context)) {
                throw new AssertionError("Device matrix did not remain synchronized through verification");
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            ClientLinkRegistry.clear();
            var server = context.minecraft().getSingleplayerServer();
            if (server != null && !fixturePositions.isEmpty()) {
                List<BlockPos> positions = List.copyOf(fixturePositions);
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null && player.level() instanceof ServerLevel level) {
                        positions.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
                    }
                });
            }
        }

        private boolean clientDevicesReady(BenchClientContext context) {
            BlockPos expectedTurntable = linkPhase.get() >= 1 ? replacementTurntablePos : turntablePos;
            return context.level().getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity
                    && context.level().getBlockEntity(replacementTurntablePos) instanceof ModernTurntableBlockEntity
                    && context.level().getBlockEntity(videoPos) instanceof VideoProjectorBlockEntity video
                    && expectedTurntable.equals(video.getLinkedTurntablePos())
                    && context.level().getBlockEntity(lyricPos) instanceof LyricProjectorBlockEntity lyric
                    && expectedTurntable.equals(lyric.getLinkedTurntablePos())
                    && context.level().getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker
                    && expectedTurntable.equals(speaker.getLinkedTurntablePos())
                    && context.level().getBlockEntity(livePos) instanceof LiveStreamerBlockEntity
                    && context.level().getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console
                    && console.document().hasSourceBinding()
                    && ClientAudioOutputRegistry.getAudioTimeline(expectedTurntable).relayRegisteredCount() >= 1
                    && ControlConsoleRenderer.consumerLeaseDiagnostic(consolePos).registered();
        }

        private void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Device link/config matrix failed", error);
            }
        }

        private static void requireClose(float actual, float expected, String label) {
            if (Math.abs(actual - expected) > 0.0001F) {
                throw new AssertionError(label + " mismatch: expected=" + expected + " actual=" + actual);
            }
        }

        private static <T> T require(Level level, BlockPos pos, Class<T> type) {
            Object value = level.getBlockEntity(pos);
            if (!type.isInstance(value)) {
                throw new AssertionError(type.getSimpleName() + " is missing at " + pos + ": " + value);
            }
            return type.cast(value);
        }
    }

    private static final class WearableBindingTopologyScenario implements BenchClientScenario {
        private static final int HEADPHONE_SLOT = 35;
        private static final int GLASSES_SLOT = 34;
        private static final BenchMetricDescriptor BINDINGS = new BenchMetricDescriptor(
                "ncpb.wearable_topology.bindings", "count", MetricDirection.NEUTRAL);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger phase = new AtomicInteger();
        private UUID playerId;
        private UUID mp4Id;
        private UUID padId;
        private BlockPos turntablePos;
        private BlockPos projectorPos;
        private ItemStack originalHead = ItemStack.EMPTY;
        private ItemStack originalHeadphoneSlot = ItemStack.EMPTY;
        private ItemStack originalGlassesSlot = ItemStack.EMPTY;

        @Override
        public void setup(BenchClientContext context) {
            playerId = context.player().getUUID();
            mp4Id = UUID.randomUUID();
            padId = UUID.randomUUID();
            turntablePos = context.player().blockPosition().offset(3, 0, 3).immutable();
            projectorPos = turntablePos.offset(1, 0, 0).immutable();
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                throw new AssertionError("Integrated server is unavailable");
            }
            server.execute(() -> setupBindings(server));
        }

        private void setupBindings(net.minecraft.server.MinecraftServer server) {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    throw new IllegalStateException("Integrated server player is unavailable");
                }
                originalHead = player.getItemBySlot(EquipmentSlot.HEAD).copy();
                originalHeadphoneSlot = player.getInventory().getItem(HEADPHONE_SLOT).copy();
                originalGlassesSlot = player.getInventory().getItem(GLASSES_SLOT).copy();
                ItemStack headphones = new ItemStack(ModItems.INVISIBLE_HEADPHONES.get());
                ItemStack glasses = new ItemStack(ModItems.HOLOGRAPHIC_GLASSES.get());
                player.getInventory().setItem(HEADPHONE_SLOT, headphones);
                player.getInventory().setItem(GLASSES_SLOT, glasses);

                MediaSource turntable = MediaBindingCleanupService.turntableSource(level, turntablePos);
                MediaSource mp4 = MediaBindingCleanupService.mp4Source(mp4Id);
                MediaSource pad = MediaBindingCleanupService.padSource(padId);
                MediaSource projector = MediaSource.projector(level.dimension(), projectorPos);
                requireBound(MediaEquipmentBindingService.bind(player, headphones, turntable), "headphone/turntable");
                requireBound(MediaEquipmentBindingService.bind(player, headphones, mp4), "headphone/MP4");
                requireBound(MediaEquipmentBindingService.bind(player, glasses, turntable), "glasses/turntable");
                requireBound(MediaEquipmentBindingService.bind(player, glasses, mp4), "glasses/MP4");
                requireBound(MediaEquipmentBindingService.bind(player, glasses, pad), "glasses/Pad");
                if (!HolographicGlassesItem.addOrUpdateBoundMedia(glasses, projector)) {
                    throw new AssertionError("Fourth holographic projector binding was rejected");
                }
                if (!HolographicGlassesItem.addOrUpdateBoundMedia(glasses, mp4)
                        || HolographicGlassesItem.readScreenBindings(glasses).size()
                                != HolographicGlassesItem.MAX_BOUND_MEDIA) {
                    throw new AssertionError("Duplicate holographic binding changed the four-slot topology");
                }
                if (HolographicGlassesItem.addOrUpdateBoundMedia(glasses, MediaSource.mp4(UUID.randomUUID()))) {
                    throw new AssertionError("Holographic glasses accepted a fifth media binding");
                }
                var stats = MediaBindingCleanupService.countTargetBindings(player, mp4);
                if (stats.headphoneCount() != 1 || stats.holographicCount() != 1
                        || !AudioLinkIndex.hasHeadphoneLinkedToMp4(mp4Id)
                        || !turntablePos.equals(AudioLinkData.readHeadphoneTurntable(headphones))) {
                    throw new AssertionError("Wearable binding/index topology is incomplete: " + stats);
                }
                player.setItemSlot(EquipmentSlot.HEAD, headphones);
                player.getInventory().setItem(HEADPHONE_SLOT, ItemStack.EMPTY);
                syncInventory(player);
                phase.set(1);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            throwIfFailed();
            if (phase.get() < 1 || !HeadphoneClientState.equipped()
                    || !HeadphoneClientState.handlesTurntable(turntablePos)
                    || !HeadphoneClientState.handlesMediaDevice(mp4Id)) {
                return BenchClientStepResult.CONTINUE;
            }
            return context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            throwIfFailed();
            int current = phase.get();
            if (current == 1) {
                runOnServer(context, player -> {
                    ItemStack glasses = player.getInventory().getItem(GLASSES_SLOT);
                    player.getInventory().setItem(HEADPHONE_SLOT, player.getItemBySlot(EquipmentSlot.HEAD));
                    player.setItemSlot(EquipmentSlot.HEAD, glasses);
                    player.getInventory().setItem(GLASSES_SLOT, ItemStack.EMPTY);
                    syncInventory(player);
                    phase.set(2);
                });
                return BenchClientStepResult.CONTINUE;
            }
            if (current == 2) {
                if (!HolographicGlassesClient.active() || !HolographicGlassesClient.handlesTurntable(turntablePos)
                        || HolographicGlassesClient.screenBindings().size()
                                != HolographicGlassesItem.MAX_BOUND_MEDIA) {
                    return BenchClientStepResult.CONTINUE;
                }
                runOnServer(context, player -> {
                    MediaSource mp4 = MediaBindingCleanupService.mp4Source(mp4Id);
                    var cleared = MediaBindingCleanupService.clearTargetBindings(player, mp4);
                    if (cleared.headphoneCount() != 1 || cleared.holographicCount() != 1
                            || AudioLinkIndex.hasHeadphoneLinkedToMp4(mp4Id)) {
                        throw new AssertionError("Target unlink did not clear both wearable owners: " + cleared);
                    }
                    phase.set(3);
                });
                return BenchClientStepResult.CONTINUE;
            }
            if (current == 3) {
                if (HolographicGlassesClient.screenBindings().size() != 3
                        || HolographicGlassesClient.screenBindings().stream()
                                .anyMatch(binding -> mp4Id.equals(binding.deviceId()))) {
                    return BenchClientStepResult.CONTINUE;
                }
                runOnServer(context, player -> {
                    var glassesClear = MediaBindingCleanupService.clearEquipmentBindings(
                            player, player.getItemBySlot(EquipmentSlot.HEAD));
                    var headphoneClear = MediaBindingCleanupService.clearEquipmentBindings(
                            player, player.getInventory().getItem(HEADPHONE_SLOT));
                    if (glassesClear.holographicCount() != 3 || headphoneClear.headphoneCount() != 1) {
                        throw new AssertionError("Full wearable cleanup mismatch: glasses=" + glassesClear
                                + " headphones=" + headphoneClear);
                    }
                    player.setItemSlot(EquipmentSlot.HEAD, originalHead.copy());
                    player.getInventory().setItem(HEADPHONE_SLOT, originalHeadphoneSlot.copy());
                    player.getInventory().setItem(GLASSES_SLOT, originalGlassesSlot.copy());
                    syncInventory(player);
                    phase.set(4);
                });
                return BenchClientStepResult.CONTINUE;
            }
            if (current == 4 && testBindingsAbsentOnClient()) {
                context.metrics().record(BINDINGS, HolographicGlassesItem.MAX_BOUND_MEDIA + 2);
                phase.set(5);
                return BenchClientStepResult.COMPLETE;
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfFailed();
            if (phase.get() != 5 || !testBindingsAbsentOnClient()
                    || AudioLinkIndex.hasHeadphoneLinkedToMp4(mp4Id)) {
                throw new AssertionError("Wearable topology did not converge after cleanup");
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            runOnServer(context, player -> {
                MediaBindingCleanupService.clearEquipmentBindings(player, player.getItemBySlot(EquipmentSlot.HEAD));
                MediaBindingCleanupService.clearEquipmentBindings(
                        player, player.getInventory().getItem(HEADPHONE_SLOT));
                player.setItemSlot(EquipmentSlot.HEAD, originalHead.copy());
                player.getInventory().setItem(HEADPHONE_SLOT, originalHeadphoneSlot.copy());
                player.getInventory().setItem(GLASSES_SLOT, originalGlassesSlot.copy());
                AudioLinkIndex.updatePlayerHeadphones(player);
                syncInventory(player);
            });
        }

        private void runOnServer(BenchClientContext context, Consumer<ServerPlayer> action) {
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                failure.compareAndSet(null, new AssertionError("Integrated server disappeared"));
                return;
            }
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) {
                        throw new IllegalStateException("Integrated server player is unavailable");
                    }
                    action.accept(player);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
        }

        private void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Wearable binding topology failed: "
                        + error.getClass().getSimpleName() + ": " + error.getMessage(), error);
            }
        }

        private boolean testBindingsAbsentOnClient() {
            if (HeadphoneClientState.handlesTurntable(turntablePos)
                    || HeadphoneClientState.handlesMediaDevice(mp4Id)
                    || HeadphoneClientState.handlesMediaDevice(padId)) {
                return false;
            }
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level == null) {
                return false;
            }
            MediaSource turntable = MediaSource.turntable(level.dimension(), turntablePos);
            MediaSource projector = MediaSource.projector(level.dimension(), projectorPos);
            MediaSource mp4 = MediaSource.mp4(mp4Id);
            MediaSource pad = MediaSource.pad(padId);
            return HolographicGlassesClient.screenBindings().stream().noneMatch(binding ->
                    mp4.equals(binding.source()) || pad.equals(binding.source())
                            || turntable.equals(binding.source()) || projector.equals(binding.source()));
        }

        private static void requireBound(MediaEquipmentBindingService.BindResult result, String label) {
            if (!result.bound() || !result.handledAbility()) {
                throw new AssertionError(label + " was not handled by the formal binding service: " + result);
            }
        }

        private static void syncInventory(ServerPlayer player) {
            player.getInventory().setChanged();
            if (player.containerMenu != null) {
                player.containerMenu.broadcastChanges();
            }
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static final class GuiScreenMatrixScenario implements BenchClientScenario {
        private static final BenchMetricDescriptor SCREENS = new BenchMetricDescriptor(
                "ncpb.gui_matrix.screens", "count", MetricDirection.NEUTRAL);
        private final List<ScreenCase> screens = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean setupComplete = new AtomicBoolean();
        private BenchGuiSession gui;
        private BlockPos origin;
        private UUID playerId;
        private int index;
        private long openedAtFrame;

        @Override
        public void setup(BenchClientContext context) {
            playerId = context.player().getUUID();
            origin = context.player().blockPosition().offset(2, 0, 2).immutable();
            screens.add(new ScreenCase("turntable", ModernTurntableScreen.class,
                    () -> new ModernTurntableScreen(origin)));
            screens.add(new ScreenCase("video-projector", VideoProjectorScreen.class,
                    () -> new VideoProjectorScreen(origin.offset(1, 0, 0))));
            screens.add(new ScreenCase("lyric-projector", LyricProjectorScreen.class,
                    () -> new LyricProjectorScreen(origin.offset(2, 0, 0))));
            screens.add(new ScreenCase("speaker", SpeakerScreen.class,
                    () -> new SpeakerScreen(origin.offset(3, 0, 0))));
            screens.add(new ScreenCase("live-streamer", LiveStreamerScreen.class,
                    () -> new LiveStreamerScreen(origin.offset(4, 0, 0))));
            screens.add(new ScreenCase("control-console", ControlConsoleGuideScreen.class,
                    () -> new ControlConsoleGuideScreen(origin.offset(5, 0, 0))));
            screens.add(new ScreenCase("mp4-focus", MP4FocusScreen.class,
                    () -> new MP4FocusScreen(InteractionHand.MAIN_HAND)));
            screens.add(new ScreenCase("pad-focus", PadFocusScreen.class,
                    () -> new PadFocusScreen(InteractionHand.MAIN_HAND)));
            screens.add(new ScreenCase("pad-map", PadMapScreen.class,
                    () -> new PadMapScreen(InteractionHand.MAIN_HAND)));
            screens.add(new ScreenCase("holographic-editor", HolographicScreenConfigTestScreen.class,
                    HolographicScreenConfigTestScreen::new));
            screens.add(new ScreenCase("video-placeholder", VideoPlaceholderDebugScreen.class,
                    VideoPlaceholderDebugScreen::new));
            screens.add(new ScreenCase("whitelist-review", WhitelistReviewScreen.class,
                    () -> new WhitelistReviewScreen(new WhitelistReviewPacket(List.of()))));
            screens.add(new ScreenCase("whitelist-preview", WhitelistPreviewScreen.class,
                    () -> new WhitelistPreviewScreen(new WhitelistPreviewPacket(UUID.randomUUID(),
                            "Offline Bench Preview", "", "", "", 16, 9, 1, 7, 0, 0L, false))));
            screens.add(new ScreenCase("media-tool-binding", MediaToolBindingScreen.class,
                    () -> new MediaToolBindingScreen(new MediaToolBindingMenu(700, context.player().getInventory()),
                            context.player().getInventory(), Component.literal("Media Binding Bench"))));
            screens.add(new ScreenCase("media-tool-report", MediaToolReportScreen.class,
                    () -> new MediaToolReportScreen(new MediaToolReportMenu(701, context.player().getInventory()),
                            context.player().getInventory(), Component.literal("Media Report Bench"))));
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                throw new AssertionError("Integrated server is unavailable");
            }
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || !(player.level() instanceof ServerLevel level)) {
                        throw new IllegalStateException("Integrated server player is unavailable");
                    }
                    level.setBlockAndUpdate(origin, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
                    level.setBlockAndUpdate(origin.offset(1, 0, 0),
                            ModBlocks.VIDEO_PROJECTOR.get().defaultBlockState());
                    level.setBlockAndUpdate(origin.offset(2, 0, 0),
                            ModBlocks.LYRIC_PROJECTOR.get().defaultBlockState());
                    level.setBlockAndUpdate(origin.offset(3, 0, 0), ModBlocks.SPEAKER.get().defaultBlockState());
                    level.setBlockAndUpdate(origin.offset(4, 0, 0),
                            ModBlocks.LIVE_STREAMER.get().defaultBlockState());
                    level.setBlockAndUpdate(origin.offset(5, 0, 0),
                            ModBlocks.CONTROL_CONSOLE.get().defaultBlockState());
                    setupComplete.set(true);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            throwIfFailed();
            if (!setupComplete.get() || !fixturesReady(context)) {
                return BenchClientStepResult.CONTINUE;
            }
            if (context.minecraft().screen == null) {
                openCurrent(context);
            }
            return context.frames().sampleCount() > openedAtFrame
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            throwIfFailed();
            ScreenCase current = screens.get(index);
            if (!current.type().isInstance(context.minecraft().screen)) {
                throw new AssertionError("GUI matrix expected " + current.name() + " but found "
                        + context.minecraft().screen);
            }
            if (context.frames().sampleCount() <= openedAtFrame) {
                return BenchClientStepResult.CONTINUE;
            }
            if (gui == null || !gui.active()) {
                throw new AssertionError("GUI automation session was not active for " + current.name());
            }
            gui.snapshot();
            gui.close();
            gui = null;
            context.minecraft().setScreen(null);
            index++;
            context.metrics().record(SCREENS, index);
            if (index >= screens.size()) {
                return BenchClientStepResult.COMPLETE;
            }
            openCurrent(context);
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfFailed();
            if (index != screens.size() || context.minecraft().screen != null) {
                throw new AssertionError("GUI matrix did not close every Screen: " + index + "/" + screens.size());
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            context.minecraft().setScreen(null);
            if (gui != null) {
                gui.close();
                gui = null;
            }
            var server = context.minecraft().getSingleplayerServer();
            if (server != null && origin != null) {
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null && player.level() instanceof ServerLevel level) {
                        for (int offset = 0; offset < 6; offset++) {
                            level.setBlockAndUpdate(origin.offset(offset, 0, 0), Blocks.AIR.defaultBlockState());
                        }
                    }
                });
            }
        }

        private boolean fixturesReady(BenchClientContext context) {
            return context.level().getBlockEntity(origin) instanceof ModernTurntableBlockEntity
                    && context.level().getBlockEntity(origin.offset(1, 0, 0)) instanceof VideoProjectorBlockEntity
                    && context.level().getBlockEntity(origin.offset(2, 0, 0)) instanceof LyricProjectorBlockEntity
                    && context.level().getBlockEntity(origin.offset(3, 0, 0)) instanceof SpeakerBlockEntity
                    && context.level().getBlockEntity(origin.offset(4, 0, 0)) instanceof LiveStreamerBlockEntity
                    && context.level().getBlockEntity(origin.offset(5, 0, 0)) instanceof ControlConsoleBlockEntity;
        }

        private void openCurrent(BenchClientContext context) {
            ScreenCase current = screens.get(index);
            context.minecraft().setScreen(current.factory().get());
            gui = context.automation().beginGuiSession(current.type());
            openedAtFrame = context.frames().sampleCount();
        }

        private void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("GUI Screen matrix fixture failed", error);
            }
        }

        private record ScreenCase(String name, Class<? extends Screen> type,
                java.util.function.Supplier<? extends Screen> factory) {
        }
    }

    private static final class HandheldMediaContractScenario implements BenchClientScenario {
        private static final BenchMetricDescriptor CONTRACTS = new BenchMetricDescriptor(
                "ncpb.handheld.contracts", "count", MetricDirection.NEUTRAL);

        @Override
        public void setup(BenchClientContext context) {
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            var mp4 = MP4HandheldMediaProfile.INSTANCE.screenSpec();
            var pad = PadHandheldMediaProfile.INSTANCE.screenSpec();
            if (mp4.portraitWidth() != 256 || mp4.portraitHeight() != 448
                    || mp4.landscapeWidth() != 448 || mp4.targetWidth() <= 0 || mp4.targetHeight() <= 0) {
                throw new AssertionError("MP4 media surface geometry contract changed: " + mp4);
            }
            if (pad.portraitWidth() != 448 || pad.portraitHeight() != 256
                    || pad.landscapeWidth() != 256 || pad.targetWidth() <= 0 || pad.targetHeight() <= 0) {
                throw new AssertionError("Pad media surface geometry contract changed: " + pad);
            }
            UUID deviceId = UUID.randomUUID();
            UUID pointId = UUID.randomUUID();
            String sessionId = PadPlaybackSessionIds.create(deviceId, pointId, 7L).value();
            if (!PadPlaybackSessionIds.isPadSession(sessionId)
                    || !PadPlaybackSessionIds.matches(sessionId, deviceId, pointId)
                    || !pointId.equals(PadPlaybackSessionIds.pointId(sessionId))
                    || PadPlaybackSessionIds.isPadSession(deviceId + "-broken")) {
                throw new AssertionError("Pad session identity contract failed for " + sessionId);
            }
            context.metrics().record(CONTRACTS, 3);
            return BenchClientStepResult.COMPLETE;
        }
    }

    private static final class LiveStreamContractScenario implements BenchClientScenario {
        private static final BenchMetricDescriptor CONTRACTS = new BenchMetricDescriptor(
                "ncpb.live.contracts", "count", MetricDirection.NEUTRAL);

        @Override
        public void setup(BenchClientContext context) {
            LiveRoomMetadataRegistry.clear();
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            String roomId = "67373";
            String placeholder = BiliLiveRoomInput.placeholderUrl(roomId);
            if (!roomId.equals(BiliLiveRoomInput.parseRoomId("live:" + roomId))
                    || !roomId.equals(BiliLiveRoomInput.parseExplicitRoomId(
                            "https://live.bilibili.com/" + roomId + "?live_from=bench"))
                    || !roomId.equals(BiliLiveRoomInput.roomIdFromPlaceholder(placeholder))
                    || !BiliLiveRoomInput.parseExplicitRoomId(roomId).isEmpty()) {
                throw new AssertionError("Bilibili live-room input contract failed");
            }

            LiveReconnectPolicy reconnect = new LiveReconnectPolicy(3, 100L, 400L, 1_000L);
            long[] expected = { 100L, 200L, 400L, LiveReconnectPolicy.GIVE_UP };
            for (long delay : expected) {
                long actual = reconnect.onStreamEnded(50L);
                if (actual != delay) {
                    throw new AssertionError("Live reconnect backoff mismatch: expected=" + delay
                            + " actual=" + actual);
                }
            }
            if (reconnect.onStreamEnded(2_000L) != 100L || reconnect.consecutiveFailures() != 0) {
                throw new AssertionError("Healthy live stream did not reset reconnect backoff");
            }

            PlaybackSessionId session = PlaybackSessionId.of("bench-live-" + UUID.randomUUID());
            LiveRoomMetadataRegistry.SourceKey source = new LiveRoomMetadataRegistry.SourceKey(3, 70, 5);
            LiveRoomMetadataRegistry.publish(source, session, roomId, " Bench title ", "Music", "MV", 1);
            var snapshot = LiveRoomMetadataRegistry.snapshot(source, roomId).orElseThrow(
                    () -> new AssertionError("Live metadata was not published"));
            if (!"Bench title".equals(snapshot.title())
                    || LiveRoomMetadataRegistry.snapshot(source, "999").isPresent()
                    || !LiveRoomMetadataRegistry.remove(source, session)
                    || LiveRoomMetadataRegistry.size() != 0) {
                throw new AssertionError("Live metadata ownership contract failed");
            }

            com.zhongbai233.net_music_can_play_bili.client.MediaConsumerRegistry<BlockPos> consumers =
                    new com.zhongbai233.net_music_can_play_bili.client.MediaConsumerRegistry<>();
            BlockPos sourceA = new BlockPos(1, 2, 3);
            BlockPos sourceB = new BlockPos(4, 5, 6);
            BlockPos consumer = new BlockPos(7, 8, 9);
            consumers.register(sourceA, consumer);
            consumers.register(sourceB, consumer);
            if (!consumers.consumersFor(sourceA).isEmpty()
                    || !consumers.consumersFor(sourceB).equals(List.of(consumer))) {
                throw new AssertionError("Live consumer rebind contract failed");
            }
            consumers.clear();
            context.metrics().record(CONTRACTS, 4);
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public void teardown(BenchClientContext context) {
            LiveRoomMetadataRegistry.clear();
        }
    }

    private static <T> T requireBlockEntity(Level level, BlockPos pos, Class<T> type) {
        Object value = level.getBlockEntity(pos);
        if (!type.isInstance(value)) {
            throw new AssertionError(type.getSimpleName() + " is missing at " + pos + ": " + value);
        }
        return type.cast(value);
    }

    private static final class WhitelistManagementLifecycleScenario implements BenchClientScenario {
        private static final String REVIEW_ROOM = "8178490";
        private static final String DENIED_ROOM = "9000000000000001";
        private static final String EXPORT_FILE = "ncpb-whitelist-bench.csv";
        private static final BenchMetricDescriptor OPERATIONS = new BenchMetricDescriptor(
                "ncpb.whitelist.operations", "count", MetricDirection.NEUTRAL);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<WhitelistReviewPacket> reviewPayload = new AtomicReference<>();
        private final AtomicReference<String> exportedCsv = new AtomicReference<>();
        private final AtomicBoolean setupComplete = new AtomicBoolean();
        private boolean originalWhitelistEnabled;
        private boolean reviewRoomAddedByBench;
        private UUID playerId;
        private BlockPos livePos;
        private Path exportPath;
        private BenchGuiSession gui;
        private long openedAtFrame;
        private int phase;
        private int operations;

        @Override
        public void setup(BenchClientContext context) {
            originalWhitelistEnabled = Config.enableLinkWhitelist;
            Config.enableLinkWhitelist = true;
            playerId = context.player().getUUID();
            livePos = context.player().blockPosition().offset(2, 0, 2).immutable();
            exportPath = context.minecraft().gameDirectory.toPath().resolve("exports")
                    .resolve("net_music_can_play_bili").resolve(EXPORT_FILE);
            try {
                Files.deleteIfExists(exportPath);
            } catch (IOException e) {
                throw new AssertionError("Could not clear the whitelist Bench export", e);
            }
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                throw new AssertionError("Integrated server is unavailable");
            }
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || !(player.level() instanceof ServerLevel level)) {
                        throw new IllegalStateException("Integrated server player is unavailable");
                    }
                    level.setBlockAndUpdate(livePos, ModBlocks.LIVE_STREAMER.get().defaultBlockState());
                    LiveStreamerBlockEntity live = requireBlockEntity(level, livePos, LiveStreamerBlockEntity.class);
                    if (!live.setRoomId(level, DENIED_ROOM, player)) {
                        throw new AssertionError("Bench denial room was not accepted as syntactically valid");
                    }
                    live.startLive(level, player);
                    if (live.isPlaying() || live.isWaitingForLive()) {
                        throw new AssertionError("Non-whitelisted live room passed the production start gate");
                    }
                    String denial = BiliWhitelistManager.denialMessage(player, "live:" + DENIED_ROOM,
                            "启动直播").getString();
                    if (!denial.contains("未加入白名单") || !denial.contains(DENIED_ROOM)) {
                        throw new AssertionError("Whitelist denial did not expose the rejected live room: " + denial);
                    }
                    operations += 2;

                    BiliWhitelistManager.AddResult deniedAdd = BiliWhitelistManager.add(server,
                            "https://live.bilibili.com/" + DENIED_ROOM, player);
                    if (deniedAdd.status() != BiliWhitelistManager.AddResult.Status.ADDED
                            || !BiliWhitelistManager.isAllowed(server, "live:" + DENIED_ROOM)) {
                        throw new AssertionError("Whitelist add did not allow the canonical live room: " + deniedAdd);
                    }
                    operations++;

                    boolean reviewAlreadyAllowed = BiliWhitelistManager.isAllowed(server, "live:" + REVIEW_ROOM);
                    BiliWhitelistManager.AddResult reviewAdd = BiliWhitelistManager.add(server,
                            "https://live.bilibili.com/" + REVIEW_ROOM + "?live_from=modbench", player);
                    if (!reviewAlreadyAllowed
                            && reviewAdd.status() != BiliWhitelistManager.AddResult.Status.ADDED
                            || reviewAlreadyAllowed
                            && reviewAdd.status() != BiliWhitelistManager.AddResult.Status.DUPLICATE) {
                        throw new AssertionError("Review room add/duplicate result was inconsistent: " + reviewAdd);
                    }
                    reviewRoomAddedByBench = !reviewAlreadyAllowed;
                    operations++;

                    WhitelistReviewPacket packet = WhitelistReviewPacket.create(BiliWhitelistManager.entries(server));
                    boolean roomVisible = packet.entries().stream()
                            .anyMatch(entry -> ("live:" + REVIEW_ROOM).equals(entry.id()));
                    boolean deniedVisible = packet.entries().stream()
                            .anyMatch(entry -> ("live:" + DENIED_ROOM).equals(entry.id()));
                    if (!roomVisible || !deniedVisible) {
                        throw new AssertionError("Whitelist review list omitted Bench entries: " + packet.entries());
                    }
                    reviewPayload.set(packet);
                    operations++;

                    String csv = BiliWhitelistManager.exportCsv(server);
                    if (!csv.startsWith("type,id,addedAt,addedByName,addedByUuid,originalInput\r\n")
                            || !csv.contains("\"live\",\"live:" + REVIEW_ROOM + "\"")) {
                        throw new AssertionError("Whitelist CSV export omitted header or live room");
                    }
                    exportedCsv.set(csv);
                    operations++;

                    BiliWhitelistManager.RemoveResult removed = BiliWhitelistManager.remove(server,
                            "live:" + DENIED_ROOM);
                    if (removed.status() != BiliWhitelistManager.RemoveResult.Status.REMOVED
                            || BiliWhitelistManager.isAllowed(server, "live:" + DENIED_ROOM)) {
                        throw new AssertionError("Whitelist remove did not restore the denial gate: " + removed);
                    }
                    operations++;
                    setupComplete.set(true);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            throwIfFailed();
            if (!setupComplete.get() || reviewPayload.get() == null || exportedCsv.get() == null) {
                return BenchClientStepResult.CONTINUE;
            }
            context.minecraft().setScreen(new WhitelistReviewScreen(reviewPayload.get(), "live:" + REVIEW_ROOM));
            gui = context.automation().beginGuiSession(WhitelistReviewScreen.class);
            openedAtFrame = context.frames().sampleCount();
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return context.frames().sampleCount() > openedAtFrame
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            throwIfFailed();
            if (context.frames().sampleCount() <= openedAtFrame) {
                return BenchClientStepResult.CONTINUE;
            }
            if (phase == 0) {
                if (!(context.minecraft().screen instanceof WhitelistReviewScreen)
                        || gui == null || !gui.active() || gui.snapshot().flattened().size() < 4) {
                    throw new AssertionError("Whitelist review list did not render its controls");
                }
                gui.close();
                WhitelistPreviewPacket preview = new WhitelistPreviewPacket(UUID.randomUUID(),
                        "Live room " + REVIEW_ROOM, "", "", "",
                        16, 9, 1, 7, 0, 0L, false);
                context.minecraft().setScreen(new WhitelistPreviewScreen(preview));
                gui = context.automation().beginGuiSession(WhitelistPreviewScreen.class);
                openedAtFrame = context.frames().sampleCount();
                phase = 1;
                return BenchClientStepResult.CONTINUE;
            }
            if (!(context.minecraft().screen instanceof WhitelistPreviewScreen)
                    || gui == null || !gui.active() || gui.snapshot().flattened().isEmpty()) {
                throw new AssertionError("Whitelist preview screen did not render");
            }
            WhitelistCsvExportClient.save(new WhitelistCsvExportPacket(EXPORT_FILE, exportedCsv.get()));
            if (!Files.isRegularFile(exportPath)) {
                throw new AssertionError("Whitelist CSV was not written to the client export directory");
            }
            operations++;
            context.metrics().record(OPERATIONS, operations);
            gui.close();
            gui = null;
            context.minecraft().setScreen(null);
            phase = 2;
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfFailed();
            if (phase != 2 || operations != 8 || !Files.isRegularFile(exportPath)
                    || context.minecraft().screen != null) {
                throw new AssertionError("Whitelist lifecycle did not complete: phase=" + phase
                        + " operations=" + operations + " export=" + exportPath);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            context.minecraft().setScreen(null);
            if (gui != null) {
                gui.close();
                gui = null;
            }
            try {
                if (exportPath != null) {
                    Files.deleteIfExists(exportPath);
                }
            } catch (IOException ignored) {
            }
            var server = context.minecraft().getSingleplayerServer();
            if (server != null) {
                boolean removeReviewRoom = reviewRoomAddedByBench;
                server.execute(() -> {
                    try {
                        BiliWhitelistManager.remove(server, "live:" + DENIED_ROOM);
                        if (removeReviewRoom) {
                            BiliWhitelistManager.remove(server, "live:" + REVIEW_ROOM);
                        }
                    } catch (IOException ignored) {
                    }
                    if (livePos != null && server.overworld().isLoaded(livePos)) {
                        server.overworld().setBlockAndUpdate(livePos, Blocks.AIR.defaultBlockState());
                    }
                });
            }
            Config.enableLinkWhitelist = originalWhitelistEnabled;
        }

        private void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Whitelist management lifecycle failed", error);
            }
        }
    }

    private static final class RealLiveDeviceTopologyScenario implements BenchClientScenario {
        private static final BenchMetricDescriptor CONSUMERS = new BenchMetricDescriptor(
                "ncpb.real_live.consumers", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor AUDIO_MILLIS = new BenchMetricDescriptor(
                "ncpb.real_live.audio_millis", "ms", MetricDirection.HIGHER_IS_BETTER);
        private final String roomId = System.getProperty("ncpb.live.real_bench.room", "8178490").trim();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<BiliLiveStreamResolver.LiveRoom> resolvedRoom = new AtomicReference<>();
        private final AtomicBoolean fixtureReady = new AtomicBoolean();
        private final AtomicBoolean roomAddedByBench = new AtomicBoolean();
        private UUID playerId;
        private BlockPos livePos;
        private BlockPos projectorPos;
        private BlockPos speakerPos;
        private BlockPos consolePos;
        private int stableTicks;

        @Override
        public void setup(BenchClientContext context) {
            if (!BiliLiveStreamResolver.isValidRoomId(roomId)) {
                throw new AssertionError("Invalid real-live Bench room: " + roomId);
            }
            playerId = context.player().getUUID();
            BlockPos origin = context.player().blockPosition().offset(2, 0, 2).immutable();
            livePos = origin;
            projectorPos = origin.offset(2, 0, 0);
            speakerPos = origin.offset(0, 0, 2);
            consolePos = origin.offset(2, 0, 2);
            CompletableFuture.runAsync(() -> {
                try {
                    BiliLiveStreamResolver.LiveRoom room = BiliLiveStreamResolver.resolve(roomId);
                    if (!room.isLive() || room.streams().isEmpty()) {
                        throw new IOException("Bilibili room " + roomId
                                + " is currently offline or returned no playable streams");
                    }
                    resolvedRoom.set(room);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                throw new AssertionError("Integrated server is unavailable");
            }
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || !(player.level() instanceof ServerLevel level)) {
                        throw new IllegalStateException("Integrated server player is unavailable");
                    }
                    if (Config.enableLinkWhitelist && !BiliWhitelistManager.isAllowed(server, "live:" + roomId)) {
                        BiliWhitelistManager.AddResult added = BiliWhitelistManager.add(server,
                                "https://live.bilibili.com/" + roomId, player);
                        if (added.status() != BiliWhitelistManager.AddResult.Status.ADDED) {
                            throw new AssertionError("Could not temporarily whitelist real-live room: " + added);
                        }
                        roomAddedByBench.set(true);
                    }
                    for (BlockPos pos : List.of(livePos, projectorPos, speakerPos, consolePos)) {
                        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                    }
                    level.setBlockAndUpdate(livePos, ModBlocks.LIVE_STREAMER.get().defaultBlockState());
                    level.setBlockAndUpdate(projectorPos, ModBlocks.VIDEO_PROJECTOR.get().defaultBlockState());
                    level.setBlockAndUpdate(speakerPos, ModBlocks.SPEAKER.get().defaultBlockState());
                    level.setBlockAndUpdate(consolePos, ModBlocks.CONTROL_CONSOLE.get().defaultBlockState());

                    VideoProjectorBlockEntity projector = requireBlockEntity(level, projectorPos,
                            VideoProjectorBlockEntity.class);
                    projector.setPreferredQuality(80);
                    projector.linkTo(livePos);
                    SpeakerBlockEntity speaker = requireBlockEntity(level, speakerPos, SpeakerBlockEntity.class);
                    speaker.setChannelIndex(SpeakerBlockEntity.CH_L);
                    speaker.setVolume(1.0F);
                    speaker.linkTo(livePos);
                    ControlConsoleBlockEntity console = requireBlockEntity(level, consolePos,
                            ControlConsoleBlockEntity.class);
                    console.linkTo(level.dimension().identifier().toString(), livePos,
                            ControlConsoleDocument.SourceKind.LIVE_STREAMER);
                    List<ControlConsoleElement> elements = List.of(
                            ControlConsoleElement.defaultScreen(),
                            new ControlConsoleElement(ControlConsoleElement.Type.AUDIO, "直播音频", 1.4F,
                                    0.0F, 0.0F, 0.25F, 1.0F, 0.0F, 0.0F, 0.0F));
                    if (!console.replaceDocument(console.document().revision(), "Live Bench " + roomId,
                            32.0D, 16.0D, 32.0D, elements)) {
                        throw new AssertionError("Could not install console screen/audio elements");
                    }
                    LiveStreamerBlockEntity live = requireBlockEntity(level, livePos, LiveStreamerBlockEntity.class);
                    if (!live.setRoomId(level, roomId, player)) {
                        throw new AssertionError("Live streamer rejected Bench room " + roomId);
                    }
                    live.startLive(level, player);
                    if (!player.teleportTo(level, consolePos.getX() + 0.5D, consolePos.getY() + 1.0D,
                            consolePos.getZ() + 3.0D, Set.<Relative>of(), 180.0F, 0.0F, true)) {
                        throw new AssertionError("Could not place player inside console range");
                    }
                    fixtureReady.set(true);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            throwIfFailed();
            if (!fixtureReady.get() || resolvedRoom.get() == null || !clientFixturesReady(context)
                    || !context.environment().readiness().ready() || context.frames().sampleCount() < 2) {
                return BenchClientStepResult.CONTINUE;
            }
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            throwIfFailed();
            if (!(context.level().getBlockEntity(livePos) instanceof LiveStreamerBlockEntity live)
                    || !live.isPlaying()) {
                return BenchClientStepResult.CONTINUE;
            }
            ClientAudioOutputRegistry.AudioTimeline audio = ClientAudioOutputRegistry.getAudioTimeline(livePos);
            String session = audio.playbackSessionId().map(PlaybackSessionId::value).orElse("");
            if (audio.combinedMillis() < 0L || audio.relayRegisteredCount() < 2 || session.isBlank()) {
                return BenchClientStepResult.CONTINUE;
            }
            LiveStreamerVideoClient.sync(livePos, session);
            if (!VideoBillboardPreview.isSessionRunning(session)
                    || !VideoBillboardPreview.currentProjectorFrame(projectorPos).hasFrame()
                    || !ControlConsoleRenderer.consumerLeaseDiagnostic(consolePos).active()) {
                return BenchClientStepResult.CONTINUE;
            }
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            throwIfFailed();
            ClientAudioOutputRegistry.AudioTimeline audio = ClientAudioOutputRegistry.getAudioTimeline(livePos);
            String session = audio.playbackSessionId().map(PlaybackSessionId::value).orElse("");
            boolean loaded = audio.combinedMillis() >= 0L && audio.relayRegisteredCount() >= 2
                    && !session.isBlank() && VideoBillboardPreview.isSessionRunning(session)
                    && VideoBillboardPreview.currentProjectorFrame(projectorPos).hasFrame()
                    && ControlConsoleRenderer.consumerLeaseDiagnostic(consolePos).active();
            if (!loaded) {
                stableTicks = 0;
                return BenchClientStepResult.CONTINUE;
            }
            context.metrics().record(CONSUMERS, 4);
            context.metrics().record(AUDIO_MILLIS, audio.combinedMillis());
            return ++stableTicks >= 40 ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfFailed();
            BiliLiveStreamResolver.LiveRoom room = resolvedRoom.get();
            if (room == null || !room.isLive() || room.streams().isEmpty() || stableTicks < 40
                    || !clientFixturesReady(context)) {
                throw new AssertionError("Real-live topology did not remain loaded: room=" + room
                        + " stableTicks=" + stableTicks);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            LiveStreamerVideoClient.clear();
            ClientAudioOutputRegistry.cleanup();
            var server = context.minecraft().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    if (server.overworld().getBlockEntity(livePos) instanceof LiveStreamerBlockEntity live) {
                        live.stopLive();
                    }
                    for (BlockPos pos : List.of(livePos, projectorPos, speakerPos, consolePos)) {
                        server.overworld().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                    if (roomAddedByBench.get()) {
                        try {
                            BiliWhitelistManager.remove(server, "live:" + roomId);
                        } catch (IOException ignored) {
                        }
                    }
                });
            }
        }

        private boolean clientFixturesReady(BenchClientContext context) {
            return context.level().getBlockEntity(livePos) instanceof LiveStreamerBlockEntity
                    && context.level().getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity projector
                    && livePos.equals(projector.getLinkedTurntablePos())
                    && context.level().getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker
                    && livePos.equals(speaker.getLinkedTurntablePos())
                    && context.level().getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console
                    && console.document().sourceKind() == ControlConsoleDocument.SourceKind.LIVE_STREAMER;
        }

        private void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Real-live device topology failed for room " + roomId, error);
            }
        }
    }

    private static final class EditorGuiLifecycleScenario implements BenchClientScenario {
        private static final int ROUNDS = 30;
        private static final BenchMetricDescriptor WIDGETS = new BenchMetricDescriptor(
                "ncpb.gui.widgets", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor OPEN_ROUNDS = new BenchMetricDescriptor(
                "ncpb.gui.open_rounds", "count", MetricDirection.NEUTRAL);
        private BenchGuiSession gui;
        private int round;

        @Override
        public void setup(BenchClientContext context) {
            context.minecraft().setScreen(new HolographicScreenConfigTestScreen());
            gui = context.automation().beginGuiSession(HolographicScreenConfigTestScreen.class);
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            return context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            if (!(context.minecraft().screen instanceof HolographicScreenConfigTestScreen)) {
                throw new AssertionError("Editor Screen did not remain open for a rendered tick");
            }
            int widgets = gui.snapshot().flattened().size();
            if (widgets < 4) {
                throw new AssertionError("Editor interaction tree unexpectedly small: " + widgets);
            }
            context.metrics().record(WIDGETS, widgets);
            context.metrics().record(OPEN_ROUNDS, ++round);
            if (round >= ROUNDS) {
                context.minecraft().setScreen(null);
                return BenchClientStepResult.COMPLETE;
            }
            context.minecraft().setScreen(new HolographicScreenConfigTestScreen());
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (round != ROUNDS || context.minecraft().screen != null || !gui.active()) {
                throw new AssertionError("GUI lifecycle did not converge: rounds=" + round);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            context.minecraft().setScreen(null);
            if (gui != null) gui.close();
        }
    }

    private static final class TerrainLodRoundTripScenario implements BenchClientScenario {
        private static final boolean COMPAT_MATRIX = Boolean.getBoolean("ncpb.terrain.compat_matrix");
        private static final Set<String> REQUIRED_COMPAT_MODS = Set.of(
                "iris", "sodium", "biomesoplenty", "glitchcore", "terrablender",
                "colossalchests", "cyclopscore");
        private static final String RESOURCE_PACK_FILE = "Accurate_textures_26.1.2.zip";
        private static final BenchMetricDescriptor MATERIAL_4_CELLS = new BenchMetricDescriptor(
                "ncpb.terrain.material_4_cells", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor MATERIAL_8_CELLS = new BenchMetricDescriptor(
                "ncpb.terrain.material_8_cells", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor TINTED_CELLS = new BenchMetricDescriptor(
                "ncpb.terrain.tinted_cells", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor BLOCK_ENTITIES = new BenchMetricDescriptor(
                "ncpb.terrain.block_entities", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor MATERIAL_UPLOADS = new BenchMetricDescriptor(
                "ncpb.terrain.material_uploads", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor TRANSLUCENT_UPLOADS = new BenchMetricDescriptor(
                "ncpb.terrain.translucent_uploads", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor TRANSLUCENT_RESORTS = new BenchMetricDescriptor(
                "ncpb.terrain.translucent_resorts", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor BLOCK_ENTITY_SUBMISSIONS = new BenchMetricDescriptor(
                "ncpb.terrain.block_entity_submissions", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor RENDER_FAILURES = new BenchMetricDescriptor(
                "ncpb.terrain.render_failures", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor EXTERNAL_TINTED_CELLS = new BenchMetricDescriptor(
                "ncpb.terrain.external_tinted_cells", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor EXTERNAL_FLUID_CELLS = new BenchMetricDescriptor(
                "ncpb.terrain.external_fluid_cells", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor EXTERNAL_BLOCK_ENTITIES = new BenchMetricDescriptor(
                "ncpb.terrain.external_block_entities", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor RESOURCE_PACK_ACTIVE = new BenchMetricDescriptor(
                "ncpb.terrain.resource_pack_active", "boolean", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor SHADER_PACK_ACTIVE = new BenchMetricDescriptor(
                "ncpb.terrain.shader_pack_active", "boolean", MetricDirection.NEUTRAL);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean setupComplete = new AtomicBoolean();
        private final CopyOnWriteArrayList<BlockPos> fixturePositions = new CopyOnWriteArrayList<>();
        private BlockPos origin;
        private BlockPos chestPos;
        private BlockPos midGlassPos;
        private BlockPos farTintPos;
        private BlockPos customFluidPos;
        private Block customBlockEntityBlock;
        private Block customTintBlock;
        private Block customFluidBlock;
        private com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds bounds;
        private TerrainPreviewRenderDiagnostics.Snapshot renderBaseline;
        private TerrainPipBenchScreen screen;
        private BenchGuiSession gui;
        private CompletableFuture<Path> firstScreenshot;
        private CompletableFuture<Path> secondScreenshot;
        private int phase;
        private int releaseTicks;
        private long generation;
        private long resortsBeforeRotation;
        private int sampledBeforeReopen;
        private boolean cacheRoundTripDone;
        private boolean resourcePackVerified;
        private boolean shaderPackVerified;
        private long maxExternalTinted;
        private long maxExternalFluids;
        private long maxExternalBlockEntities;
        private long maxTranslucentUploads;
        private UUID playerId;

        @Override
        public void setup(BenchClientContext context) {
            origin = context.player().blockPosition();
            playerId = context.player().getUUID();
            bounds = TerrainHardRangeBounds.around(origin.getX(), origin.getY(), origin.getZ(),
                    56.0D, 16.0D, 56.0D, context.level().getMinY(), context.level().getMaxY());
            int fixtureY = Math.min(context.level().getMaxY() - 16, origin.getY() + 8);
            chestPos = new BlockPos(origin.getX() + 2, origin.getY() + 2, origin.getZ() + 2);
            midGlassPos = new BlockPos(alignDown(origin.getX() + 24, 4), alignDown(fixtureY, 4),
                    alignDown(origin.getZ(), 4));
            farTintPos = new BlockPos(alignDown(origin.getX() + 48, 8), alignDown(fixtureY, 8),
                    alignDown(origin.getZ(), 8));
            customFluidPos = origin.offset(7, 3, 3);
            if (COMPAT_MATRIX) {
                verifyCompatibilityMods();
                customBlockEntityBlock = requiredBlock("colossalchests:uncolossal_chest");
                customTintBlock = requiredBlock("biomesoplenty:palm_leaves");
                customFluidBlock = requiredBlock("biomesoplenty:blood");
                resourcePackVerified = verifyAccurateTexturesResourcePack(context);
            }
            renderBaseline = TerrainPreviewRenderDiagnostics.snapshot();
            TerrainPreviewManager.clear();
            prepareFixture(context);
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            throwIfFailed();
            if (!setupComplete.get() || !fixtureVisible(context) || !context.environment().readiness().ready()) {
                return BenchClientStepResult.CONTINUE;
            }
            if (screen == null) {
                screen = new TerrainPipBenchScreen(origin);
                context.minecraft().setScreen(screen);
                gui = context.automation().beginGuiSession(TerrainPipBenchScreen.class);
            }
            return context.minecraft().screen == screen && context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            throwIfFailed();
            Vector3d fixedCore = new Vector3d(0.5D, 0.5D, 0.5D);
            TerrainPreviewManager.update(context.level(), origin, bounds, fixedCore);
            TerrainPreviewFrame frame = TerrainPreviewManager.frame();
            if (generation == 0L) {
                generation = frame.generation();
            } else if (frame.generation() != generation) {
                throw new AssertionError("Fixed terrain core unexpectedly rebuilt: "
                        + generation + " -> " + frame.generation());
            }
            long material4 = materialCells(frame, 4);
            long material8 = materialCells(frame, 8);
            long tinted = frame.fullDetailSections().stream().flatMap(section -> section.blocks().stream())
                    .filter(block -> !block.tintLayers().isEmpty()).count();
            long externalTinted = externalTintedCells(frame);
            long externalFluids = externalFluidCells(frame);
            long externalBlockEntities = externalBlockEntities(frame);
            maxExternalTinted = Math.max(maxExternalTinted, externalTinted);
            maxExternalFluids = Math.max(maxExternalFluids, externalFluids);
            maxExternalBlockEntities = Math.max(maxExternalBlockEntities, externalBlockEntities);
            if (COMPAT_MATRIX && IrisShaderpackCompat.isShaderPackInUse()) {
                shaderPackVerified = true;
            }
            TerrainPreviewRenderDiagnostics.Snapshot rendered = TerrainPreviewRenderDiagnostics.snapshot()
                    .deltaFrom(renderBaseline);
            maxTranslucentUploads = Math.max(maxTranslucentUploads, rendered.translucentSectionUploads());
            context.metrics().record(MATERIAL_4_CELLS, material4);
            context.metrics().record(MATERIAL_8_CELLS, material8);
            context.metrics().record(TINTED_CELLS, tinted);
            context.metrics().record(BLOCK_ENTITIES, frame.blockEntities().size());
            context.metrics().record(MATERIAL_UPLOADS, rendered.materialSectionUploads());
            context.metrics().record(TRANSLUCENT_UPLOADS, rendered.translucentSectionUploads());
            context.metrics().record(TRANSLUCENT_RESORTS, rendered.translucentResorts());
            context.metrics().record(BLOCK_ENTITY_SUBMISSIONS, rendered.blockEntitySubmissions());
            context.metrics().record(RENDER_FAILURES, rendered.failures());
            context.metrics().record(EXTERNAL_TINTED_CELLS, externalTinted);
            context.metrics().record(EXTERNAL_FLUID_CELLS, externalFluids);
            context.metrics().record(EXTERNAL_BLOCK_ENTITIES, externalBlockEntities);
            context.metrics().record(RESOURCE_PACK_ACTIVE, resourcePackVerified ? 1L : 0L);
            context.metrics().record(SHADER_PACK_ACTIVE, shaderPackVerified ? 1L : 0L);
            if (rendered.failures() != 0L) {
                throw new AssertionError("Terrain PIP reported " + rendered.failures() + " render failure(s)");
            }

            boolean compatReady = !COMPAT_MATRIX || resourcePackVerified && shaderPackVerified
                    && externalTinted > 0L && externalFluids > 0L && externalBlockEntities > 0L
                    && rendered.translucentSectionUploads() >= 2L;
            if (phase == 0 && material4 > 0L && material8 > 0L && tinted > 0L && compatReady
                    && !frame.blockEntities().isEmpty() && rendered.materialSectionUploads() > 0L
                    && rendered.translucentSectionUploads() > 0L && rendered.blockEntitySubmissions() > 0L) {
                sampledBeforeReopen = frame.sampledSections();
                TerrainPreviewManager.close(origin);
                TerrainPreviewManager.update(context.level(), origin, bounds, fixedCore);
                TerrainPreviewFrame reopened = TerrainPreviewManager.frame();
                if (reopened.generation() != generation
                        || reopened.sampledSections() < sampledBeforeReopen
                        || materialCells(reopened, 4) == 0L || materialCells(reopened, 8) == 0L) {
                    throw new AssertionError("Parked terrain cache did not restore material LOD immediately");
                }
                cacheRoundTripDone = true;
                firstScreenshot = context.automation().captureScreenshot("terrain-material-pip-before",
                        com.zhongbai233.bench.api.neoforge.client.BenchCaptureOptions.immediate());
                phase = 1;
            } else if (phase == 1 && completedScreenshot(firstScreenshot)) {
                resortsBeforeRotation = rendered.translucentResorts();
                screen.setAngleDegrees(145.0D);
                phase = 2;
            } else if (phase == 2 && rendered.translucentResorts() > resortsBeforeRotation) {
                secondScreenshot = context.automation().captureScreenshot("terrain-material-pip-after",
                        com.zhongbai233.bench.api.neoforge.client.BenchCaptureOptions.immediate());
                phase = 3;
            } else if (phase == 3 && completedScreenshot(secondScreenshot)) {
                screen.setRenderTerrain(false);
                TerrainPreviewManager.clear();
                phase = 4;
            } else if (phase == 4 && ++releaseTicks >= 3) {
                context.minecraft().setScreen(null);
                if (gui != null) {
                    gui.close();
                }
                phase = 5;
                return BenchClientStepResult.COMPLETE;
            }
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            throwIfFailed();
            TerrainPreviewRenderDiagnostics.Snapshot rendered = TerrainPreviewRenderDiagnostics.snapshot()
                    .deltaFrom(renderBaseline);
            if (phase != 5 || !cacheRoundTripDone || context.minecraft().screen != null
                    || gui == null || gui.active()
                    || !completedScreenshot(firstScreenshot) || !completedScreenshot(secondScreenshot)
                    || rendered.materialSectionUploads() == 0L
                    || rendered.translucentSectionUploads() == 0L
                    || rendered.translucentResorts() <= resortsBeforeRotation
                    || rendered.blockEntitySubmissions() == 0L || rendered.failures() != 0L
                    || COMPAT_MATRIX && (!resourcePackVerified || !shaderPackVerified
                            || maxExternalTinted == 0L || maxExternalFluids == 0L
                            || maxExternalBlockEntities == 0L || maxTranslucentUploads < 2L)) {
                throw new AssertionError("Terrain material PIP did not converge: phase=" + phase
                        + ", cacheRoundTrip=" + cacheRoundTripDone + ", diagnostics=" + rendered
                        + ", externalTinted=" + maxExternalTinted + ", externalFluids=" + maxExternalFluids
                        + ", externalBlockEntities=" + maxExternalBlockEntities
                        + ", maxTranslucentUploads=" + maxTranslucentUploads);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            if (context.minecraft().screen == screen) {
                context.minecraft().setScreen(null);
            }
            if (gui != null) {
                gui.close();
            }
            TerrainPreviewManager.clear();
            var server = context.minecraft().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null && player.level() instanceof ServerLevel level) {
                        fixturePositions.forEach(pos -> level.setBlockAndUpdate(pos,
                                Blocks.AIR.defaultBlockState()));
                    }
                });
            }
        }

        private void prepareFixture(BenchClientContext context) {
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                failure.compareAndSet(null, new IllegalStateException("Integrated server is unavailable"));
                return;
            }
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || !(player.level() instanceof ServerLevel level)) {
                        throw new IllegalStateException("Integrated server player is unavailable");
                    }
                    placeFixture(level, chestPos, COMPAT_MATRIX
                            ? customBlockEntityBlock.defaultBlockState() : Blocks.CHEST.defaultBlockState());
                    for (int x = 0; x < 4; x++) {
                        for (int z = 0; z < 4; z++) {
                            placeFixture(level, midGlassPos.offset(x, 0, z),
                                    Blocks.BLUE_STAINED_GLASS.defaultBlockState());
                        }
                    }
                    for (int x = 0; x < 8; x++) {
                        for (int z = 0; z < 8; z++) {
                            placeFixture(level, farTintPos.offset(x, 0, z), COMPAT_MATRIX
                                    ? customTintBlock.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState());
                        }
                    }
                    if (COMPAT_MATRIX) {
                        placeFluidBasin(level);
                    }
                    setupComplete.set(true);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
        }

        private void placeFixture(ServerLevel level, BlockPos pos, BlockState state) {
            if (!level.getBlockState(pos).isAir()) {
                throw new AssertionError("Terrain bench fixture would overwrite a non-air block at " + pos);
            }
            fixturePositions.add(pos.immutable());
            level.setBlockAndUpdate(pos, state);
        }

        private boolean fixtureVisible(BenchClientContext context) {
            return context.level().getBlockEntity(chestPos) != null
                    && (!COMPAT_MATRIX || context.level().getBlockEntity(chestPos).getClass()
                            .getName().startsWith("org.cyclops.colossalchests."))
                    && context.level().getBlockState(midGlassPos).is(Blocks.BLUE_STAINED_GLASS)
                    && context.level().getBlockState(farTintPos).is(
                            COMPAT_MATRIX ? customTintBlock : Blocks.GRASS_BLOCK)
                    && (!COMPAT_MATRIX || context.level().getBlockState(customFluidPos).is(customFluidBlock)
                            && !context.level().getFluidState(customFluidPos).isEmpty());
        }

        private void placeFluidBasin(ServerLevel level) {
            placeFixture(level, customFluidPos.below(), Blocks.STONE.defaultBlockState());
            placeFixture(level, customFluidPos.north(), Blocks.STONE.defaultBlockState());
            placeFixture(level, customFluidPos.south(), Blocks.STONE.defaultBlockState());
            placeFixture(level, customFluidPos.east(), Blocks.STONE.defaultBlockState());
            placeFixture(level, customFluidPos.west(), Blocks.STONE.defaultBlockState());
            placeFixture(level, customFluidPos, customFluidBlock.defaultBlockState());
        }

        private static void verifyCompatibilityMods() {
            List<String> missing = REQUIRED_COMPAT_MODS.stream()
                    .filter(modId -> !ModList.get().isLoaded(modId)).sorted().toList();
            if (!missing.isEmpty()) {
                throw new AssertionError("Terrain compatibility matrix is missing required mods: " + missing);
            }
        }

        private static Block requiredBlock(String idText) {
            Identifier id = Identifier.parse(idText);
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            if (block == null || block == Blocks.AIR || !BuiltInRegistries.BLOCK.containsKey(id)) {
                throw new AssertionError("Terrain compatibility block is not registered: " + idText);
            }
            return block;
        }

        private static boolean verifyAccurateTexturesResourcePack(BenchClientContext context) {
            Identifier grassTexture = Identifier.withDefaultNamespace("textures/block/grass_block_top.png");
            var resource = context.minecraft().getResourceManager().getResource(grassTexture)
                    .orElseThrow(() -> new AssertionError("Missing active grass texture resource"));
            if (!resource.sourcePackId().contains(RESOURCE_PACK_FILE)) {
                throw new AssertionError("Accurate Textures resource pack is not authoritative for " + grassTexture
                        + ": source=" + resource.sourcePackId());
            }
            try (var input = resource.open()) {
                var image = ImageIO.read(input);
                if (image == null || image.getWidth() != 32 || image.getHeight() != 32) {
                    throw new AssertionError("Accurate Textures grass texture must be 32x32, got "
                            + (image == null ? "undecodable" : image.getWidth() + "x" + image.getHeight()));
                }
            } catch (IOException error) {
                throw new AssertionError("Failed to inspect Accurate Textures grass texture", error);
            }
            return true;
        }

        private static long externalTintedCells(TerrainPreviewFrame frame) {
            if (!COMPAT_MATRIX) {
                return 0L;
            }
            return frame.fullDetailSections().stream().flatMap(section -> section.blocks().stream())
                    .filter(block -> "biomesoplenty".equals(
                            BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).getNamespace()))
                    .filter(block -> !block.tintLayers().isEmpty()).count();
        }

        private static long externalFluidCells(TerrainPreviewFrame frame) {
            if (!COMPAT_MATRIX) {
                return 0L;
            }
            return frame.fullDetailSections().stream().flatMap(section -> section.blocks().stream())
                    .filter(block -> "biomesoplenty".equals(
                            BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).getNamespace()))
                    .filter(block -> !block.state().getFluidState().isEmpty()).count();
        }

        private long externalBlockEntities(TerrainPreviewFrame frame) {
            if (!COMPAT_MATRIX) {
                return 0L;
            }
            return frame.blockEntities().stream().filter(blockEntity -> blockEntity.worldPos().equals(chestPos))
                    .filter(blockEntity -> blockEntity.renderState().getClass().getName()
                            .startsWith("org.cyclops.colossalchests.")).count();
        }

        private static int alignDown(int value, int cellSize) {
            return Math.floorDiv(value, cellSize) * cellSize;
        }

        private static long materialCells(TerrainPreviewFrame frame, int cellSize) {
            return frame.fullDetailSections().stream().flatMap(section -> section.blocks().stream())
                    .filter(block -> block.cellSize() == cellSize).count();
        }

        private static boolean completedScreenshot(CompletableFuture<Path> screenshot) {
            if (screenshot == null || !screenshot.isDone()) {
                return false;
            }
            Path path = screenshot.join();
            if (path == null || !java.nio.file.Files.isRegularFile(path)) {
                throw new AssertionError("Terrain PIP screenshot was not written: " + path);
            }
            return true;
        }

        private void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Terrain material PIP fixture failed", error);
            }
        }
    }

    /** Bench-only PIP host: this class is compiled from the bench source set and never enters the production JAR. */
    private static final class TerrainPipBenchScreen extends net.minecraft.client.gui.screens.Screen {
        private static final float[] NO_FLOATS = new float[0];
        private static final int[] NO_INTS = new int[0];
        private final BlockPos origin;
        private double angleDegrees = 35.0D;
        private boolean renderTerrain = true;

        private TerrainPipBenchScreen(BlockPos origin) {
            super(net.minecraft.network.chat.Component.literal("Terrain material PIP bench"));
            this.origin = origin.immutable();
        }

        private void setAngleDegrees(double angleDegrees) {
            this.angleDegrees = angleDegrees;
        }

        private void setRenderTerrain(boolean renderTerrain) {
            this.renderTerrain = renderTerrain;
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xFF080B10);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            int x = 16;
            int y = 16;
            int w = Math.max(1, width - 32);
            int h = Math.max(1, height - 32);
            ScreenRectangle viewportBounds = new ScreenRectangle(x, y, w, h);
            var viewport = new com.zhongbai233.scene_editor.core.projection.EditorViewport(
                    x, y, w, h);
            double radians = Math.toRadians(angleDegrees);
            org.joml.Vector3d focus = new org.joml.Vector3d(18.0D, 4.0D, 0.0D);
            org.joml.Vector3d camera = new org.joml.Vector3d(
                    focus.x + Math.cos(radians) * 105.0D, 58.0D,
                    focus.z + Math.sin(radians) * 105.0D);
            var cameraState = com.zhongbai233.scene_editor.core.camera.EditorCameraState
                    .lookingAt(com.zhongbai233.scene_editor.core.camera.EditorCameraMode.ORBIT,
                            camera, focus, new org.joml.Vector3d(0.0D, 1.0D, 0.0D),
                            70.0F, 1.0F, 0.05F, 512.0F);
            var cameraFrame = new com.zhongbai233.scene_editor.core.camera.CameraFrame(
                    com.zhongbai233.scene_editor.core.camera.CameraMatrices.create(
                            cameraState, viewport), viewport, cameraState.mode());
            var guiState = ((GuiGraphicsExtractorAccessor) graphics).net_music_can_play_bili$guiRenderState();
            guiState.addPicturesInPictureState(new HolographicPreviewPipRenderState(
                    null, new org.joml.Vector3f(), 1.0F, 0.0F, 0.0F, false, 70.0F, false,
                    -1, NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_FLOATS,
                    NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_INTS,
                    NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_FLOATS,
                    NO_FLOATS, NO_FLOATS, 0, 0, true, true, renderTerrain,
                    origin.getX(), origin.getY(), origin.getZ(), 56.0F, 16.0F, 56.0F,
                    renderTerrain ? TerrainPreviewManager.frame() : TerrainPreviewFrame.empty(), cameraFrame,
                    x, y, x + w, y + h, Math.min(w, h), viewportBounds));
            graphics.outline(x, y, w, h, 0xFF45E7FF);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private static final class MediaResourceConvergenceScenario implements BenchClientScenario {
        private static final int MEASURE_TICKS = 40;
        private static final BenchMetricDescriptor VIDEO_CLOSE_ACTIVE = new BenchMetricDescriptor(
                "ncpb.video.close_active", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor AUDIO_CLOSE_ACTIVE = new BenchMetricDescriptor(
                "ncpb.openal.close_active", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor AUDIO_PENDING_BATCHES = new BenchMetricDescriptor(
                "ncpb.openal.pending_delete_batches", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor HTTP_ACTIVE = new BenchMetricDescriptor(
            "ncpb.http.active_requests", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor HTTP_CANCELS = new BenchMetricDescriptor(
            "ncpb.http.cancel_requests", "count", MetricDirection.NEUTRAL);
        private int ticks;

        @Override public void setup(BenchClientContext context) { ModernTurntableVideoClient.clear(); }
        @Override public BenchClientStepResult stabilize(BenchClientContext context) {
            return context.frames().sampleCount() >= 2 ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }
        @Override public BenchClientStepResult warmup(BenchClientContext context) { return BenchClientStepResult.COMPLETE; }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            var video = VideoCloseDiagnostics.global().snapshot(System.nanoTime());
            var audio = AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime());
            context.metrics().record(VIDEO_CLOSE_ACTIVE, video.activeOperations());
            context.metrics().record(AUDIO_CLOSE_ACTIVE, audio.activeOperations());
            context.metrics().record(AUDIO_PENDING_BATCHES, OpenALSpatialAudio.pendingNativeDeleteBatches());
            var http = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
            context.metrics().record(HTTP_ACTIVE, http.activeRequests());
            context.metrics().record(HTTP_CANCELS, http.cancelRequests());
            return ++ticks >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            ConsoleConsumerLifecycleScenario.requireClean("resource convergence");
            if (VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0
                    || AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0
                    || OpenALSpatialAudio.pendingNativeDeleteBatches() != 0
                    || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0) {
                throw new AssertionError("Native close operations did not converge");
            }
            for (MemoryResourceTracker.Category category : MemoryResourceTracker.Category.values()) {
                if (MemoryResourceTracker.usage(category).currentBytes() != 0L) {
                    throw new AssertionError("Owned memory did not converge: " + category);
                }
            }
        }
    }

    private static final class ConsoleConsumerLifecycleScenario implements BenchClientScenario {
        private static final int ROUNDS = 100;
        private static final BlockPos SOURCE = new BlockPos(0, 64, 0);
        private static final BenchMetricDescriptor CONSUMERS = new BenchMetricDescriptor(
                "ncpb.console.consumers", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor VIDEO_INSTANCES = new BenchMetricDescriptor(
                "ncpb.video.instances", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor PENDING_REQUESTS = new BenchMetricDescriptor(
                "ncpb.video.pending_requests", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor MEMORY_BYTES = new BenchMetricDescriptor(
                "ncpb.memory.current_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

        private int round;

        @Override
        public void setup(BenchClientContext context) {
            ModernTurntableVideoClient.clear();
            requireClean("setup");
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            exerciseRound(-1);
            requireClean("warmup");
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            exerciseRound(round);
            record(context);
            return ++round >= ROUNDS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (round != ROUNDS) {
                throw new AssertionError("Expected " + ROUNDS + " lifecycle rounds, got " + round);
            }
            requireClean("verify");
        }

        @Override
        public void teardown(BenchClientContext context) {
            ModernTurntableVideoClient.clear();
        }

        private static void exerciseRound(int index) {
            BlockPos first = new BlockPos(index * 2 + 1, 64, 1);
            BlockPos second = new BlockPos(index * 2 + 2, 64, 1);
            ModernTurntableVideoClient.registerControlConsoleConsumer(SOURCE, first, 116);
            ModernTurntableVideoClient.registerControlConsoleConsumer(SOURCE, second, 116);
            assertConsumers(2, "both consumers attached");
            ModernTurntableVideoClient.unregisterControlConsoleConsumer(first);
            assertConsumers(1, "shared consumer remains");
            ModernTurntableVideoClient.unregisterControlConsoleConsumer(second);
            assertConsumers(0, "last consumer detached");
        }

        private static void record(BenchClientContext context) {
            ModernTurntableVideoClient.VideoLifecycleDiagnostics lifecycle =
                    ModernTurntableVideoClient.videoLifecycleDiagnostics();
            context.metrics().record(CONSUMERS, lifecycle.controlConsoleConsumers());
            context.metrics().record(VIDEO_INSTANCES, lifecycle.resources().instances());
            context.metrics().record(PENDING_REQUESTS, lifecycle.pendingRequests());
            for (MemoryResourceTracker.Category category : MemoryResourceTracker.Category.values()) {
                context.metrics().record(MEMORY_BYTES, MemoryResourceTracker.usage(category).currentBytes());
            }
        }

        private static void requireClean(String phase) {
            ModernTurntableVideoClient.VideoLifecycleDiagnostics lifecycle =
                    ModernTurntableVideoClient.videoLifecycleDiagnostics();
            if (lifecycle.controlConsoleConsumers() != 0 || lifecycle.activeRequests() != 0
                    || lifecycle.pendingRequests() != 0 || lifecycle.resources().instances() != 0
                    || lifecycle.resources().pendingLoading() != 0 || lifecycle.resources().pendingFailure() != 0) {
                throw new AssertionError("Video lifecycle not clean during " + phase + ": " + lifecycle);
            }
        }

        private static void assertConsumers(int expected, String phase) {
            int actual = ModernTurntableVideoClient.videoLifecycleDiagnostics().controlConsoleConsumers();
            if (actual != expected) {
                throw new AssertionError(phase + ": expected " + expected + " consumers, got " + actual);
            }
        }
    }
}
