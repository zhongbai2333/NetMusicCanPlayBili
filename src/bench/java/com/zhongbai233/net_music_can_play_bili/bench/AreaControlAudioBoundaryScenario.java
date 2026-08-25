package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAreaAudioZoneRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.compat.areacontrol.AreaControlAudioCompat;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.network.AudioEndpointSnapshotPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Integrated proof against the real AreaControl runtime. The fixture creates a parent area, its
 * child, a separate sibling room and wildness, then moves one real player through all four zones.
 */
final class AreaControlAudioBoundaryScenario implements BenchClientScenario {
    private static final UUID PARENT_AREA_ID = UUID.fromString("ac000000-0000-0000-0000-000000000001");
    private static final UUID CHILD_AREA_ID = UUID.fromString("ac000000-0000-0000-0000-000000000002");
    private static final UUID SIBLING_AREA_ID = UUID.fromString("ac000000-0000-0000-0000-000000000003");
    private static final PlaybackSourceId SOURCE_ID = PlaybackSourceId.of(
            UUID.fromString("ac100000-0000-0000-0000-000000000001"));
    private static final UUID PARENT_ENDPOINT_ID = UUID.fromString("ac200000-0000-0000-0000-000000000001");
    private static final UUID CHILD_ENDPOINT_ID = UUID.fromString("ac200000-0000-0000-0000-000000000002");
    private static final UUID SIBLING_ENDPOINT_ID = UUID.fromString("ac200000-0000-0000-0000-000000000003");
    private static final UUID WILD_ENDPOINT_ID = UUID.fromString("ac200000-0000-0000-0000-000000000004");
    private static final UUID MOVING_SOURCE_ID = UUID.fromString("ac300000-0000-0000-0000-000000000001");

    private static final BlockPos PARENT_LISTENER = new BlockPos(68, 165, 0);
    private static final BlockPos CHILD_LISTENER = new BlockPos(84, 165, 0);
    private static final BlockPos SIBLING_LISTENER = new BlockPos(132, 165, 0);
    private static final BlockPos WILD_LISTENER = new BlockPos(164, 165, 0);
    private static final BlockPos PARENT_OUTPUT = new BlockPos(70, 165, 0);
    private static final BlockPos CHILD_OUTPUT = new BlockPos(86, 165, 0);
    private static final BlockPos SIBLING_OUTPUT = new BlockPos(134, 165, 0);
    private static final BlockPos WILD_OUTPUT = new BlockPos(166, 165, 0);
    private static final BlockPos CONSOLE_KEY = new BlockPos(80, 165, 4);
    private static final BlockPos VIRTUAL_CHILD_OUTPUT = new BlockPos(88, 165, 1);
    private static final List<BlockPos> RENDER_MARKERS = List.of(
            PARENT_LISTENER.offset(0, 0, 4), PARENT_LISTENER.offset(0, 1, 4),
            CHILD_LISTENER.offset(0, 0, 4), CHILD_LISTENER.offset(0, 1, 4),
            SIBLING_LISTENER.offset(0, 0, 4), SIBLING_LISTENER.offset(0, 1, 4),
            WILD_LISTENER.offset(0, 0, 4), WILD_LISTENER.offset(0, 1, 4));
    private static final float MAX_DISTANCE = 160.0F;
    private static final float SETTLED_HIGH = 0.995F;
    private static final float SETTLED_LOW = 0.005F;

