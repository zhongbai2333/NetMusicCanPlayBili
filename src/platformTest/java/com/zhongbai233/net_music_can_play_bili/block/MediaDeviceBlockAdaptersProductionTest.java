package com.zhongbai233.net_music_can_play_bili.block;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.blockentity.*;
import com.zhongbai233.net_music_can_play_bili.init.*;
import com.zhongbai233.net_music_can_play_bili.link.*;
import com.zhongbai233.net_music_can_play_bili.server.*;
import com.zhongbai233.net_music_can_play_bili.testsupport.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MediaDeviceBlockAdaptersProductionTest {
    private static final BlockPos POS = new BlockPos(3, 4, 5);
    private ServerLevel level; private ServerPlayer player;
    private ModernTurntableBlockEntity turntable; private LiveStreamerBlockEntity live;
    private ModernTurntableBlock modernBlock; private LiveStreamerBlock liveBlock;
    private MockedStatic<BiliWhitelistManager> resolver;
    private MockedStatic<IndexedBlockPlaybackSessionManager> publisher;
    private MockedStatic<PlaybackAuditManager> audit;
    @BeforeAll static void bootstrap() { DeviceAdapterBootstrap.initialize(); }
    @BeforeEach void setup() {
        level = mock(ServerLevel.class); player = mock(ServerPlayer.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getServer()).thenReturn(mock(net.minecraft.server.MinecraftServer.class));
        turntable = PlatformBootstrap.turntable(POS); turntable.setLevel(level);
        live = PlatformBootstrap.liveStreamer(POS); live.setLevel(level);
        modernBlock = (ModernTurntableBlock) turntable.getBlockState().getBlock();
        liveBlock = (LiveStreamerBlock) live.getBlockState().getBlock();
        when(level.getBlockEntity(POS)).thenReturn(turntable);
        when(level.getBlockState(POS)).thenReturn(turntable.getBlockState());
        resolver = mockStatic(BiliWhitelistManager.class);
        resolver.when(() -> BiliWhitelistManager.resolveSong(any())).thenReturn(new CompletableFuture<ItemMusicCD.SongInfo>());
        publisher = mockStatic(IndexedBlockPlaybackSessionManager.class);
        audit = mockStatic(PlaybackAuditManager.class);
    }
    @AfterEach void cleanup() { audit.close(); publisher.close(); resolver.close(); }
    private InteractionResult use(ItemStack stack) {
        return modernBlock.useItemOn(stack, turntable.getBlockState(), level, POS, player, InteractionHand.MAIN_HAND, null);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mainHandInsertionCopiesOneDiscAndCreativeControlsConsumption(boolean creative) {
        when(player.isCreative()).thenReturn(creative);
        ItemStack stack = PlatformBootstrap.disc("https://example.test/a.mp3", 30); stack.setCount(3);
        assertEquals(InteractionResult.SUCCESS, use(stack));
        assertEquals(creative ? 3 : 2, stack.getCount());
        assertEquals(1, turntable.getDisc().getCount());
        assertTrue(ItemStack.isSameItemSameComponents(stack, turntable.getDisc()));
        // BE.setDisc.markDirty and the block interaction each publish the resulting HAS_DISC state.
        verify(level, times(2)).setBlock(eq(POS), argThat(s -> s.getValue(ModernTurntableBlock.HAS_DISC)), eq(3));
        resolver.verify(() -> BiliWhitelistManager.resolveSong(any()), times(1));
    }
    @Test void destroyStopsEachRealDeviceAndPlayerDestroyRemovesAndDropsDiscOnce() {
        turntable.setDisc(PlatformBootstrap.disc("https://example.test/a.mp3", 30));
        modernBlock.destroy(level, POS, turntable.getBlockState()); assertFalse(turntable.isPlaying()); assertTrue(turntable.hasDisc());
        try (var block = mockStatic(Block.class, CALLS_REAL_METHODS)) {
            block.when(() -> Block.popResource(eq(level), eq(POS), any())).thenAnswer(call -> null);
            block.when(() -> Block.dropResources(any(), eq(level), eq(POS), any(), eq(player), any())).thenAnswer(call -> null);
            modernBlock.playerDestroy(level, player, POS, turntable.getBlockState(), turntable, ItemStack.EMPTY);
            assertFalse(turntable.hasDisc());
            block.verify(() -> Block.popResource(eq(level), eq(POS), argThat(s -> s.getCount() == 1 && ItemMusicCD.getSongInfo(s) != null)), times(1));
        }
        when(level.getBlockEntity(POS)).thenReturn(live);
        CompoundTag saved = new CompoundTag(); saved.putString("RoomId", "123"); saved.putBoolean("Playing", true); saved.putBoolean("AutoResumeRequested", true);
        live.loadCustomOnly(TagValueInput.create(new ProblemReporter.Collector(), PlatformBootstrap.registries(), saved));
        liveBlock.destroy(level, POS, live.getBlockState()); assertFalse(live.isPlaying()); assertFalse(live.saveWithoutMetadata(PlatformBootstrap.registries()).getBoolean("AutoResumeRequested").orElseThrow());
    }
    /** Merged from SpeakerPlacementAdaptersProductionTest; keeps its own speaker/world fixture. */
    @Nested class SpeakerPlacement {
        private static final BlockPos POS = new BlockPos(100, 0, 0);
        private ServerLevel level; private ServerPlayer player; private SpeakerBlock block; private SpeakerBlockEntity speaker;
        private AudioPlaybackIndexSavedData data; private MockedStatic<AudioPlaybackIndexSavedData> storage;
        @BeforeEach void setup() {
            level = mock(ServerLevel.class); player = mock(ServerPlayer.class); when(level.dimension()).thenReturn(Level.OVERWORLD);
            block = (SpeakerBlock) ModBlocks.SPEAKER.get(); speaker = (SpeakerBlockEntity) block.newBlockEntity(POS, block.defaultBlockState()); speaker.setLevel(level);
            when(level.getBlockEntity(POS)).thenReturn(speaker); when(level.getBlockState(POS)).thenReturn(block.defaultBlockState());
            data = new AudioPlaybackIndexSavedData(); storage = mockStatic(AudioPlaybackIndexSavedData.class, CALLS_REAL_METHODS);
            storage.when(() -> AudioPlaybackIndexSavedData.get(level)).thenReturn(data);
        }
        @AfterEach void cleanup() { AudioLinkIndex.removeSpeakerEndpoint(level, speaker.getEndpointId(), POS); storage.close(); }
        @Test void serverScreenEntryRespectsBuildPermissionWithoutClientUi() {
            when(player.mayBuild()).thenReturn(false); assertEquals(InteractionResult.PASS, block.useWithoutItem(block.defaultBlockState(), level, POS, player, null));
            when(player.mayBuild()).thenReturn(true); assertEquals(InteractionResult.SUCCESS, block.useWithoutItem(block.defaultBlockState(), level, POS, player, null));
            assertFalse(speaker.isLinked()); assertTrue(data.endpointSnapshot().isEmpty());
        }
    }
}
