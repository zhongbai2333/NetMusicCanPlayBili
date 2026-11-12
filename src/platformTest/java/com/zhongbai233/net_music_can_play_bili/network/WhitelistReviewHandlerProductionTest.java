package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.server.*;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaIoExecutor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Whitelist review/preview server handlers. Each nested class keeps the exact fixture of its original file; the two
 * fixtures static-mock overlapping classes, so they cannot share one setup.
 */
class WhitelistReviewHandlerProductionTest {
    /** Real review handler, manager, JSON and acknowledgements; only actors, permission decision and delivery are mocks. */
    @Nested class Review {
        private static final String URL="https://example.test/review.mp4";
        @TempDir Path world;
        MinecraftServer server; ServerPlayer player; IPayloadContext context; CommandSourceStack source;
        MockedStatic<NetMusicBiliServerCommands> permissions; MockedStatic<PacketDistributor> delivery;
        List<CustomPacketPayload> sent=new ArrayList<>(); BiliWhitelistManager.Entry entry;
        @BeforeEach void setup() throws Exception {
            PlatformBootstrap.initialize(); server=mock(MinecraftServer.class); player=mock(ServerPlayer.class);
            var level=mock(ServerLevel.class); context=mock(IPayloadContext.class); source=mock(CommandSourceStack.class);
            when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
            when(player.getUUID()).thenReturn(UUID.randomUUID()); when(player.level()).thenReturn(level);
            when(level.getServer()).thenReturn(server); when(player.getDisplayName()).thenReturn(Component.literal("Reviewer"));
            when(player.createCommandSourceStack()).thenReturn(source); when(context.player()).thenReturn(player);
            permissions=mockStatic(NetMusicBiliServerCommands.class);
            permissions.when(()->NetMusicBiliServerCommands.canManageWhitelist(source)).thenReturn(true);
            delivery=mockStatic(PacketDistributor.class);
            delivery.when(()->PacketDistributor.sendToPlayer(eq(player),any(CustomPacketPayload.class)))
                    .thenAnswer(invocation->{sent.add(invocation.getArgument(1)); return null;});
            clearCompleted(); WhitelistReviewActionPacket.clearPendingShortMutations(); NetworkRateLimiter.removePlayer(player.getUUID());
            entry=BiliWhitelistManager.add(server,URL,player).entry();
        }
        @AfterEach void cleanup() throws Exception {
            delivery.close(); permissions.close(); NetworkRateLimiter.removePlayer(player.getUUID()); clearCompleted();
            WhitelistReviewActionPacket.clearPendingShortMutations();
        }
        void clearCompleted() throws Exception {
            var field=WhitelistReviewActionPacket.class.getDeclaredField("COMPLETED_MUTATIONS"); field.setAccessible(true);
            ((Map<?,?>)field.get(null)).clear();
        }
        WhitelistReviewActionPacket packet(WhitelistReviewActionPacket.Action action,String text,String version,long request) {
            return new WhitelistReviewActionPacket(action,URL,0,text,version,0,0,request);
        }
        void handle(WhitelistReviewActionPacket payload) { WhitelistReviewActionPacket.handle(payload,context); }
        List<WhitelistReviewMutationResultPacket> results() {
            return sent.stream().filter(WhitelistReviewMutationResultPacket.class::isInstance)
                    .map(WhitelistReviewMutationResultPacket.class::cast).toList();
        }
        @Test void nonServerPlayerHasNoMutationOrDelivery() {
            when(context.player()).thenReturn(null); handle(packet(WhitelistReviewActionPacket.Action.REMOVE,"reason",entry.addedAt,1));
            assertEquals(1,BiliWhitelistManager.entryCount(server)); assertTrue(sent.isEmpty());
        }
        @Test void revokedPermissionDeniesActualMutationWithCorrelatedAck() {
            permissions.when(()->NetMusicBiliServerCommands.canManageWhitelist(source)).thenReturn(false);
            handle(packet(WhitelistReviewActionPacket.Action.REMOVE,"reason",entry.addedAt,23));
            assertEquals(1,BiliWhitelistManager.entryCount(server)); assertEquals(0,BiliWhitelistManager.removalRecordCount(server));
            assertEquals(WhitelistReviewMutationResultPacket.Status.DENIED,results().getFirst().status());
            assertEquals(23,results().getFirst().requestId()); assertEquals(1,sent.size());
        }
        @Test void reusedRequestIdWithDifferentFingerprintCannotAddSecondComment() {
            handle(packet(WhitelistReviewActionPacket.Action.COMMENT,"first",entry.addedAt,33));
            handle(packet(WhitelistReviewActionPacket.Action.COMMENT,"changed",entry.addedAt,33));
            assertEquals(1,BiliWhitelistManager.entries(server).getFirst().comments.size());
            assertEquals(WhitelistReviewMutationResultPacket.Status.INVALID,results().getLast().status());
        }
        @Test void fourthMutationInSameRealWindowIsRateLimitedWithNoFourthWrite() {
            // Warm the actual mutation/packet classes before starting the timed window.
            handle(packet(WhitelistReviewActionPacket.Action.COMMENT,"warmup",entry.addedAt,50));
            NetworkRateLimiter.removePlayer(player.getUUID()); sent.clear();
            for(int i=0;i<4;i++) handle(packet(WhitelistReviewActionPacket.Action.COMMENT,"comment"+i,entry.addedAt,60+i));
            assertEquals(4,BiliWhitelistManager.entries(server).getFirst().comments.size());
            assertEquals(WhitelistReviewMutationResultPacket.Status.RATE_LIMITED,results().getLast().status());
            assertEquals(4,results().size());
        }
        @Test void saveFailureReturnsSaveFailedWithoutPersistingComment() throws Exception {
            Path path=world.resolve("net_music_can_play_bili/net_music_can_play_bili_link_whitelist.json");
            java.nio.file.Files.delete(path); java.nio.file.Files.createDirectory(path);
            handle(packet(WhitelistReviewActionPacket.Action.COMMENT,"new",entry.addedAt,80));
            assertEquals(WhitelistReviewMutationResultPacket.Status.SAVE_FAILED,results().getFirst().status());
            assertTrue(BiliWhitelistManager.entries(server).getFirst().comments.isEmpty());
        }
        private static final String SHORT="https://b23.tv/safe-fixture";
        class PendingShortFixture implements AutoCloseable {
            final AtomicBoolean ready=new AtomicBoolean(false);
            final CompletableFuture<Optional<BiliWhitelistManager.CanonicalResource>> future=new CompletableFuture<>();
            final List<Runnable> callbacks=new ArrayList<>();
            final List<Throwable> fixtureErrors=new ArrayList<>();
            // Delegate every internal manager helper to production; intercept only the controlled resolver boundary.
            // A default static mock also intercepts private helpers (e.g. normalizeAuditText), which corrupts the fixture.
            final MockedStatic<BiliWhitelistManager> manager=mockStatic(BiliWhitelistManager.class,invocation->{
                String method=invocation.getMethod().getName();
                if(invocation.getArguments().length>0 && SHORT.equals(invocation.getArgument(0))) {
                    if(method.equals("canonicalReady")) return ready.get();
                    if(method.equals("canonicalResourceAsync")) return future;
                    if(method.equals("canonicalResource")) return Optional.of(new BiliWhitelistManager.CanonicalResource("url",URL));
                }
                return invocation.callRealMethod();
            });
            final net.minecraft.server.players.PlayerList players=mock(net.minecraft.server.players.PlayerList.class);
            PendingShortFixture() {
                when(server.getPlayerList()).thenReturn(players); when(players.getPlayer(player.getUUID())).thenReturn(player);
                doAnswer(invocation->{callbacks.add(invocation.getArgument(0));return null;}).when(server).execute(any(Runnable.class));
                manager.when(()->BiliWhitelistManager.canonicalReady(SHORT)).thenAnswer(invocation->ready.get());
                manager.when(()->BiliWhitelistManager.canonicalResourceAsync(SHORT)).thenReturn(future);
                manager.when(()->BiliWhitelistManager.acceptsServer(server)).thenReturn(true);
                manager.when(()->BiliWhitelistManager.canonicalResource(SHORT))
                        .thenReturn(Optional.of(new BiliWhitelistManager.CanonicalResource("url",URL)));
                manager.when(()->BiliWhitelistManager.addComment(eq(server),eq(SHORT),eq(player),anyString(),anyString()))
                        .thenAnswer(invocation->{try{return invocation.callRealMethod();}catch(Throwable error){fixtureErrors.add(error);throw error;}});
                manager.when(()->BiliWhitelistManager.reviewSnapshot(eq(server),anyInt(),anyInt(),anyInt(),anyInt())).thenCallRealMethod();
            }
            WhitelistReviewActionPacket payload(String text,long request) {
                return new WhitelistReviewActionPacket(WhitelistReviewActionPacket.Action.COMMENT,SHORT,0,text,entry.addedAt,0,0,request);
            }
            void complete() {
                ready.set(true); future.complete(Optional.of(new BiliWhitelistManager.CanonicalResource("url",URL)));
                while(!callbacks.isEmpty()) callbacks.removeFirst().run();
                if(!fixtureErrors.isEmpty()) throw new AssertionError("Controlled resolver callback failed",fixtureErrors.getFirst());
            }
            @Override public void close() { manager.close(); }
        }
        @ParameterizedTest @ValueSource(strings={"permission_revoked","player_disconnected","server_stopped"})
        void lateShortLinkResolutionCannotMutateAfterAuthorityOrLifecycleChanges(String change) {
            try(var pending=new PendingShortFixture()) {
                handle(pending.payload("late",202));
                switch(change) {
                    case "permission_revoked" -> permissions.when(()->NetMusicBiliServerCommands.canManageWhitelist(source)).thenReturn(false);
                    case "player_disconnected" -> when(pending.players.getPlayer(player.getUUID())).thenReturn(null);
                    case "server_stopped" -> pending.manager.when(()->BiliWhitelistManager.acceptsServer(server)).thenReturn(false);
                    default -> throw new AssertionError(change);
                }
                pending.complete(); assertTrue(sent.isEmpty());
            }
            assertTrue(BiliWhitelistManager.entries(server).getFirst().comments.isEmpty());
        }
    }

