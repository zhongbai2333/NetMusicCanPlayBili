package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Calls real item, NBT and runtime store paths; no server/client or native media engine is launched.
 * Also covers real duplicate device-identity normalization and the actual item stacked-interaction callbacks
 * (UI/network/persistence boundaries mocked).
 */
class MP4InventoryPersistenceIntegrationTest {
    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }
    @BeforeEach void clearRuntime() { MP4DeviceStateStore.clearRuntime(); MP4DeviceLocationIndex.clear(); }

    @Test void capacityRejectsNineteenthDiscWithoutConsumingIt() {
        ItemStack device = PlatformBootstrap.mp4();
        for (int index = 0; index < 18; index++) {
            ItemStack disc = disc(index);
            assertTrue(MP4Item.addDisc(device, disc));
            assertTrue(disc.isEmpty());
        }
        ItemStack rejected = disc(18);
        assertFalse(MP4Item.addDisc(device, rejected));
        assertEquals(1, rejected.getCount());
        assertEquals(18, MP4Item.queueSize(device));
        assertEquals(List.of("song-0", "song-17"), List.of(title(MP4Item.readQueue(device).get(0)),
                title(MP4Item.readQueue(device).get(17))));
    }

    @Test void genericDiscInsertionConsumesOneAndWithdrawalReturnsOne() {
        ItemStack device = PlatformBootstrap.mp4();
        ItemStack source = disc(0); source.setCount(4);
        assertTrue(MP4Item.addDisc(device, source));
        assertEquals(3, source.getCount());
        assertEquals(1, MP4Item.queueSize(device));
        ItemStack removed = MP4Item.removeSelectedOrLastDisc(device);
        assertEquals(1, removed.getCount());
        assertEquals("song-0", title(removed));
        assertEquals(0, MP4Item.queueSize(device));
    }

    @Test void queueAndDeviceIdSurviveRealItemStackNbtRoundTrip() {
        ItemStack device = PlatformBootstrap.mp4();
        UUID deviceId = MP4Item.getOrCreateDeviceId(device);
        assertTrue(MP4Item.addDisc(device, disc(0)));
        assertTrue(MP4Item.addDisc(device, disc(1)));
        var encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, device).getOrThrow();
        ItemStack restored = ItemStack.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(deviceId, MP4Item.readDeviceId(restored));
        assertEquals(List.of("song-0", "song-1"), MP4Item.readQueue(restored).stream().map(this::title).toList());
        assertEquals("https://example.test/0.mp3", ItemMusicCD.getSongInfo(MP4Item.readQueue(restored).getFirst()).songUrl);
    }

    @Test void malformedQueueEntriesAreDiscardedAndValidEntriesCappedAtEighteen() {
        ItemStack device = PlatformBootstrap.mp4();
        ListTag queue = new ListTag();
        queue.add(new CompoundTag());
        CompoundTag foreign = new CompoundTag();
        foreign.store("stack", ItemStack.OPTIONAL_CODEC, new ItemStack(Items.STONE));
        queue.add(foreign);
        for (int index = 0; index < 22; index++) {
            CompoundTag entry = new CompoundTag();
            entry.store("stack", ItemStack.OPTIONAL_CODEC, disc(index));
            queue.add(entry);
        }
        CompoundTag tag = new CompoundTag(); tag.put("mp4_queue", queue);
        device.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        List<ItemStack> decoded = MP4Item.readQueue(device);
        assertEquals(18, decoded.size());
        assertEquals("song-0", title(decoded.getFirst()));
        assertEquals("song-17", title(decoded.getLast()));
    }

    @Test void malformedQueueTagAndMalformedDeviceIdDoNotThrow() {
        ItemStack device = PlatformBootstrap.mp4();
        CompoundTag tag = new CompoundTag();
        tag.put("mp4_queue", StringTag.valueOf("invalid list"));
        tag.putString("mp4_device_id", "broken uuid");
        device.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        assertNull(MP4Item.readDeviceId(device));
        assertTrue(MP4Item.readQueue(device).isEmpty());
        UUID repaired = MP4Item.getOrCreateDeviceId(device);
        assertEquals(repaired, MP4Item.getOrCreateDeviceId(device));
        assertEquals("invalid list", device.get(DataComponents.CUSTOM_DATA).copyTag().getString("mp4_queue").orElseThrow());
    }

    @Test void wrongCarrierAndNonMusicDiscAreRejectedWithoutConsumption() {
        ItemStack source = disc(0);
        assertFalse(MP4Item.addDisc(new ItemStack(Items.STONE), source));
        assertEquals(1, source.getCount());
        ItemStack stone = new ItemStack(Items.STONE, 3);
        assertFalse(MP4Item.addDisc(PlatformBootstrap.mp4(), stone));
        assertEquals(3, stone.getCount());
        assertTrue(MP4Item.removeSelectedOrLastDisc(PlatformBootstrap.mp4()).isEmpty());
    }

    @Test void removalUsesLastQueueOccurrenceEvenWhenUrlsDuplicate() {
        ItemStack device = PlatformBootstrap.mp4();
        ItemStack first = PlatformBootstrap.disc("https://example.test/duplicate.mp3", "first", 11);
        ItemStack second = PlatformBootstrap.disc("https://example.test/duplicate.mp3", "second", 22);
        assertTrue(MP4Item.addDisc(device, first)); assertTrue(MP4Item.addDisc(device, second));
        assertEquals("second", title(MP4Item.removeSelectedOrLastDisc(device)));
        assertEquals(List.of("first"), MP4Item.readQueue(device).stream().map(this::title).toList());
    }

    @Test void replacementIdentityRetainsContentsAndOtherCustomData() {
        ItemStack device = PlatformBootstrap.mp4();
        UUID before = MP4Item.getOrCreateDeviceId(device);
        device.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                data -> data.update(tag -> tag.putString("unrelated", "kept")));
        assertTrue(MP4Item.addDisc(device, disc(0)));
        UUID after = MP4DeviceIdentity.replaceDeviceId(null, device);
        assertNotEquals(before, after);
        assertEquals(after, MP4Item.readDeviceId(device));
        assertEquals("kept", device.get(DataComponents.CUSTOM_DATA).copyTag().getString("unrelated").orElseThrow());
        assertEquals("song-0", title(MP4Item.readQueue(device).getFirst()));
        assertNull(MP4DeviceIdentity.replaceDeviceId(null, new ItemStack(Items.STONE)));
    }

    @Test void actualStoreCopiesQueueNormalizesAndSeparatesDeviceIds() {
        ItemStack device = PlatformBootstrap.mp4();
        ItemStack source = disc(0); source.setCount(4); assertTrue(MP4Item.addDisc(device, source));
        UUID id = MP4Item.getOrCreateDeviceId(device);
        var entry = MP4DeviceStateStore.getOrCreate(null, id, device);
        assertEquals(1, entry.queue().size()); assertEquals(1, entry.queue().getFirst().getCount());
        ItemStack other = PlatformBootstrap.mp4(); UUID otherId = MP4Item.getOrCreateDeviceId(other);
        assertTrue(MP4DeviceStateStore.getOrCreate(null, otherId, other).queue().isEmpty());
        assertTrue(MP4DeviceStateStore.get(null).queue().isEmpty());
        MP4DeviceStateStore.clearRuntime();
        assertTrue(MP4DeviceStateStore.get(id).queue().isEmpty());
        assertEquals(1, MP4DeviceStateStore.getOrCreate(null, id, device).queue().size());
    }

    @Test void storeQueueCopyUpdatesOrderAndSelectedIndexAfterRemoval() {
        ItemStack device = PlatformBootstrap.mp4();
        for (int index = 0; index < 3; index++) assertTrue(MP4Item.addDisc(device, disc(index)));
        UUID id = MP4Item.getOrCreateDeviceId(device);
        MP4DeviceStateStore.getOrCreate(null, id, device);
        MP4DeviceStateStore.recordPlayback(null, id, 2, 5000, 30, 700, "session-a", true);
        long version = MP4DeviceStateStore.get(id).updatedGameTime();
        MP4Item.removeSelectedOrLastDisc(device);
        MP4DeviceStateStore.syncQueueCopy(null, id, device);
        var updated = MP4DeviceStateStore.get(id);
        assertEquals(List.of("song-0", "song-1"), updated.queue().stream().map(this::title).toList());
        assertEquals(1, updated.state().selectedQueueIndex());
        assertTrue(updated.state().playing());
        assertTrue(updated.updatedGameTime() > version);
        long stableVersion = updated.updatedGameTime();
        MP4DeviceStateStore.syncQueueCopy(null, id, device);
        assertEquals(stableVersion, MP4DeviceStateStore.get(id).updatedGameTime());
    }

    @Test void actualProgressRecordingPreservesConfigurationAndClampsProgress() {
        ItemStack device = PlatformBootstrap.mp4(); assertTrue(MP4Item.addDisc(device, disc(0)));
        UUID id = MP4Item.getOrCreateDeviceId(device);
        var entry = MP4DeviceStateStore.getOrCreate(null, id, device);
        MP4Item.State configuration = new MP4Item.State(false, true, false, true, 7, 0, 2, 555, 2,
                true, true, 1, true, 77, true);
        MP4DeviceStateStore.update(null, id, entry.withState(configuration));
        MP4DeviceStateStore.recordPlayback(null, id, 0, 5000, 10, 1500, "session-a", true);
        var recorded = MP4DeviceStateStore.get(id);
        assertEquals(500, recorded.state().progressPerMille());
        assertEquals(1000, recorded.state().volumePerMille());
        assertTrue(recorded.state().shuffle()); assertEquals(2, recorded.state().repeatMode());
        assertEquals(7, recorded.state().qualityIndex()); assertEquals(1, recorded.state().subtitleMode());
        assertEquals("session-a", recorded.sessionId()); assertTrue(recorded.state().playing());
        MP4DeviceStateStore.recordPlayback(null, id, 0, 20000, 10, -1, "", false);
        assertEquals(9950, MP4DeviceStateStore.get(id).elapsedMillis());
        assertEquals(1000, MP4DeviceStateStore.get(id).state().progressPerMille());
        assertEquals(0, MP4DeviceStateStore.get(id).state().volumePerMille());
        assertFalse(MP4DeviceStateStore.get(id).state().playing());
    }

    @Test void storeUnknownDurationPreservesProgressAndRejectsInvalidQueueMembers() {
        UUID id = UUID.randomUUID();
        var invalidState = new MP4Item.State(true, false, true, false, 88, 55, 99, -5, 99,
                false, false, 99, false, 321, false);
        List<ItemStack> input = new ArrayList<>(); input.add(new ItemStack(Items.STONE));
        for (int index = 0; index < 22; index++) input.add(disc(index));
        MP4DeviceStateStore.update(null, id, new MP4DeviceStateStore.DeviceEntry(invalidState, input, -1, -1, "", -4));
        var sanitized = MP4DeviceStateStore.get(id);
        assertEquals(18, sanitized.queue().size()); assertEquals(17, sanitized.state().selectedQueueIndex());
        assertEquals(7, sanitized.state().qualityIndex()); assertEquals(15, sanitized.state().queueScrollOffset());
        assertEquals(2, sanitized.state().repeatMode()); assertEquals(1, sanitized.state().subtitleMode());
        assertEquals(0, sanitized.elapsedMillis()); assertEquals(0, sanitized.durationSeconds());
        MP4DeviceStateStore.recordPlayback(null, id, 0, 12345, 0, 700, "session-b", true);
        assertEquals(321, MP4DeviceStateStore.get(id).state().progressPerMille());
        assertEquals(12345, MP4DeviceStateStore.get(id).elapsedMillis());
    }

    // Merged from MP4DeviceLifecycleIntegrationTest.
    @Test void duplicateInventoryIdentityKeepsFirstOccurrenceAndReassignsLaterCopies() {
        UUID id = UUID.randomUUID(); ItemStack first = device(id); ItemStack second = first.copy(); ItemStack third = first.copy();
        ServerPlayer player = player(first, second, third);
        assertTrue(MP4DeviceIdentity.normalizeDuplicateDeviceIds(null, player));
        assertEquals(id, MP4Item.readDeviceId(first));
        assertNotEquals(id, MP4Item.readDeviceId(second)); assertNotEquals(id, MP4Item.readDeviceId(third));
        assertNotEquals(MP4Item.readDeviceId(second), MP4Item.readDeviceId(third));
        verify(player.getInventory()).setChanged();
        assertFalse(MP4DeviceIdentity.normalizeDuplicateDeviceIds(null, player));
    }

    @Test void carriedOccurrenceHasPriorityDuringDuplicateNormalization() {
        UUID id = UUID.randomUUID(); ItemStack carried = device(id); ItemStack inInventory = carried.copy();
        ServerPlayer player = player(inInventory); AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        player.containerMenu = menu; when(menu.getCarried()).thenReturn(carried);
        assertTrue(MP4DeviceIdentity.normalizeDuplicateDeviceIds(null, player));
        assertEquals(id, MP4Item.readDeviceId(carried)); assertNotEquals(id, MP4Item.readDeviceId(inInventory));
        verify(menu).broadcastChanges();
    }

    /** Merged from item/MP4StackedInteractionProductionTest; keeps its own static-mock fixture. */
    @Nested class StackedInteraction {
        private MockedStatic<MP4PlaybackSavedData> persistence;
        private MockedStatic<MP4PlaybackSyncManager> playback;
        private ServerPlayer player;
        private ServerLevel level;
        private ItemStack device;
        private MP4Item item;
        @BeforeEach void setup() {
            MP4DeviceStateStore.clearRuntime(); MP4DeviceLocationIndex.clear();
            level = mock(ServerLevel.class); when(level.dimension()).thenReturn(Level.OVERWORLD);
            player = mock(ServerPlayer.class); when(player.level()).thenReturn(level);
            when(player.getUUID()).thenReturn(UUID.randomUUID()); when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            Inventory inventory = mock(Inventory.class); when(player.getInventory()).thenReturn(inventory);
            device = PlatformBootstrap.mp4(); item = (MP4Item) device.getItem();
            persistence = mockStatic(MP4PlaybackSavedData.class);
            persistence.when(() -> MP4PlaybackSavedData.get(level)).thenReturn(new MP4PlaybackSavedData());
            playback = mockStatic(MP4PlaybackSyncManager.class);
        }
        @AfterEach void teardown() { playback.close(); persistence.close(); MP4DeviceStateStore.clearRuntime(); }

        @Test void stackedOnOtherPrimaryConsumesOneDiscUpdatesSlotAndRealAuthoritativeQueue() {
            ItemStack disc = disc("first"); Slot slot = mock(Slot.class); when(slot.getItem()).thenReturn(disc);
            assertTrue(item.overrideStackedOnOther(device, slot, ClickAction.PRIMARY, player));
            assertTrue(disc.isEmpty()); verify(slot).set(ItemStack.EMPTY); verify(slot).setChanged();
            UUID source = MP4Item.readDeviceId(device); assertNotNull(source);
            assertEquals("first", title(MP4DeviceStateStore.get(source).queue().getFirst()));
            playback.verify(() -> MP4PlaybackSyncManager.reconcileQueueChange(eq(player), eq(source), anyList(), eq(-1)));
        }

        @Test void fullQueueCallbackRejectsWithoutConsumingOrSendingSynchronization() {
            for (int i = 0; i < 18; i++) assertTrue(MP4Item.addDisc(device, disc("song-" + i)));
            ItemStack carried = disc("rejected"); SlotAccess access = mock(SlotAccess.class);
            assertFalse(item.overrideOtherStackedOnMe(device, carried, mock(Slot.class), ClickAction.PRIMARY, player, access));
            assertEquals(1, carried.getCount()); verifyNoInteractions(access); playback.verifyNoInteractions();
        }

        private ItemStack disc(String title) { return PlatformBootstrap.disc("https://example.test/" + title + ".mp3", title, 30); }
    }

    private ItemStack disc(int index) { return PlatformBootstrap.disc("https://example.test/" + index + ".mp3", "song-" + index, 30 + index); }
    private String title(ItemStack disc) { return ItemMusicCD.getSongInfo(disc).songName; }
    private ItemStack device(UUID id) { ItemStack stack = PlatformBootstrap.mp4(); MP4Item.writeDeviceId(stack, id); return stack; }
    private ServerPlayer player(ItemStack... contents) {
        ServerPlayer player = mock(ServerPlayer.class); Inventory inventory = mock(Inventory.class);
        when(player.getInventory()).thenReturn(inventory); when(inventory.getContainerSize()).thenReturn(contents.length);
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        for (int index = 0; index < contents.length; index++) when(inventory.getItem(index)).thenReturn(contents[index]);
        return player;
    }
}