    private static final Set<UUID> ALL_ENDPOINTS = Set.of(PARENT_ENDPOINT_ID, CHILD_ENDPOINT_ID,
            SIBLING_ENDPOINT_ID, WILD_ENDPOINT_ID);
    private static final BenchMetricDescriptor TRANSITIONS = new BenchMetricDescriptor(
            "ncpb.area_control.transitions", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor CROSS_FADES = new BenchMetricDescriptor(
            "ncpb.area_control.cross_fades", "count", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor OUTPUTS = new BenchMetricDescriptor(
            "ncpb.area_control.outputs", "count", MetricDirection.NEUTRAL);

    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean setupComplete = new AtomicBoolean();
    private final AtomicInteger requestedStage = new AtomicInteger();
    private final AtomicInteger serverStage = new AtomicInteger();
    private UUID playerId;
    private volatile Vec3 originalPosition;
    private volatile float originalYaw;
    private volatile float originalPitch;
    private volatile boolean originalNoGravity;
    private boolean clientRoutesInstalled;
    private boolean parentChildCrossFade;
    private boolean childSiblingCrossFade;
    private int phase;

    @Override
    public void setup(BenchClientContext context) {
        if (!ModList.get().isLoaded("area_control") || !AreaControlAudioCompat.active()) {
            throw new AssertionError("AreaControl local/Bench runtime dependency is not active");
        }
        ClientAudioEndpointIndex.clear();
        ClientAudioOutputRegistry.cleanup();
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new AssertionError("AreaControl audio Bench requires an integrated server");
        }
        playerId = context.player().getUUID();
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    throw new IllegalStateException("Integrated server player is unavailable");
                }
                originalPosition = player.position();
                originalYaw = player.getYRot();
                originalPitch = player.getXRot();
                originalNoGravity = player.isNoGravity();
                ReflectiveAreaFixture fixture = ReflectiveAreaFixture.open();
                fixture.removeAll(level, CHILD_AREA_ID, SIBLING_AREA_ID, PARENT_AREA_ID);
                fixture.add(level, PARENT_AREA_ID, "NCPB Bench Parent",
                        64, 150, -20, 111, 190, 20);
                Object child = fixture.add(level, CHILD_AREA_ID, "NCPB Bench Child",
                        80, 154, -8, 95, 182, 8);
                fixture.add(level, SIBLING_AREA_ID, "NCPB Bench Sibling",
                        128, 150, -20, 143, 190, 20);
                if (!PARENT_AREA_ID.equals(fixture.parentId(child))) {
                    throw new AssertionError("AreaControl did not establish the expected parent/child relation");
                }
                requireZone(level, PARENT_LISTENER, PARENT_AREA_ID);
                requireZone(level, CHILD_LISTENER, CHILD_AREA_ID);
                requireZone(level, SIBLING_LISTENER, SIBLING_AREA_ID);
                requireZone(level, WILD_LISTENER, AreaAudioZone.WILDNESS_ID);
                placeRenderMarkers(level);

                List<AudioEndpointSnapshotPacket.Endpoint> endpoints = List.of(
                        endpoint(level, PARENT_ENDPOINT_ID, PARENT_OUTPUT),
                        endpoint(level, CHILD_ENDPOINT_ID, CHILD_OUTPUT),
                        endpoint(level, SIBLING_ENDPOINT_ID, SIBLING_OUTPUT),
                        endpoint(level, WILD_ENDPOINT_ID, WILD_OUTPUT));
                PacketDistributor.sendToPlayer(player, new AudioEndpointSnapshotPacket(
                        SOURCE_ID.value(), PARENT_OUTPUT, AreaControlAudioCompat.zoneAt(level, PARENT_OUTPUT),
                        1L, true, true, endpoints, List.of()));
                teleport(player, level, PARENT_LISTENER);
                requestedStage.set(1);
                serverStage.set(1);
            } catch (Throwable error) {
                failure.compareAndSet(null, unwrap(error));
            } finally {
                setupComplete.set(true);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        throwIfFailed();
        if (!setupComplete.get() || !clientAt(context, PARENT_LISTENER)
                || !PARENT_OUTPUT.equals(ClientAudioEndpointIndex.sourcePosition(SOURCE_ID))) {
            return BenchClientStepResult.CONTINUE;
        }
        installClientRoutes();
        Sample sample = sample();
        return settled(sample, Zone.PARENT) && context.environment().readiness().ready()
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
        installClientRoutes();
        Sample sample = sample();
        switch (phase) {
            case 0 -> {
                if (!settled(sample, Zone.PARENT)) {
                    return BenchClientStepResult.CONTINUE;
                }
                assertRouting(sample, Zone.PARENT, PARENT_ENDPOINT_ID);
                requestTeleport(context, CHILD_LISTENER, CHILD_AREA_ID, 2);
                phase = 1;
            }
            case 1 -> {
                parentChildCrossFade |= naturalCrossFade(sample.parentGain(), sample.childGain());
                if (serverStage.get() < 2 || !clientAt(context, CHILD_LISTENER) || !settled(sample, Zone.CHILD)) {
                    return BenchClientStepResult.CONTINUE;
                }
                assertRouting(sample, Zone.CHILD, CHILD_ENDPOINT_ID);
                requestTeleport(context, SIBLING_LISTENER, SIBLING_AREA_ID, 3);
                phase = 2;
            }
            case 2 -> {
                childSiblingCrossFade |= naturalCrossFade(sample.childGain(), sample.siblingGain());
                if (serverStage.get() < 3 || !clientAt(context, SIBLING_LISTENER)
                        || !settled(sample, Zone.SIBLING)) {
                    return BenchClientStepResult.CONTINUE;
                }
                assertRouting(sample, Zone.SIBLING, SIBLING_ENDPOINT_ID);
                requestTeleport(context, WILD_LISTENER, AreaAudioZone.WILDNESS_ID, 4);
                phase = 3;
            }
            case 3 -> {
                if (serverStage.get() < 4 || !clientAt(context, WILD_LISTENER) || !settled(sample, Zone.WILD)) {
                    return BenchClientStepResult.CONTINUE;
                }
                assertRouting(sample, Zone.WILD, WILD_ENDPOINT_ID);
                if (!parentChildCrossFade || !childSiblingCrossFade) {
                    throw new AssertionError("Natural AreaControl cross-fade was not sampled: parentChild="
                            + parentChildCrossFade + ", childSibling=" + childSiblingCrossFade);
                }
                context.metrics().record(TRANSITIONS, 3);
                context.metrics().record(CROSS_FADES, 2);
                context.metrics().record(OUTPUTS, 6);
                phase = 4;
                return BenchClientStepResult.COMPLETE;
            }
            default -> {
                return BenchClientStepResult.COMPLETE;
            }
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        if (phase != 4 || !parentChildCrossFade || !childSiblingCrossFade) {
            throw new AssertionError("AreaControl audio boundary Bench did not converge: phase=" + phase
                    + ", parentChild=" + parentChildCrossFade + ", childSibling=" + childSiblingCrossFade);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        ClientAudioEndpointIndex.clear();
        ClientAreaAudioZoneRegistry.clearConsoleOutputs(CONSOLE_KEY);
        ClientAreaAudioZoneRegistry.removeMovingZone(MOVING_SOURCE_ID);
        ClientAudioOutputRegistry.cleanup();
        var server = context.minecraft().getSingleplayerServer();
        if (server == null || playerId == null) {
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() instanceof ServerLevel level) {
                    if (originalPosition != null) {
                        player.teleportTo(level, originalPosition.x, originalPosition.y, originalPosition.z,
                                Set.<Relative>of(), originalYaw, originalPitch, true);
                    }
                    player.setNoGravity(originalNoGravity);
                    removeRenderMarkers(level);
                    ReflectiveAreaFixture.open().removeAll(level,
                            CHILD_AREA_ID, SIBLING_AREA_ID, PARENT_AREA_ID);
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, unwrap(error));
            }
        });
    }

    private void installClientRoutes() {
        if (clientRoutesInstalled) {
            return;
        }
        ClientAreaAudioZoneRegistry.replaceConsoleOutputs(CONSOLE_KEY,
                Map.of(VIRTUAL_CHILD_OUTPUT, AreaAudioZone.isolated(CHILD_AREA_ID)));
        ClientAreaAudioZoneRegistry.setMovingZone(MOVING_SOURCE_ID, AreaAudioZone.isolated(SIBLING_AREA_ID));
        clientRoutesInstalled = true;
    }

    private void requestTeleport(BenchClientContext context, BlockPos target, UUID expectedZone, int stage) {
        if (!requestedStage.compareAndSet(stage - 1, stage)) {
            return;
        }
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new AssertionError("Integrated server disappeared during AreaControl transition");
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    throw new IllegalStateException("Integrated server player is unavailable");
                }
                teleport(player, level, target);
                requireZone(level, target, expectedZone);
                serverStage.set(stage);
            } catch (Throwable error) {
                failure.compareAndSet(null, unwrap(error));
            }
        });
    }

    private static AudioEndpointSnapshotPacket.Endpoint endpoint(ServerLevel level, UUID id, BlockPos pos) {
        return new AudioEndpointSnapshotPacket.Endpoint(id, pos, 0, 1.0F, false, MAX_DISTANCE, 1L,
                AreaControlAudioCompat.zoneAt(level, pos));
    }

    private static void teleport(ServerPlayer player, ServerLevel level, BlockPos target) {
        player.setNoGravity(true);
        if (!player.teleportTo(level, target.getX() + 0.5D, target.getY() + 0.25D, target.getZ() + 0.5D,
                Set.<Relative>of(), 0.0F, 0.0F, true)) {
            throw new IllegalStateException("Could not teleport AreaControl Bench player to " + target);
        }
    }

    private static void requireZone(ServerLevel level, BlockPos pos, UUID expected) {
        AreaAudioZone actual = AreaControlAudioCompat.zoneAt(level, pos);
        if (!actual.isolated() || !expected.equals(actual.areaId())) {
            throw new AssertionError("Unexpected AreaControl zone at " + pos + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void placeRenderMarkers(ServerLevel level) {
        for (BlockPos marker : RENDER_MARKERS) {
            if (!level.isEmptyBlock(marker)) {
                throw new AssertionError("AreaControl Bench render marker would overwrite " + marker);
            }
            level.setBlockAndUpdate(marker, Blocks.GLASS.defaultBlockState());
        }
    }

    private static void removeRenderMarkers(ServerLevel level) {
        for (BlockPos marker : RENDER_MARKERS) {
            if (level.getBlockState(marker).is(Blocks.GLASS)) {
                level.setBlockAndUpdate(marker, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static boolean clientAt(BenchClientContext context, BlockPos target) {
        Vec3 position = context.player().position();
        return Math.abs(position.x - (target.getX() + 0.5D)) < 0.25D
                && Math.abs(position.y - (target.getY() + 0.25D)) < 0.25D
                && Math.abs(position.z - (target.getZ() + 0.5D)) < 0.25D;
    }

    private static Sample sample() {
        long now = System.nanoTime();
        return new Sample(
                ClientAreaAudioZoneRegistry.gain(PARENT_OUTPUT, now),
                ClientAreaAudioZoneRegistry.gain(CHILD_OUTPUT, now),
                ClientAreaAudioZoneRegistry.gain(SIBLING_OUTPUT, now),
                ClientAreaAudioZoneRegistry.gain(WILD_OUTPUT, now),
                ClientAreaAudioZoneRegistry.gain(VIRTUAL_CHILD_OUTPUT, now),
                ClientAreaAudioZoneRegistry.gain(MOVING_SOURCE_ID, now),
                ClientAudioEndpointIndex.audibleDemands(SOURCE_ID),
                ClientAudioEndpointIndex.geometricDemands(SOURCE_ID));
    }

    private static boolean settled(Sample sample, Zone expected) {
        float[] gains = { sample.parentGain(), sample.childGain(), sample.siblingGain(), sample.wildGain() };
        for (int index = 0; index < gains.length; index++) {
            if (index == expected.ordinal()) {
                if (gains[index] < SETTLED_HIGH) {
                    return false;
                }
            } else if (gains[index] > SETTLED_LOW) {
                return false;
            }
        }
        return true;
    }

    private static boolean naturalCrossFade(float fadingOut, float fadingIn) {
        return fadingOut > 0.05F && fadingOut < 0.95F && fadingIn > 0.05F && fadingIn < 0.95F;
    }

    private static void assertRouting(Sample sample, Zone zone, UUID endpoint) {
        if (!sample.audible().equals(Set.of(endpoint))) {
            throw new AssertionError("AreaControl selected the wrong audible endpoint for " + zone
                    + ": " + sample.audible() + ", gains=" + sample);
        }
        if (!sample.geometric().equals(ALL_ENDPOINTS)) {
            throw new AssertionError("Acoustic walls incorrectly retired geometric decode demand: "
                    + sample.geometric());
        }
        boolean virtualExpected = zone == Zone.CHILD;
        boolean movingExpected = zone == Zone.SIBLING;
        if ((sample.virtualGain() >= SETTLED_HIGH) != virtualExpected
                || (sample.virtualGain() <= SETTLED_LOW) == virtualExpected
                || (sample.movingGain() >= SETTLED_HIGH) != movingExpected
                || (sample.movingGain() <= SETTLED_LOW) == movingExpected) {
            throw new AssertionError("Virtual/moving outputs did not follow their actual areas for " + zone
                    + ": " + sample);
        }
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("AreaControl audio boundary fixture failed", error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return error;
    }

    private enum Zone {
        PARENT, CHILD, SIBLING, WILD
    }

    private record Sample(float parentGain, float childGain, float siblingGain, float wildGain,
            float virtualGain, float movingGain, Set<UUID> audible, Set<UUID> geometric) {
    }

    /** AreaControl is runtime-only, so the Bench fixture deliberately uses its public runtime surface by reflection. */
    private record ReflectiveAreaFixture(Class<?> areaClass, Object manager, Method findBy,
            Method add, Method remove, Method parentId, Field uid, Field name,
            Field minX, Field minY, Field minZ, Field maxX, Field maxY, Field maxZ) {
        static ReflectiveAreaFixture open() throws ReflectiveOperationException {
            Class<?> areaClass = Class.forName("org.teacon.areacontrol.api.Area");
            Class<?> managerClass = Class.forName("org.teacon.areacontrol.AreaManager");
            Object manager = managerClass.getField("INSTANCE").get(null);
            return new ReflectiveAreaFixture(areaClass, manager,
                    managerClass.getMethod("findBy", UUID.class),
                    managerClass.getMethod("add", areaClass, ResourceKey.class, ServerPlayer.class),
                    managerClass.getMethod("remove", areaClass, ResourceKey.class),
                    areaClass.getMethod("getBelongingArea"),
                    areaClass.getField("uid"), areaClass.getField("name"),
                    areaClass.getField("minX"), areaClass.getField("minY"), areaClass.getField("minZ"),
                    areaClass.getField("maxX"), areaClass.getField("maxY"), areaClass.getField("maxZ"));
        }

        Object add(ServerLevel level, UUID areaId, String areaName,
                int x1, int y1, int z1, int x2, int y2, int z2) throws ReflectiveOperationException {
            Object area = areaClass.getConstructor().newInstance();
            uid.set(area, areaId);
            name.set(area, areaName);
            minX.setInt(area, x1);
            minY.setInt(area, y1);
            minZ.setInt(area, z1);
            maxX.setInt(area, x2);
            maxY.setInt(area, y2);
            maxZ.setInt(area, z2);
            if (!Boolean.TRUE.equals(add.invoke(manager, area, level.dimension(), null))) {
                throw new AssertionError("AreaControl rejected Bench fixture area " + areaId);
            }
            return area;
        }

        UUID parentId(Object area) throws ReflectiveOperationException {
            return (UUID) parentId.invoke(area);
        }

        void removeAll(ServerLevel level, UUID... ids) throws ReflectiveOperationException {
            for (UUID id : ids) {
                Object existing = findBy.invoke(manager, id);
                if (existing != null) {
                    remove.invoke(manager, existing, level.dimension());
                }
            }
        }
    }
}
