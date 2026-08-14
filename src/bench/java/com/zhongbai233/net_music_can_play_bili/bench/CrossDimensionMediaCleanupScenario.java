package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.bench.api.neoforge.client.BenchGuiSession;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackSessions;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class CrossDimensionMediaCleanupScenario implements BenchClientScenario {
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
