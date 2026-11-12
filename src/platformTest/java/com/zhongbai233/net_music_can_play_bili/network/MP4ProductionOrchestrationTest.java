package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackEndReason;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Calls the production manager, registry, recovery, persistence and broadcaster together.
 * Mocks only server/carrier lookup, resolver transport and packet delivery; no game is started. */
class MP4ProductionOrchestrationTest {
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DEVICE = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private final List<Runnable> serverTasks = new ArrayList<>();
    private final List<CompletableFuture<ItemMusicCD.SongInfo>> resolutions = new ArrayList<>();
    private final List<String> effects = new ArrayList<>();
    private final List<MP4PlaybackSyncPacket> sent = new ArrayList<>();
    private final List<MP4PlaybackTimelinePacket> timelines = new ArrayList<>();
    private ServerPlayer actor;
    private MinecraftServer server;
    private ServerLevel level;
    private PlayerList players;
    private ItemStack stack;
    private MP4PlaybackSavedData saved;
    private MockedStatic<PacketDistributor> packets;
    private MockedStatic<MP4DeviceLocationIndex> locations;
    private MockedStatic<BiliWhitelistManager> whitelist;
    private MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions;

    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }

    @BeforeEach void setUp() throws Exception {
        sessions = field("SESSION_REGISTRY", MP4PlaybackSourceSessionRegistry.class);
        resetRuntime();
        server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);
        actor = mock(ServerPlayer.class);
        players = mock(PlayerList.class);
        when(actor.getUUID()).thenReturn(OWNER);
        when(actor.level()).thenReturn(level);
        when(actor.blockPosition()).thenReturn(BlockPos.ZERO);
        when(level.getServer()).thenReturn(server);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.players()).thenReturn(List.of());
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(OWNER)).thenReturn(actor);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        when(server.getAllLevels()).thenReturn(List.of(level));
        when(server.getTickCount()).thenReturn(1); // avoids unrelated discovery/holder scans
        // Successful resolver callbacks can be the first audience lookup in this JVM.
        var mods = own(mockStatic(ModList.class));
        mods.when(ModList::get).thenReturn(mock(ModList.class));
        doAnswer(invocation -> { serverTasks.add(invocation.getArgument(0)); return null; })
                .when(server).execute(any(Runnable.class));
        saved = spy(new MP4PlaybackSavedData());
        doAnswer(invocation -> { effects.add("device:" + ((MP4DeviceStateStore.DeviceEntry) invocation.getArgument(1)).state().selectedQueueIndex());
            return invocation.callRealMethod(); }).when(saved).putDevice(any(), any());
        doAnswer(invocation -> { effects.add("progress:" + ((MP4PlaybackSavedData.Entry) invocation.getArgument(1)).queueIndex());
            return invocation.callRealMethod(); }).when(saved).put(any(), any());
        var data = own(mockStatic(MP4PlaybackSavedData.class, CALLS_REAL_METHODS));
        data.when(() -> MP4PlaybackSavedData.get(level)).thenReturn(saved);
        var hooks = own(mockStatic(ServerLifecycleHooks.class));
        hooks.when(ServerLifecycleHooks::getCurrentServer).thenReturn(server);
        packets = own(mockStatic(PacketDistributor.class));
        packets.when(() -> PacketDistributor.sendToPlayer(eq(actor), any(MP4PlaybackSyncPacket.class)))
                .thenAnswer(invocation -> { effects.add("broadcast"); sent.add(invocation.getArgument(1)); return null; });
        // Ordinary MP4 playback publishes to the nearby audience, including player sources.
        // Stop also sends directly to the owner; keep the existing stop-effect trace unambiguous.
        packets.when(() -> PacketDistributor.sendToPlayersNear(eq(level), isNull(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(MP4PlaybackSyncPacket.class)))
                .thenAnswer(invocation -> {
                    MP4PlaybackSyncPacket packet = invocation.getArgument(6);
                    if (packet.playing()) { effects.add("broadcast"); sent.add(packet); }
                    return null;
                });
        packets.when(() -> PacketDistributor.sendToPlayersNear(eq(level), isNull(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(MP4PlaybackTimelinePacket.class)))
                .thenAnswer(invocation -> { timelines.add(invocation.getArgument(6)); return null; });
        locations = own(mockStatic(MP4DeviceLocationIndex.class));
        whitelist = own(mockStatic(BiliWhitelistManager.class));
        whitelist.when(() -> BiliWhitelistManager.isPlaybackAllowed(nullable(ServerPlayer.class),
                anyString(), anyString())).thenCallRealMethod();
        whitelist.when(() -> BiliWhitelistManager.isPlaybackAllowed(nullable(ServerLevel.class),
                anyString(), nullable(ServerPlayer.class), anyString())).thenCallRealMethod();
        whitelist.when(() -> BiliWhitelistManager.acceptsServer(server)).thenReturn(true);
        whitelist.when(() -> BiliWhitelistManager.playbackIdentity(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        whitelist.when(() -> BiliWhitelistManager.resolveSong(any())).thenAnswer(invocation -> {
            effects.add("resolve:" + ((ItemMusicCD.SongInfo) invocation.getArgument(0)).songUrl);
            var future = new CompletableFuture<ItemMusicCD.SongInfo>(); resolutions.add(future); return future;
        });
        stack = PlatformBootstrap.mp4();
        MP4Item.writeDeviceId(stack, DEVICE);
        carrier(stack);
        seedQueue(3, 0, 0);
        clearEffects();
    }

    @AfterEach void tearDown() throws Exception {
        resetRuntime();
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
    }

    @Test void unknownDurationEofAdvancesExactlyOnceAndPersistsBeforeResolver() {
        var session = seedSession(0);
        MP4PlaybackSyncManager.handleEnd(actor, terminal(session, PlaybackEndReason.NATURAL_EOF));
        var entry = MP4DeviceStateStore.get(DEVICE);
        assertTrue(entry.state().playing());
        assertEquals(1, entry.state().selectedQueueIndex());
        assertEquals(0L, entry.elapsedMillis());
        assertEquals(0, entry.state().progressPerMille());
        assertEquals(1, saved.get(DEVICE).orElseThrow().queueIndex());
        assertTrue(saved.get(DEVICE).orElseThrow().playing());
        assertEquals(List.of("device:1", "progress:1", "resolve:" + url(1)), effects);
        assertNull(sessions.get(DEVICE));
        assertEquals(1, resolutions.size());
        MP4PlaybackSyncManager.handleEnd(actor, terminal(session, PlaybackEndReason.NATURAL_EOF));
        MP4PlaybackSyncManager.handleEnd(actor, terminal(session, PlaybackEndReason.FAILED));
        assertEquals(1, resolutions.size());
        assertEquals(3, effects.size());
    }

    @Test void automaticFailureTraversesEachOccurrenceOnceThenStops() {
        seedQueue(4, 0, 2);
        var session = seedSession(0);
        MP4PlaybackSyncManager.beginAutomaticQueue(DEVICE, MP4DeviceStateStore.get(DEVICE).queue());
        clearEffects();
        MP4PlaybackSyncManager.handleEnd(actor, terminal(session, PlaybackEndReason.FAILED));
        for (int index = 1; index < 4; index++) {
            assertEquals(index, MP4DeviceStateStore.get(DEVICE).state().selectedQueueIndex());
            assertTrue(MP4DeviceStateStore.get(DEVICE).state().playing());
            assertEquals(index, resolutions.size());
            resolutions.get(index - 1).completeExceptionally(new IllegalStateException("test resolver rejection"));
            drainServerTasks();
        }
        assertEquals(3, resolutions.size());
        assertFalse(MP4DeviceStateStore.get(DEVICE).state().playing());
        assertEquals(3, MP4DeviceStateStore.get(DEVICE).state().selectedQueueIndex());
        assertFalse(saved.get(DEVICE).orElseThrow().playing());
        assertEquals(0L, saved.get(DEVICE).orElseThrow().elapsedMillis());
        assertTrue(MP4PlaybackSyncManager.mayCompletePlayback(DEVICE));
        assertNull(sessions.get(DEVICE));
        verify(actor, times(4)).sendSystemMessage(any());
    }

    @Test void serverStoppingFlushesAndClearsAuthorityBeforeLateResolveCallback() {
        var old = seedSession(0);
        MP4PlaybackSyncManager.handleEnd(actor, terminal(old, PlaybackEndReason.NATURAL_EOF));
        assertFalse(MP4PlaybackSyncManager.mayCompletePlayback(DEVICE));
        MP4PlaybackSyncManager.onServerStopping(new ServerStoppingEvent(server));
        assertTrue(sessions.isEmpty());
        assertTrue(MP4PlaybackSyncManager.mayCompletePlayback(DEVICE));
        assertEquals(MP4DeviceStateStore.DeviceEntry.EMPTY, MP4DeviceStateStore.get(DEVICE));
        whitelist.verify(() -> BiliWhitelistManager.serverClosing(server));
        clearEffects();
        resolutions.getFirst().complete(new ItemMusicCD.SongInfo(url(1), "next", 120, false));
        drainServerTasks();
        assertTrue(sessions.isEmpty());
        assertTrue(effects.isEmpty());
        verifyNoInteractions(saved);
        packets.verifyNoInteractions();
    }

    @Test void realControlRejectsDeviceAbsentFromInventory() {
        inventoryCarrier(); realItemQueue();
        var before = MP4DeviceStateStore.get(DEVICE);
        clearEffects();
        MP4PlaybackControlPacket.handle(new MP4PlaybackControlPacket(MP4PlaybackControlPacket.Action.SELECT,
                2, 650, 0L, UUID.randomUUID()), context());
        assertEquals(before, MP4DeviceStateStore.get(DEVICE));
        assertTrue(effects.isEmpty());
        assertTrue(resolutions.isEmpty());
        verifyNoInteractions(saved);
        packets.verifyNoInteractions();
    }

    @Test void realStopPersistsCurrentElapsedAndStopsTheAuthoritativeSession() {
        inventoryCarrier(); timedQueue(120); realItemQueue(); seedSession(120);
        var clock = own(mockStatic(MonotonicMediaClock.class, CALLS_REAL_METHODS));
        clock.when(MonotonicMediaClock::nowTick).thenReturn(1000L);
        clock.when(MonotonicMediaClock::nowNanos).thenReturn((1000L) * 50_000_000L);
        control(MP4PlaybackControlPacket.Action.STOP, 0, 650, 9999L);
        assertNull(sessions.get(DEVICE));
        assertTrue(MP4PlaybackSyncManager.mayCompletePlayback(DEVICE));
        var stopped = MP4DeviceStateStore.get(DEVICE);
        assertFalse(stopped.state().playing());
        assertEquals(0, stopped.state().selectedQueueIndex());
        assertEquals(50000L, stopped.elapsedMillis());
        assertEquals(417, stopped.state().progressPerMille());
        assertFalse(saved.get(DEVICE).orElseThrow().playing());
        assertEquals(50000L, saved.get(DEVICE).orElseThrow().elapsedMillis());
        assertTrue(resolutions.isEmpty());
        packets.verify(() -> PacketDistributor.sendToPlayersTrackingEntityAndSelf(eq(actor),
                argThat((MP4PlaybackSyncPacket packet) -> !packet.playing()
                        && DEVICE.equals(packet.sourceId()) && packet.queueIndex() == 0)));
    }

    @Test void stopAfterOwnerChangesDimensionPersistsInSessionLevel() {
        seedSession(120);
        ServerLevel destination = mock(ServerLevel.class);
        when(destination.getServer()).thenReturn(server);
        when(destination.dimension()).thenReturn(Level.NETHER);
        when(destination.players()).thenReturn(List.of());
        when(server.getLevel(Level.NETHER)).thenReturn(destination);
        when(actor.level()).thenReturn(destination);
        locations.when(() -> MP4DeviceLocationIndex.resolve(destination, DEVICE)).thenReturn(Optional.empty());
        var clock = own(mockStatic(MonotonicMediaClock.class, CALLS_REAL_METHODS));
        clock.when(MonotonicMediaClock::nowNanos).thenReturn(50_000_000_000L);
        assertTrue(saved.get(DEVICE).isEmpty());

        MP4PlaybackSyncManager.stop(actor, DEVICE);

        assertNull(sessions.get(DEVICE));
        var progress = saved.get(DEVICE).orElseThrow();
        assertEquals(50_000L, progress.elapsedMillis());
        assertFalse(progress.playing());
        verify(destination, never()).getDataStorage();
        packets.verify(() -> PacketDistributor.sendToPlayer(eq(actor),
                argThat((MP4PlaybackSyncPacket packet) -> !packet.playing()
                        && DEVICE.equals(packet.sourceId()))));
    }

    @Test void ninthRealControlPacketIsRateLimitedWithZeroPlaybackEffects() {
        inventoryCarrier(); realItemQueue(); seedSession(120);
        for (int i = 1; i <= 8; i++) {
            control(MP4PlaybackControlPacket.Action.VOLUME, 0, i * 10, 0L);
            assertEquals(i * 10, sessions.get(DEVICE).volumePerMille());
        }
        var active = sessions.get(DEVICE);
        var before = MP4DeviceStateStore.get(DEVICE);
        clearEffects();
        control(MP4PlaybackControlPacket.Action.VOLUME, 2, 999, 0L);
        assertSame(active, sessions.get(DEVICE));
        assertEquals(before, MP4DeviceStateStore.get(DEVICE));
        assertTrue(effects.isEmpty());
        assertTrue(timelines.isEmpty());
        verifyNoInteractions(saved);
        packets.verifyNoInteractions();
    }

    private void timedQueue(int duration) {
        var entry = MP4DeviceStateStore.get(DEVICE);
        var queue = new ArrayList<ItemStack>();
        for (int i = 0; i < 3; i++) queue.add(PlatformBootstrap.disc(url(i), "timed " + i, duration));
        MP4DeviceStateStore.update(level, DEVICE, new MP4DeviceStateStore.DeviceEntry(
                entry.state(), queue, 0L, duration, Optional.empty()));
    }
    private void control(MP4PlaybackControlPacket.Action action, int index, int volume, long target) {
        MP4PlaybackControlPacket.handle(new MP4PlaybackControlPacket(action, index, volume, target, DEVICE), context());
    }

    @Test void fifthRealRetryPacketIsRateLimitedBeforeAValidResolveCanStart() {
        inventoryCarrier(); realItemQueue();
        var active = seedSession(120);
        for (int i = 0; i < 4; i++) retry("stale-session", 5000L);
        clearEffects();
        retry(active.sessionId(), 5000L);
        assertSame(active, sessions.get(DEVICE));
        assertTrue(resolutions.isEmpty());
        assertTrue(effects.isEmpty());
        verifyNoInteractions(saved);
        packets.verifyNoInteractions();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void realRetryCompletionRejectsOfflineOwnerOrReplacedPhysicalStack(boolean offline) {
        inventoryCarrier(); realItemQueue();
        var active = seedSession(120);
        retry(active.sessionId(), 5000L);
        if (offline) when(players.getPlayer(OWNER)).thenReturn(null);
        else {
            stack = PlatformBootstrap.mp4(); MP4Item.writeDeviceId(stack, DEVICE); carrier(stack);
        }
        clearEffects();
        completeSelected(0, 0);
        assertSame(active, sessions.get(DEVICE));
        assertTrue(MP4PlaybackSyncManager.mayCompletePlayback(DEVICE));
        assertTrue(effects.isEmpty());
        verifyNoInteractions(saved);
        packets.verifyNoInteractions();
    }

    private void retry(String expectedSession, long target) {
        MP4PlaybackRetryPacket.handle(new MP4PlaybackRetryPacket(DEVICE, expectedSession, target), context());
    }

    @Test void missingServerLevelRemovesSessionAndResolveAuthorityWithoutWritingUnavailableWorld() {
        seedSession(0);
        var intent = MP4PlaybackSyncManager.beginCommandResolve(DEVICE, 2, url(2));
        when(server.getLevel(Level.OVERWORLD)).thenReturn(null);
        tick();
        assertNull(sessions.get(DEVICE));
        assertFalse(MP4PlaybackSyncManager.isCurrentResolve(DEVICE, intent));
        assertTrue(MP4PlaybackSyncManager.mayCompletePlayback(DEVICE));
        assertTrue(effects.isEmpty());
        verifyNoInteractions(saved);
        packets.verifyNoInteractions();
    }

    private void tick() { MP4PlaybackSyncManager.onServerTick(new ServerTickEvent.Post(() -> true, server)); }


    @Test void realSelectPacketOwnsOldEofFailureAndTickThenCommitsRequestedOccurrence() {
        inventoryCarrier();
        realItemQueue();
        var old = seedSession(1);
        select(2);
        assertEquals(2, MP4PlaybackSyncManager.requestedQueueIndex(DEVICE, -1));
        assertSame(old, sessions.get(DEVICE));
        assertEquals(1, resolutions.size());
        var pendingState = MP4DeviceStateStore.get(DEVICE);
        clearEffects();
        for (int i = 0; i < 2; i++) {
            MP4PlaybackEndPacket.handle(terminal(old, PlaybackEndReason.NATURAL_EOF), context());
            MP4PlaybackEndPacket.handle(terminal(old, PlaybackEndReason.FAILED), context());
            MP4PlaybackSyncManager.onServerTick(new ServerTickEvent.Post(() -> true, server));
        }
        assertSame(old, sessions.get(DEVICE));
        assertEquals(2, MP4PlaybackSyncManager.requestedQueueIndex(DEVICE, -1));
        verify(actor, never()).sendSystemMessage(any());
        assertEquals(pendingState, MP4DeviceStateStore.get(DEVICE));
        assertTrue(effects.isEmpty());
        verifyNoInteractions(saved);
        packets.verifyNoInteractions();
        completeSelected(0, 2);
        var selected = sessions.get(DEVICE);
        assertNotNull(selected);
        assertNotEquals(old.sessionId(), selected.sessionId());
        assertEquals(2, selected.queueIndex());
        assertEquals(url(2), selected.rawUrl());
        assertEquals(DEVICE, selected.sourceId());
        assertTrue(MP4DeviceStateStore.get(DEVICE).state().playing());
        assertEquals(2, MP4DeviceStateStore.get(DEVICE).state().selectedQueueIndex());
        assertEquals(0L, MP4DeviceStateStore.get(DEVICE).elapsedMillis());
        assertEquals(0, MP4DeviceStateStore.get(DEVICE).state().progressPerMille());
        assertEquals(1, sent.size());
        assertEquals(2, saved.get(DEVICE).orElseThrow().queueIndex());
        assertTrue(saved.get(DEVICE).orElseThrow().playing());
        assertTrue(effects.indexOf("progress:2") >= 0);
        assertTrue(effects.indexOf("progress:2") < effects.indexOf("broadcast"), effects.toString());
        assertEquals(1, Collections.frequency(effects, "broadcast"));
        var packet = sent.getLast();
        assertTrue(packet.playing());
        assertEquals(DEVICE, packet.sourceId());
        assertEquals(OWNER, packet.ownerId());
        assertEquals(2, packet.queueIndex());
        assertEquals(selected.sessionId(), packet.sessionId());
        assertEquals(url(2), packet.rawUrl());
        var committed = MP4DeviceStateStore.get(DEVICE);
        clearEffects();
        for (int i = 0; i < 3; i++) {
            MP4PlaybackEndPacket.handle(terminal(old, PlaybackEndReason.NATURAL_EOF), context());
            MP4PlaybackEndPacket.handle(terminal(old, PlaybackEndReason.FAILED), context());
        }
        assertSame(selected, sessions.get(DEVICE));
        assertEquals(committed, MP4DeviceStateStore.get(DEVICE));
        assertTrue(effects.isEmpty());
        verifyNoInteractions(saved);
        packets.verifyNoInteractions();
    }


    @Test void twoRealSelectPacketsRejectOutOfOrderResolverCompletion() {
        inventoryCarrier(); realItemQueue(); seedSession(0);
        select(2); select(1);
        assertEquals(2, resolutions.size());
        assertEquals(1, MP4PlaybackSyncManager.requestedQueueIndex(DEVICE, -1));
        var before = MP4DeviceStateStore.get(DEVICE);
        clearEffects();
        completeSelected(0, 2);
        assertEquals(before, MP4DeviceStateStore.get(DEVICE));
        assertTrue(effects.isEmpty());
        verifyNoInteractions(saved);
        packets.verifyNoInteractions();
        completeSelected(1, 1);
        assertEquals(1, sessions.get(DEVICE).queueIndex());
        assertEquals(url(1), sessions.get(DEVICE).rawUrl());
        assertEquals(1, MP4DeviceStateStore.get(DEVICE).state().selectedQueueIndex());
        assertEquals(1, sent.size());
        assertEquals(1, sent.getFirst().queueIndex());
    }


    @Test void realPendingPadStartRejectsOldTerminalAndTickThenCommitsThroughActualManager() {
        padCarrier(); inventoryCarrier();
        var storage = mock(net.minecraft.world.level.storage.SavedDataStorage.class);
        when(level.getDataStorage()).thenReturn(storage);
        when(storage.computeIfAbsent(PadDocumentSavedData.TYPE)).thenReturn(new PadDocumentSavedData());
        UUID point = UUID.fromString("30000000-0000-0000-0000-000000000003");
        PadItem.writeDocument(stack, new com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument(
                "pending", "owner", false, 100L, 1L,
                com.zhongbai233.net_music_can_play_bili.item.pad.PadMapSettings.DEFAULT,
                List.of(new com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry(9, PlatformBootstrap.disc(url(9), 120))),
                List.of(new com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint(point, "Nine", 0, 0, 0, 8, 9,
                        com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerMode.MANUAL, false, 650, true))));
        var old = seedSession(1);
        PadPlaybackControlPacket.handle(new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.START,
                DEVICE, point, 0L), context());
        assertTrue(PadPlaybackControlPacket.hasPending(DEVICE)); assertEquals(1, resolutions.size());
        var pendingState = MP4DeviceStateStore.get(DEVICE);
        clearEffects();
        MP4PlaybackEndPacket.handle(terminal(old, PlaybackEndReason.NATURAL_EOF), context());
        MP4PlaybackEndPacket.handle(terminal(old, PlaybackEndReason.FAILED), context());
        tick();
        assertSame(old, sessions.get(DEVICE)); assertTrue(effects.isEmpty());
        assertTrue(PadPlaybackControlPacket.hasPending(DEVICE));
        assertEquals(pendingState, MP4DeviceStateStore.get(DEVICE));
        verify(actor, never()).sendSystemMessage(any());
        verifyNoInteractions(saved); packets.verifyNoInteractions();
        completeSelected(0, 9);
        var current = sessions.get(DEVICE); assertNotNull(current);
        assertEquals(9, current.queueIndex()); assertEquals(url(9), current.rawUrl());
        assertNotEquals(old.sessionId(), current.sessionId());
        assertFalse(PadPlaybackControlPacket.hasPending(DEVICE));
        assertEquals(current.sessionId(), saved.device(DEVICE).orElseThrow().sessionId());
        assertTrue(sent.getLast().playing());
    }

    enum PlaybackReject { DIRECT_BILI, WHITELIST, VIP, EMPTY }
    @ParameterizedTest @EnumSource(PlaybackReject.class)
    void realStartRejectsUnauthorizedOrUnplayableSourceBeforeResolver(PlaybackReject rejection) throws Exception {
        inventoryCarrier();
        String raw = switch (rejection) {
            case DIRECT_BILI -> "https://example.bilivideo.com/media.mp4";
            case WHITELIST -> "BV1xx411c7mD|p=2";
            default -> url(0);
        };
        ItemStack disc = PlatformBootstrap.disc(raw, 120);
        if (rejection == PlaybackReject.VIP) {
            disc = ItemMusicCD.setSongInfo(new ItemMusicCD.SongInfo(raw, "VIP", 120, "", true, false, List.of()), disc);
            var resolver = own(mockStatic(MusicPlayResolverManager.class));
            resolver.when(() -> MusicPlayResolverManager.canResolve(any())).thenReturn(false);
        }
        replaceRealQueue(rejection == PlaybackReject.EMPTY ? List.of() : List.of(disc));
        if (rejection == PlaybackReject.VIP) assertTrue(ItemMusicCD.getSongInfo(MP4Item.readQueue(stack).getFirst()).vip);
        if (rejection == PlaybackReject.WHITELIST) {
            whitelist.when(BiliWhitelistManager::enabled).thenReturn(true);
            whitelist.when(() -> BiliWhitelistManager.canonicalResource(raw))
                    .thenReturn(Optional.of(new BiliWhitelistManager.CanonicalResource("video", "BV1xx411c7mD")));
            whitelist.when(() -> BiliWhitelistManager.isAllowed(server, raw)).thenReturn(false);
        }
        whitelist.when(() -> BiliWhitelistManager.denialMessage(eq(actor), eq(raw), anyString()))
                .thenReturn(net.minecraft.network.chat.Component.literal("controlled denial"));
        clearEffects(); control(MP4PlaybackControlPacket.Action.START, 0, 650, 0L);
        assertTrue(resolutions.isEmpty()); assertNull(sessions.get(DEVICE));
        assertFalse(MP4DeviceStateStore.get(DEVICE).state().playing());
        assertTrue(MP4PlaybackSyncManager.mayCompletePlayback(DEVICE));
        assertTrue(sent.stream().noneMatch(MP4PlaybackSyncPacket::playing));
        packets.verify(() -> PacketDistributor.sendToPlayersTrackingEntityAndSelf(eq(actor), any(MP4PlaybackSyncPacket.class)));
        var messages = org.mockito.ArgumentCaptor.forClass(net.minecraft.network.chat.Component.class);
        if (rejection == PlaybackReject.DIRECT_BILI || rejection == PlaybackReject.WHITELIST) {
            verify(actor, times(2)).sendSystemMessage(messages.capture());
            assertEquals(List.of("controlled denial", "所选条目播放失败，已停止；请重试或选择其他条目。"),
                    messages.getAllValues().stream().map(net.minecraft.network.chat.Component::getString).toList());
        } else if (rejection == PlaybackReject.VIP) {
            verify(actor).sendSystemMessage(messages.capture());
            assertEquals("所选条目播放失败，已停止；请重试或选择其他条目。", messages.getValue().getString());
        } else verify(actor, never()).sendSystemMessage(any());
    }

    @Test void fullSyncRevocationRemovesSessionWithoutPublishingPlayingPacket() {
        var initial = seedSession(120);
        var clock = own(mockStatic(MonotonicMediaClock.class, CALLS_REAL_METHODS));
        clock.when(MonotonicMediaClock::nowTick).thenReturn(1500L);
        clock.when(MonotonicMediaClock::nowNanos).thenReturn(75_000_000_000L);
        whitelist.when(BiliWhitelistManager::enabled).thenReturn(true);
        whitelist.when(() -> BiliWhitelistManager.canonicalResource(initial.rawUrl()))
                .thenReturn(Optional.of(new BiliWhitelistManager.CanonicalResource("video", "BV1xx411c7mD")));
        whitelist.when(() -> BiliWhitelistManager.isAllowed(server, initial.rawUrl())).thenReturn(true);
        whitelist.when(() -> BiliWhitelistManager.denialMessage(eq(actor), eq(initial.rawUrl()), anyString()))
                .thenReturn(net.minecraft.network.chat.Component.literal("controlled denial"));

        tick();
        assertNotNull(sessions.get(DEVICE));
        assertEquals(initial.sessionId(), sessions.get(DEVICE).sessionId());
        assertTrue(sent.getLast().playing());

        clearEffects();
        whitelist.when(() -> BiliWhitelistManager.isAllowed(server, initial.rawUrl())).thenReturn(false);
        clock.when(MonotonicMediaClock::nowTick).thenReturn(1800L);
        clock.when(MonotonicMediaClock::nowNanos).thenReturn(90_000_000_000L);
        tick();

        assertNull(sessions.get(DEVICE));
        assertTrue(MP4PlaybackSyncManager.mayCompletePlayback(DEVICE));
        assertEquals(1, sent.size());
        assertFalse(sent.getFirst().playing());
        assertEquals(DEVICE, sent.getFirst().sourceId());
        assertTrue(resolutions.isEmpty());
        assertTrue(timelines.isEmpty());
        verify(actor).sendSystemMessage(argThat(message -> "controlled denial".equals(message.getString())));
    }

    private void replaceRealQueue(List<ItemStack> queue) throws Exception {
        // Invoke the actual item serializer so callbacks see changed NBT, not a runtime-only fake queue.
        var writer = MP4Item.class.getDeclaredMethod("writeQueue", ItemStack.class, List.class);
        writer.setAccessible(true); writer.invoke(null, stack, queue);
        MP4DeviceStateStore.updateQueue(level, DEVICE, MP4Item.readQueue(stack));
    }


    @Test void sequentialQueueLastEofPersistsStoppedBeforeBroadcastAndIgnoresDuplicate() {
        seedQueue(3, 2, 0); var active = seedSession(0, 2); clearEffects();
        MP4PlaybackEndPacket.handle(terminal(active, PlaybackEndReason.NATURAL_EOF), context());
        assertEquals(List.of("device:2", "progress:2", "device:2", "broadcast"), effects);
        assertNull(sessions.get(DEVICE)); assertFalse(saved.get(DEVICE).orElseThrow().playing());
        assertTrue(resolutions.isEmpty()); verify(actor, never()).sendSystemMessage(any());
        MP4PlaybackEndPacket.handle(terminal(active, PlaybackEndReason.NATURAL_EOF), context());
        MP4PlaybackEndPacket.handle(terminal(active, PlaybackEndReason.FAILED), context());
        assertEquals(4, effects.size());
    }

    @ParameterizedTest @EnumSource(value = PlaybackEndReason.class, names = {"NATURAL_EOF", "FAILED"})
    void padTerminalPersistsBeforeStopBroadcastWithoutMp4Recovery(PlaybackEndReason reason) {
        padCarrier(); var active = seedSession(0);
        doAnswer(invocation -> { effects.add("notice"); return null; }).when(actor).sendSystemMessage(any());
        MP4PlaybackSyncManager.beginAutomaticQueue(DEVICE, MP4DeviceStateStore.get(DEVICE).queue());
        MP4PlaybackEndPacket.handle(terminal(active, reason), context());
        assertEquals(reason == PlaybackEndReason.FAILED
                ? List.of("device:0", "progress:0", "broadcast", "notice")
                : List.of("device:0", "progress:0", "broadcast"), effects);
        assertNull(sessions.get(DEVICE)); assertFalse(saved.get(DEVICE).orElseThrow().playing());
        assertEquals(0, saved.get(DEVICE).orElseThrow().queueIndex());
        assertTrue(resolutions.isEmpty()); assertFalse(PadPlaybackControlPacket.hasPending(DEVICE));
        int effectsBefore = effects.size();
        MP4PlaybackEndPacket.handle(terminal(active, reason), context());
        assertEquals(effectsBefore, effects.size());
    }

    private void padCarrier() {
        stack = PlatformBootstrap.pad(); PadItem.writeDeviceId(stack, DEVICE); carrier(stack);
        var entity = mock(ItemEntity.class); when(entity.getItem()).thenReturn(stack);
        when(level.getEntity(17)).thenReturn(entity);
    }

    private void inventoryCarrier() {
        var inventory = mock(Inventory.class);
        when(actor.getInventory()).thenReturn(inventory);
        when(actor.getMainHandItem()).thenReturn(stack);
        when(actor.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        when(inventory.getContainerSize()).thenReturn(1);
        when(inventory.getItem(0)).thenAnswer(invocation -> stack);
    }
    private void realItemQueue() {
        var entry = MP4DeviceStateStore.get(DEVICE);
        for (var disc : entry.queue()) assertTrue(MP4Item.addDisc(stack, disc.copy()));
        assertEquals(entry.queue().size(), MP4Item.readQueue(stack).size());
        MP4DeviceStateStore.update(level, DEVICE, new MP4DeviceStateStore.DeviceEntry(entry.state(),
                MP4Item.readQueue(stack), entry.elapsedMillis(), entry.durationSeconds(), entry.playbackSessionId()));
        clearEffects();
    }
    private IPayloadContext context() {
        var context = mock(IPayloadContext.class); when(context.player()).thenReturn(actor); return context;
    }
    private void select(int index) {
        MP4PlaybackControlPacket.handle(new MP4PlaybackControlPacket(MP4PlaybackControlPacket.Action.SELECT,
                index, 650, 9876L, DEVICE), context());
    }

    private void completeSelected(int future, int urlIndex) {
        resolutions.get(future).complete(new ItemMusicCD.SongInfo(url(urlIndex), "resolved", 120, false));
        drainServerTasks();
    }

    private void carrier(ItemStack carrier) {
        locations.when(() -> MP4DeviceLocationIndex.resolve(level, DEVICE)).thenReturn(Optional.of(
                new MP4DeviceLocationIndex.ResolvedLocation(carrier, ClientMediaSyncPayload.SOURCE_ITEM,
                        17, BlockPos.ZERO, -1, OWNER)));
    }
    private MP4PlaybackSyncManager.Session seedSession(int duration) {
        return seedSession(duration, 0);
    }
    private MP4PlaybackSyncManager.Session seedSession(int duration, int index) {
        var session = new MP4PlaybackSyncManager.Session(Level.OVERWORLD, OWNER, DEVICE,
                ClientMediaSyncPayload.SOURCE_ITEM, 17, BlockPos.ZERO, -1, index,
                "https://media.invalid/transport", url(index), "track", duration, 650,
                PlaybackSessionId.of("production-session"), 0, 1000, 1000, MonotonicMediaClock.running(0L, 0L));
        sessions.replace(DEVICE, session);
        return session;
    }
    private void seedQueue(int size, int index, int repeat) {
        List<ItemStack> queue = new ArrayList<>();
        for (int i = 0; i < size; i++) queue.add(PlatformBootstrap.disc(url(i), "track " + i, 0));
        MP4DeviceStateStore.update(level, DEVICE, new MP4DeviceStateStore.DeviceEntry(
                state(true, index, repeat), queue, 0L, 0, Optional.empty()));
    }
    private MP4Item.State state(boolean playing, int index, int repeat) {
        var s = MP4Item.State.DEFAULT;
        return new MP4Item.State(playing, false, s.videoEnabled(), s.landscape(), s.qualityIndex(), index,
                s.queueScrollOffset(), 650, repeat, s.playlistOpen(), s.lyricsEnabled(), s.subtitleMode(),
                s.subtitleAiEnabled(), 0, s.rotationHintShown());
    }
    private static MP4PlaybackEndPacket terminal(MP4PlaybackSyncManager.Session session, PlaybackEndReason reason) {
        return new MP4PlaybackEndPacket(DEVICE, session.sessionId(), PlaybackSync.parseTerminalToken(session.playUrl()), reason);
    }
    private void clearEffects() { effects.clear(); sent.clear(); timelines.clear(); clearInvocations(saved, actor); packets.clearInvocations(); }
    private void drainServerTasks() { while (!serverTasks.isEmpty()) serverTasks.removeFirst().run(); }
    private static String url(int i) { return "https://media.invalid/" + i; }
    private <T extends AutoCloseable> T own(T mocked) { mocks.add(mocked); return mocked; }
    private void resetRuntime() throws Exception {
        sessions.clear();
        field("RESOLVE_INTENTS", MP4ResolveIntentRegistry.class).clear();
        field("FAILURE_RECOVERY", MP4FailureRecovery.class).clear();
        field("PROGRESS_PERSISTENCE", MP4PlaybackProgressPersistence.class).clearRuntime();
        field("UNKNOWN_BUDGET", UnknownPlaybackBudget.class).clear();
        MP4DeviceStateStore.clearRuntime();
        PadPlaybackControlPacket.clearResolveIntents();
        PadDocumentStore.clearRuntime();
        PadDeviceHolderTracker.clear();
        PadStatePacket.clearClientSyncs();
        AudioLinkIndex.clear();
        NetworkRateLimiter.removePlayer(OWNER);
        MP4StatePacket.clearClientSyncs();
        // Production start also writes audit state. Keep this fixture isolated from other suites.
        Field auditField = PlaybackAuditManager.class.getDeclaredField("SOURCES");
        auditField.setAccessible(true);
        Object auditRegistry = auditField.get(null);
        Field auditEntries = auditRegistry.getClass().getDeclaredField("sources");
        auditEntries.setAccessible(true);
        ((Map<?, ?>) auditEntries.get(auditRegistry)).clear();
    }
    @SuppressWarnings("unchecked") private static <T> T field(String name, Class<T> type) throws Exception {
        Field field = MP4PlaybackSyncManager.class.getDeclaredField(name);
        field.setAccessible(true); return (T) field.get(null);
    }

}
