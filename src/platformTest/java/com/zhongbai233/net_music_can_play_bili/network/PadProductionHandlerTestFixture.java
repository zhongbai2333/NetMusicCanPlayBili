package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.*;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Pad item/document/intent state; doubles are server transport, inventory lookup and playback output. */
final class PadProductionHandlerTestFixture implements AutoCloseable {
    final UUID owner = UUID.randomUUID(), device = UUID.randomUUID();
    final UUID firstPoint = UUID.randomUUID(), secondPoint = UUID.randomUUID();
    final MinecraftServer server = mock(MinecraftServer.class);
    final ServerLevel level = mock(ServerLevel.class);
    final ServerPlayer player = mock(ServerPlayer.class);
    final PlayerList players = mock(PlayerList.class);
    final Inventory inventory = mock(Inventory.class);
    final IPayloadContext context = mock(IPayloadContext.class);
    final PadDocumentSavedData saved = new PadDocumentSavedData();
    final List<AutoCloseable> mocks = new ArrayList<>();
    final List<Runnable> tasks = new ArrayList<>();
    final List<ItemMusicCD.SongInfo> requested = new ArrayList<>();
    final List<CompletableFuture<ItemMusicCD.SongInfo>> futures = new ArrayList<>();
    final List<ServerMediaPlayback.StartRequest> starts = new ArrayList<>();
    final List<UUID> stops = new ArrayList<>();
    final List<PadStateMirrorPacket> mirrors = new ArrayList<>();
    ItemStack pad;
    MockedStatic<BiliWhitelistManager> whitelist;
    MockedStatic<ServerMediaPlayback> playback;

    PadProductionHandlerTestFixture() {
        PlatformBootstrap.initialize();
        PadDocumentStore.clearRuntime(); PadDeviceHolderTracker.clear(); PadStatePacket.clearClientSyncs();
        NetworkRateLimiter.removePlayer(owner);
        when(player.getUUID()).thenReturn(owner);
        when(player.getId()).thenReturn(71);
        when(player.level()).thenReturn(level);
        when(player.getInventory()).thenReturn(inventory);
        when(context.player()).thenReturn(player);
        when(level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(owner)).thenReturn(player);
        when(server.getAllLevels()).thenReturn(List.of(level));
        when(level.players()).thenReturn(List.of(player));
        when(server.getTickCount()).thenReturn(5);
        SavedDataStorage storage = mock(SavedDataStorage.class);
        when(level.getDataStorage()).thenReturn(storage);
        when(storage.computeIfAbsent(PadDocumentSavedData.TYPE)).thenReturn(saved);
        doAnswer(invocation -> { tasks.add(invocation.getArgument(0)); return null; })
                .when(server).execute(any(Runnable.class));
        pad = copy(false);
        carrier(pad, ItemStack.EMPTY);
        var packets = own(mockStatic(PacketDistributor.class));
        packets.when(() -> PacketDistributor.sendToPlayer(eq(player), any(PadStateMirrorPacket.class)))
                .thenAnswer(invocation -> { mirrors.add(invocation.getArgument(1)); return null; });
    }

    void enableResolver() {
        whitelist = own(mockStatic(BiliWhitelistManager.class));
        whitelist.when(() -> BiliWhitelistManager.isPlaybackAllowed(nullable(ServerPlayer.class),
                anyString(), anyString())).thenCallRealMethod();
        whitelist.when(() -> BiliWhitelistManager.acceptsServer(server)).thenReturn(true);
        whitelist.when(() -> BiliWhitelistManager.playbackIdentity(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        whitelist.when(() -> BiliWhitelistManager.resolveSong(any())).thenAnswer(invocation -> {
            requested.add(invocation.getArgument(0));
            var future = new CompletableFuture<ItemMusicCD.SongInfo>(); futures.add(future); return future;
        });
        playback = own(mockStatic(ServerMediaPlayback.class));
        playback.when(() -> ServerMediaPlayback.resolvedTrack(any(), any())).thenCallRealMethod();
        playback.when(() -> ServerMediaPlayback.start(eq(player), any(ServerMediaPlayback.StartRequest.class)))
                .thenAnswer(invocation -> { starts.add(invocation.getArgument(1)); return null; });
        playback.when(() -> ServerMediaPlayback.stopAndBroadcast(eq(player), any(UUID.class)))
                .thenAnswer(invocation -> { stops.add(invocation.getArgument(1)); return null; });
    }

    ItemStack copy(boolean locked) {
        ItemStack stack = PlatformBootstrap.pad();
        PadItem.writeDeviceId(stack, device);
        PadItem.writeDocument(stack, document("initial", 100, 1, locked));
        return stack;
    }
    PadDocument document(String title, long time, long sequence, boolean locked) {
        return new PadDocument(title, "author", locked, time, sequence, PadMapSettings.DEFAULT,
                List.of(new PadMediaEntry(5, PlatformBootstrap.disc("https://example.test/five.mp3", "Five", 60)),
                        new PadMediaEntry(9, PlatformBootstrap.disc("https://example.test/nine.mp4", "Nine", 120))),
                List.of(new PadTriggerPoint(firstPoint, "Nine point", 0, 0, 0, 8, 9,
                                PadTriggerMode.ENTER_RADIUS, true, 450, true),
                        new PadTriggerPoint(secondPoint, "Five point", 5, 0, 0, 8, 5,
                                PadTriggerMode.MANUAL, false, 800, true)));
    }
    void carrier(ItemStack main, ItemStack other) {
        when(player.getMainHandItem()).thenReturn(main);
        when(player.getOffhandItem()).thenReturn(other);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(main);
        when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(other);
        when(inventory.getContainerSize()).thenReturn(2);
        when(inventory.getItem(0)).thenReturn(main);
        when(inventory.getItem(1)).thenReturn(other);
    }
    void control(PadPlaybackControlPacket.Action action, UUID point, long elapsed) {
        PadPlaybackControlPacket.handle(new PadPlaybackControlPacket(action, device, point, elapsed), context);
    }
    void complete(int index, String url, int duration) {
        futures.get(index).complete(new ItemMusicCD.SongInfo(url, "Resolved " + index, duration, false));
        drain();
    }
    void drain() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
    <T extends AutoCloseable> T own(T mock) { mocks.add(mock); return mock; }
    @Override public void close() throws Exception {
        PadDocumentStore.clearRuntime(); PadDeviceHolderTracker.clear(); PadStatePacket.clearClientSyncs();
        NetworkRateLimiter.removePlayer(owner);
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
    }
}