    /** Merged from WhitelistPreviewAdapterProductionTest: actual preview builder and latest-request/authority gates;
     * queued I/O, metadata/resolver and delivery are boundaries. */
    @Nested class Preview {
        ServerPlayer player; ServerLevel level; MinecraftServer server; PlayerList players; CommandSourceStack source;
        List<AutoCloseable> mocks=new ArrayList<>(); List<Supplier<?>> work=new ArrayList<>();
        List<CompletableFuture<Object>> results=new ArrayList<>();List<Runnable> callbacks=new ArrayList<>();
        List<WhitelistPreviewPacket> sent=new ArrayList<>();
        MockedStatic<NetMusicBiliServerCommands> permissions;
        MockedStatic<BiliWhitelistManager> whitelist;MockedStatic<MusicPlayResolverManager> resolver;
        @BeforeEach void setup() {
            PlatformBootstrap.initialize(); WhitelistPreviewPacket.clearPendingPreviews();
            server=mock(MinecraftServer.class);level=mock(ServerLevel.class);player=mock(ServerPlayer.class);players=mock(PlayerList.class);source=mock(CommandSourceStack.class);
            when(player.getUUID()).thenReturn(new UUID(321,456));when(player.level()).thenReturn(level);when(level.getServer()).thenReturn(server);
            when(player.createCommandSourceStack()).thenReturn(source);when(server.getPlayerList()).thenReturn(players);when(players.getPlayer(player.getUUID())).thenReturn(player);
            doAnswer(i->{callbacks.add(i.getArgument(0));return null;}).when(server).execute(any(Runnable.class));
            var io=own(mockStatic(MediaIoExecutor.class));io.when(()->MediaIoExecutor.supply(any())).thenAnswer(i->{work.add(i.getArgument(0));var result=new CompletableFuture<Object>();results.add(result);return result;});
            var delivery=own(mockStatic(PacketDistributor.class));delivery.when(()->PacketDistributor.sendToPlayer(any(ServerPlayer.class),any(CustomPacketPayload.class)))
                .thenAnswer(i->{sent.add(i.getArgument(1));return null;});
            permissions=own(mockStatic(NetMusicBiliServerCommands.class));permissions.when(()->NetMusicBiliServerCommands.canManageWhitelist(source)).thenReturn(true);
            whitelist=own(mockStatic(BiliWhitelistManager.class));whitelist.when(()->BiliWhitelistManager.acceptsServer(server)).thenReturn(true);
            var api=own(mockStatic(BiliApiClient.class,i->{if(i.getMethod().getName().equals("getVideoInfo"))
                    return new BiliApiClient.VideoInfo(1,"Title",List.of("Artist"),2,120,2,2,"Part",BiliApiClient.VideoId.bvid("BV1xx411c7mD"));return i.callRealMethod();}));
            resolver=own(mockStatic(MusicPlayResolverManager.class));resolver.when(()->MusicPlayResolverManager.resolve(any(ItemMusicCD.SongInfo.class)))
                .thenReturn(CompletableFuture.completedFuture(new ItemMusicCD.SongInfo("https://cdn-preview.test/a","Resolved",60,false)));
        }
        <T extends AutoCloseable>T own(T mock){mocks.add(mock);return mock;}
        @AfterEach void cleanup() throws Exception {for(int i=mocks.size()-1;i>=0;i--)mocks.get(i).close();WhitelistPreviewPacket.clearPendingPreviews();}
        void resolve(String raw,long elapsed,boolean playing,long request){WhitelistPreviewPacket.resolveAndSend(player,raw,elapsed,playing,request);}
        void finish(int index){try{results.get(index).complete(work.get(index).get());}catch(Throwable error){results.get(index).completeExceptionally(error);}drain();}
        void drain(){while(!callbacks.isEmpty())callbacks.removeFirst().run();}
        @ParameterizedTest @ValueSource(strings={"clear","server_stopped","offline","permission_revoked"})
        void latePreviewCannotPublishAfterLifecycleOrAuthorityChange(String change){
            resolve("bili:BV1xx411c7mD|p=2",1000,true,21);
            switch(change){case "clear"->WhitelistPreviewPacket.clearPendingPreviews();case "server_stopped"->whitelist.when(()->BiliWhitelistManager.acceptsServer(server)).thenReturn(false);
                case "offline"->when(players.getPlayer(player.getUUID())).thenReturn(null);case "permission_revoked"->permissions.when(()->NetMusicBiliServerCommands.canManageWhitelist(source)).thenReturn(false);}
            finish(0);assertTrue(sent.isEmpty());
        }
        ServerPlayer actor(int index){var actor=mock(ServerPlayer.class);when(actor.level()).thenReturn(level);when(actor.getUUID()).thenReturn(new UUID(700,index));when(actor.createCommandSourceStack()).thenReturn(source);when(players.getPlayer(actor.getUUID())).thenReturn(actor);return actor;}
        void request(ServerPlayer actor,long request){WhitelistPreviewPacket.resolveAndSend(actor,"https://fixture.invalid/a.mp3",request,true,request);}
        @Test void completedIoStillRechecksPermissionOnServerExecutorBeforeDelivery(){
            var actor=actor(0);request(actor,1);results.getFirst().complete(work.getFirst().get());assertEquals(1,callbacks.size());
            permissions.when(()->NetMusicBiliServerCommands.canManageWhitelist(source)).thenReturn(false);
            callbacks.removeFirst().run();assertTrue(sent.isEmpty());
            permissions.when(()->NetMusicBiliServerCommands.canManageWhitelist(source)).thenReturn(true);request(actor,2);finish(1);assertEquals(2,sent.getFirst().requestId());
        }
    }
}
