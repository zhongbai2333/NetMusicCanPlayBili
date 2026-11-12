package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.network.message.SetMusicIDMessage;
import com.github.tartaricacid.netmusic.inventory.CDBurnerMenu;
import com.github.tartaricacid.netmusic.inventory.ComputerMenu;
import com.github.tartaricacid.netmusic.inventory.io.CDInput;
import com.github.tartaricacid.netmusic.inventory.io.CDOutput;
import com.zhongbai233.net_music_can_play_bili.Config;
import com.zhongbai233.net_music_can_play_bili.server.*;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SetMusicID server path. {@link Guard}: actual guard method, not FML injection; canonical HTTP and final NetMusic
 * delegate are controlled ports. {@link NetMusicTarget} (from NetMusicBurnerFunctionalClosureTest): the real NetMusic
 * SetMusicIDMessage handler against real burner/computer menus, so it must not share Guard's delegate static mock.
 */
class SetMusicIdGuardProductionTest {
    @Nested class Guard {
        static final String BV="BV1xx411c7mD|p=2",SHORT="https://b23.tv/guard-fixture";
        @TempDir Path world;MinecraftServer server;ServerPlayer player;PlayerList players;AbstractContainerMenu menu;
        IPayloadContext context;NonNullList<ItemStack> slots;boolean previousEnabled;Method guard;
        MockedStatic<ServerLifecycleHooks> hooks;MockedStatic<BiliWhitelistManager> whitelist;MockedStatic<SetMusicIDMessage> delegate;
        List<Runnable> callbacks=new ArrayList<>();List<SetMusicIDMessage> delegated=new ArrayList<>();
        List<CompletableFuture<Optional<BiliWhitelistManager.CanonicalResource>>> canonical=new ArrayList<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        @BeforeEach void setup() throws Exception {
            PlatformBootstrap.initialize();DeferredDiscRequests.clear();previousEnabled=Config.enableLinkWhitelist;Config.enableLinkWhitelist=true;
            server=mock(MinecraftServer.class);player=mock(ServerPlayer.class);players=mock(PlayerList.class);menu=mock(AbstractContainerMenu.class);context=mock(IPayloadContext.class);var level=mock(ServerLevel.class);
            when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);when(server.getPlayerList()).thenReturn(players);
            when(player.getUUID()).thenReturn(UUID.randomUUID());when(player.getDisplayName()).thenReturn(Component.literal("GuardTester"));
            when(player.level()).thenReturn(level);when(level.getServer()).thenReturn(server);when(players.getPlayer(player.getUUID())).thenReturn(player);
            when(context.player()).thenReturn(player);when(context.flow()).thenReturn(PacketFlow.SERVERBOUND);
            slots=NonNullList.create();slots.add(new ItemStack(Items.PAPER,1));when(menu.getItems()).thenReturn(slots);player.containerMenu=menu;
            doAnswer(i->{callbacks.add(i.getArgument(0));return null;}).when(server).execute(any(Runnable.class));
            hooks=mockStatic(ServerLifecycleHooks.class);hooks.when(ServerLifecycleHooks::getCurrentServer).thenReturn(server);
            whitelist=mockStatic(BiliWhitelistManager.class,i->{
                if(i.getMethod().getName().equals("canonicalResourceAsync")&&SHORT.equals(i.getArgument(0))){
                    var future=new CompletableFuture<Optional<BiliWhitelistManager.CanonicalResource>>();canonical.add(future);return future;
                }
                try{return i.callRealMethod();}catch(Throwable error){failure.set(error);throw error;}
            });
            delegate=mockStatic(SetMusicIDMessage.class);delegate.when(()->SetMusicIDMessage.handle(any(),eq(context))).thenAnswer(i->{delegated.add(i.getArgument(0));return null;});
            guard=SetMusicIDMessageMixin.class.getDeclaredMethod("net_music_can_play_bili$guardBiliWhitelist",SetMusicIDMessage.class,IPayloadContext.class,CallbackInfo.class);guard.setAccessible(true);
            BiliWhitelistManager.add(server,BV,player);
        }
        @AfterEach void cleanup(){delegate.close();whitelist.close();hooks.close();Config.enableLinkWhitelist=previousEnabled;DeferredDiscRequests.clear();}
        ItemMusicCD.SongInfo song(String raw){return new ItemMusicCD.SongInfo(raw,"UserTitle",99,false);}
        CallbackInfo invoke(ItemMusicCD.SongInfo song)throws Exception{
            var ci=new CallbackInfo("handle",true);guard.invoke(null,new SetMusicIDMessage(song),context,ci);return ci;
        }
        void finish(int index)throws Exception{canonical.get(index).complete(Optional.of(new BiliWhitelistManager.CanonicalResource("bili",BV)));drain();}
        void drain(){while(!callbacks.isEmpty())callbacks.removeFirst().run();if(failure.get()!=null)throw new AssertionError("Guard boundary fixture failed",failure.get());}
        @Test void actualShortLinkGuardDefersThenDelegatesCanonicalFrozenSong()throws Exception{
            var original=song(SHORT);original.readOnly=true;original.artists=new ArrayList<>(List.of("UserArtist"));
            assertTrue(invoke(original).isCancelled());assertEquals(1,canonical.size());assertTrue(delegated.isEmpty());
            finish(0);assertEquals(1,delegated.size());var frozen=delegated.getFirst().song();
            assertEquals(BV,frozen.songUrl);assertEquals("UserTitle",frozen.songName);assertEquals(99,frozen.songTime);
            assertEquals(List.of("UserArtist"),frozen.artists);assertTrue(frozen.readOnly);assertNotSame(original,frozen);assertEquals(SHORT,original.songUrl);
            verify(player,never()).sendSystemMessage(any(Component.class));
        }
        @ParameterizedTest @ValueSource(strings={"menu","slot_value","slot_count","logout","replaced_player","server_stopped","clear"})
        void actualGuardRejectsLateCanonicalAfterSnapshotOrLifecycleChange(String change)throws Exception{
            invoke(song(SHORT));switch(change){
                case "menu"->player.containerMenu=mock(AbstractContainerMenu.class);
                case "slot_value"->slots.set(0,new ItemStack(Items.STONE));
                case "slot_count"->slots.add(ItemStack.EMPTY);
                case "logout"->when(players.getPlayer(player.getUUID())).thenReturn(null);
                case "replaced_player"->when(players.getPlayer(player.getUUID())).thenReturn(mock(ServerPlayer.class));
                case "server_stopped"->hooks.when(ServerLifecycleHooks::getCurrentServer).thenReturn(null);
                case "clear"->DeferredDiscRequests.clear();
            }
            finish(0);assertTrue(delegated.isEmpty());verify(player,never()).sendSystemMessage(any(Component.class));
        }
        @Test void actualGuardRechecksWhitelistAfterAsyncRevocation()throws Exception{
            invoke(song(SHORT));assertEquals(BiliWhitelistManager.RemoveResult.Status.REMOVED,BiliWhitelistManager.remove(server,BV,player,"revoke").status());
            finish(0);assertTrue(delegated.isEmpty());verify(player).sendSystemMessage(any(Component.class));
        }
        @ParameterizedTest @ValueSource(strings={"error","empty"})
        void actualGuardReportsShortLinkFailureWithoutDelegating(String kind)throws Exception{
            invoke(song(SHORT));if(kind.equals("error"))canonical.getFirst().completeExceptionally(new IllegalStateException("controlled"));else canonical.getFirst().complete(Optional.empty());
            drain();assertTrue(delegated.isEmpty());verify(player).sendSystemMessage(any(Component.class));
        }
        @Test void actualGuardNewestTicketWinsAndOlderCompletionCannotClearNewRequest()throws Exception{
            invoke(song(SHORT));invoke(song(SHORT));assertEquals(2,canonical.size());finish(0);assertTrue(delegated.isEmpty());finish(1);assertEquals(1,delegated.size());
        }
        @ParameterizedTest @ValueSource(booleans={true,false})
        void actualGuardRejectsForbiddenCdnUrlEvenWhenWhitelistDisabled(boolean enabled)throws Exception{
            Config.enableLinkWhitelist=enabled;assertTrue(invoke(song("https://cdn.bilivideo.com/audio.m4s")).isCancelled());
            assertTrue(delegated.isEmpty());assertTrue(canonical.isEmpty());verify(player).sendSystemMessage(any(Component.class));
        }
        @Test void actualGuardAllowsOrdinaryUrlWhenWhitelistDisabled()throws Exception{
            Config.enableLinkWhitelist=false;assertFalse(invoke(song("https://ordinary-fixture.test/a.mp3")).isCancelled());assertTrue(delegated.isEmpty());assertTrue(canonical.isEmpty());
        }
        @Test void actualGuardNormalizesVideoPageUrlBeforeDelegatingNetMusic()throws Exception{
            var input=song("https://www.bilibili.com/video/BV1xx411c7mD?p=2");input.readOnly=true;
            assertTrue(invoke(input).isCancelled());assertEquals(1,delegated.size());assertEquals(BV,delegated.getFirst().song().songUrl);assertTrue(delegated.getFirst().song().readOnly);
            assertTrue(canonical.isEmpty());
        }
        @Test void actualGuardLeavesClientboundFlowUntouched()throws Exception{
            when(context.flow()).thenReturn(PacketFlow.CLIENTBOUND);assertFalse(invoke(song(SHORT)).isCancelled());assertTrue(canonical.isEmpty());assertTrue(delegated.isEmpty());
        }
    }

    @Nested class NetMusicTarget {
        @BeforeEach void setup(){PlatformBootstrap.initialize();}
        @ParameterizedTest @CsvSource({"burner,false","burner,true","computer,false","computer,true"})
        void actualNetMusicTargetConsumesOneDiscPreservesReadOnlyAndDuplicateCannotConsumeAgain(String kind,boolean locked) throws Exception {
            AbstractContainerMenu menu=kind.equals("burner")?mock(CDBurnerMenu.class,CALLS_REAL_METHODS):mock(ComputerMenu.class,CALLS_REAL_METHODS);
            CDInput material=new CDInput();CDOutput output=new CDOutput();
            Class<?> owner=kind.equals("burner")?CDBurnerMenu.class:ComputerMenu.class;
            var inputField=owner.getDeclaredField("input");inputField.setAccessible(true);inputField.set(menu,material);
            var outputField=owner.getDeclaredField("output");outputField.setAccessible(true);outputField.set(menu,output);
            var disc=PlatformBootstrap.disc("BV1xx411c7mD|p=1","Original",10);
            var original=ItemMusicCD.getSongInfo(disc);original.readOnly=locked;ItemMusicCD.setSongInfo(original,disc);
            material.set(0,ItemResource.of(disc),2);
            var actor=mock(ServerPlayer.class);actor.containerMenu=menu;var context=mock(IPayloadContext.class);
            when(context.flow()).thenReturn(PacketFlow.SERVERBOUND);when(context.player()).thenReturn(actor);
            List<Runnable> tasks=new ArrayList<>();doAnswer(i->{tasks.add(i.getArgument(0));return CompletableFuture.completedFuture(null);}).when(context).enqueueWork(any(Runnable.class));
            var replacement=new ItemMusicCD.SongInfo("BV1xx411c7mD|p=2","Replacement",99,true);
            var message=new SetMusicIDMessage(replacement);SetMusicIDMessage.handle(message,context);
            assertEquals(2,material.getAmountAsInt(0));assertTrue(output.getResource(0).isEmpty());tasks.removeFirst().run();
            assertEquals(1,material.getAmountAsInt(0));assertEquals(1,output.getAmountAsInt(0));
            var actual=ItemMusicCD.getSongInfo(output.getResource(0).toStack(1));
            assertEquals(locked?"BV1xx411c7mD|p=1":"BV1xx411c7mD|p=2",actual.songUrl);
            assertEquals(locked?10:99,actual.songTime);assertTrue(actual.readOnly);
            SetMusicIDMessage.handle(message,context);tasks.removeFirst().run();
            assertEquals(1,material.getAmountAsInt(0));assertEquals(1,output.getAmountAsInt(0));
        }
    }
}
