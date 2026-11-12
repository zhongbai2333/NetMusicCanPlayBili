package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Executes the real upload admission, authoritative store update and copies' item NBT writes, plus the Pad
 * playback-control, publish and stacked-inventory handlers (Slot/SlotAccess are UI delivery boundaries).
 */
class PadStateProductionHandlerTest {
    enum Direction { PAD_ON_SLOT, DISC_ON_PAD }
    PadProductionHandlerTestFixture f;
    final List<ItemStack> inserted = new ArrayList<>();
    final List<ItemStack> delivered = new ArrayList<>();
    Slot slot;
    SlotAccess carried;
    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }
    @BeforeEach void setup() {
        f = new PadProductionHandlerTestFixture(); inserted.clear(); delivered.clear();
        slot = mock(Slot.class); carried = mock(SlotAccess.class);
        doAnswer(invocation -> { delivered.add(invocation.getArgument(0)); return null; }).when(slot).set(any(ItemStack.class));
        when(carried.set(any(ItemStack.class))).thenAnswer(invocation -> { delivered.add(invocation.getArgument(0)); return true; });
    }
    @AfterEach void close() throws Exception { f.close(); }

    @Test void lockedOnlyInventoryRejectsDraftUploadWithoutAnyDocumentMutation() {
        var locked = f.copy(true);
        f.carrier(locked, net.minecraft.world.item.ItemStack.EMPTY);
        var before = PadItem.readDocument(locked);
        upload(f.document("blocked", 200, 2, false), 200, 2);
        assertEquals(before.title(), PadItem.readDocument(locked).title());
        assertEquals(before.sequence(), PadItem.readDocument(locked).sequence());
        assertTrue(PadItem.readLocked(locked));
        assertEquals(PadDocument.DEFAULT, PadDocumentStore.get(f.device));
        assertTrue(f.saved.document(f.device).isEmpty());
        assertTrue(f.mirrors.isEmpty());
    }

    @Test void unlockedCopyIsChosenEvenWhenLockedCopyAppearsFirstAndAllCopiesRetainTheirLock() {
        var locked = f.copy(true);
        var unlocked = f.copy(false);
        f.carrier(locked, unlocked);
        PadDocument incoming = f.document("accepted", 200, 2, true);
        upload(incoming, 200, 2);
        assertEquals("accepted", PadDocumentStore.get(f.device).title());
        assertFalse(PadDocumentStore.get(f.device).locked());
        assertEquals("accepted", f.saved.document(f.device).orElseThrow().title());
        for (var stack : java.util.List.of(locked, unlocked)) {
            assertEquals(f.device, PadItem.readDeviceId(stack));
            assertEquals("accepted", PadItem.readDocument(stack).title());
            assertEquals(200, PadItem.readDocument(stack).updatedAtMillis());
            assertEquals(2, PadItem.readDocument(stack).sequence());
            assertEquals(incoming.triggerPoints(), PadItem.readDocument(stack).triggerPoints());
        }
        assertTrue(PadItem.readLocked(locked));
        assertFalse(PadItem.readLocked(unlocked));
    }

    @Test void olderClientEnvelopeCannotOverwriteStateWithAYoungerDocument() {
        upload(f.document("accepted", 200, 2, false), 1000, 5);
        upload(f.document("out of order", 300, 3, false), 999, 999);
        assertEquals("accepted", PadDocumentStore.get(f.device).title());
        assertEquals("accepted", PadItem.readDocument(f.pad).title());
        assertEquals(200, f.saved.document(f.device).orElseThrow().updatedAtMillis());
    }

    @Test void equalClientTimestampUsesSequenceToRejectAnOlderEnvelope() {
        upload(f.document("accepted", 200, 2, false), 1000, 5);
        upload(f.document("old sequence", 300, 3, false), 1000, 4);
        assertEquals("accepted", PadDocumentStore.get(f.device).title());
        assertEquals(2, PadItem.readDocument(f.pad).sequence());
    }

    @Test void olderDocumentVersionIsRejectedDespiteNewerClientEnvelope() {
        upload(f.document("accepted", 200, 5, false), 1000, 5);
        upload(f.document("old document", 200, 4, false), 1001, 6);
        assertEquals("accepted", PadDocumentStore.get(f.device).title());
        assertEquals(5, f.saved.document(f.device).orElseThrow().sequence());
    }

    @Test void rejectedStaleDocumentDoesNotBlockLegitimateLaterUploadWithHigherEnvelope() {
        upload(f.document("accepted", 200, 5, false), 1000, 5);
        upload(f.document("stale rejected", 200, 4, false), 1001, 6);
        assertEquals("accepted", PadDocumentStore.get(f.device).title());
        // PadClient.markDocumentDirty stamps each new send intent and increments localSequence.
        // A genuinely later upload uses a later envelope; a lower envelope would model an old packet.
        upload(f.document("legitimate later", 201, 6, false), 1002, 7);
        assertEquals("legitimate later", PadDocumentStore.get(f.device).title());
        assertEquals(201, f.saved.document(f.device).orElseThrow().updatedAtMillis());
        assertEquals(6, f.saved.document(f.device).orElseThrow().sequence());
        assertEquals("legitimate later", PadItem.readDocument(f.pad).title());
    }

    @Test void exactDuplicateUploadKeepsAuthorityAndItemContentsIdentical() {
        var incoming = f.document("duplicate", 200, 2, false);
        upload(incoming, 200, 2);
        var before = f.pad.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        upload(incoming, 200, 2);
        assertEquals(before, f.pad.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag());
        assertEquals(incoming, PadDocumentStore.get(f.device));
        assertEquals(incoming, f.saved.document(f.device).orElseThrow());
    }

    @Test void equalEnvelopeWithNewerDocumentIsAcceptedByCurrentProductionPolicy() {
        upload(f.document("first", 200, 2, false), 1000, 5);
        upload(f.document("newer doc", 201, 3, false), 1000, 5);
        assertEquals("newer doc", PadDocumentStore.get(f.device).title());
        assertEquals(3, PadItem.readDocument(f.pad).sequence());
    }

    @Test void removingPlayerSyncHistoryAllowsNewConnectionEnvelopeWithoutRollingBackDocument() {
        upload(f.document("before reconnect", 200, 2, false), 1000, 5);
        PadStatePacket.removeClientSyncs(f.owner);
        upload(f.document("after reconnect", 201, 3, false), 1, 1);
        assertEquals("after reconnect", PadDocumentStore.get(f.device).title());
    }

    @Test void clientSyncHistoryIsKeyedByPlayerRatherThanOnlySharedDevice() {
        upload(f.document("first player", 200, 2, false), 1000, 5);
        when(f.player.getUUID()).thenReturn(UUID.randomUUID());
        upload(f.document("second player", 201, 3, false), 1, 1);
        assertEquals("second player", PadDocumentStore.get(f.device).title());
    }

    @Test void missingDeviceOrDocumentIsRejectedBeforeStoreUpdate() {
        PadStatePacket.handle(new PadStatePacket(UUID.randomUUID(), f.document("not held", 200, 2, false), 200, 2), f.context);
        PadStatePacket.handle(new PadStatePacket(f.device, null, 200, 2), f.context);
        PadStatePacket.handle(new PadStatePacket(null, f.document("null id", 200, 2, false), 200, 2), f.context);
        assertEquals(PadDocument.DEFAULT, PadDocumentStore.get(f.device));
        assertTrue(f.saved.document(f.device).isEmpty());
        assertEquals("initial", PadItem.readDocument(f.pad).title());
    }

    // Merged from PadPlaybackControlProductionHandlerTest (its setup enabled the resolver for every test).
    @Test void actualEightRequestWindowRejectsNinthPlaybackControlBeforeAnyResolverWork() {
        f.enableResolver();
        for (int i = 0; i < 8; i++) assertTrue(NetworkRateLimiter.allow(f.owner, "pad_playback_control", 8));
        f.control(PadPlaybackControlPacket.Action.START, f.firstPoint, 0);
        assertTrue(f.requested.isEmpty()); assertTrue(f.starts.isEmpty()); assertTrue(f.stops.isEmpty());
    }

    @Test void wrongDeviceIdCannotControlAnUnrelatedPad() {
        f.enableResolver();
        PadPlaybackControlPacket.handle(new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.STOP,
                UUID.randomUUID(), null, 0), f.context);
        assertTrue(f.requested.isEmpty()); assertTrue(f.starts.isEmpty()); assertTrue(f.stops.isEmpty());
    }

    // Merged from PadPublishProductionHandlerTest.
    @Test void lockedOnlyCarrierCannotBeRepublishedAsADraft() {
        var locked = f.copy(true); f.carrier(locked, ItemStack.EMPTY);
        var before = locked.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        publish(f.device);
        verify(f.inventory, never()).add(any(ItemStack.class));
        verify(f.player, never()).drop(any(ItemStack.class), anyBoolean());
        assertTrue(f.mirrors.isEmpty());
        assertTrue(f.saved.document(f.device).isEmpty());
        assertEquals(before, locked.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag());
    }

    @Test void twoPublishesShareIdAndActualRateWindowRejectsThirdCopy() {
        when(f.inventory.add(any(ItemStack.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0)); return true;
        });
        publish(f.device); publish(f.device); publish(f.device);
        assertEquals(2, inserted.size());
        assertEquals(2, f.mirrors.size());
        assertNotSame(inserted.get(0), inserted.get(1));
        for (ItemStack copy : inserted) {
            assertEquals(f.device, PadItem.readDeviceId(copy));
            assertTrue(PadItem.readLocked(copy));
            assertEquals("initial", PadItem.readDocument(copy).title());
        }
        assertFalse(PadItem.readLocked(f.pad));
    }

    // Merged from PadStackedInteractionProductionHandlerTest.
    @ParameterizedTest @EnumSource(Direction.class)
    void lockedInventoryCallbacksRejectInsertionAndExtractionWithoutConsumptionDeliveryOrMirror(Direction direction) {
        PadItem.writeLocked(f.pad, true);
        var before = f.pad.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        ItemStack disc = PlatformBootstrap.disc("https://example.test/new.mp3", "New", 33);
        disc.setCount(3); when(slot.getItem()).thenReturn(disc); when(slot.hasItem()).thenReturn(false);
        assertFalse(interact(direction, ClickAction.PRIMARY, disc));
        assertFalse(interact(direction, ClickAction.SECONDARY, ItemStack.EMPTY));
        assertEquals(3, disc.getCount());
        assertEquals(before, f.pad.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag());
        assertTrue(delivered.isEmpty()); assertTrue(f.mirrors.isEmpty());
        verify(slot, never()).setChanged();
    }

    private void upload(PadDocument document, long envelopeTime, long envelopeSequence) {
        PadStatePacket.handle(new PadStatePacket(f.device, document, envelopeTime, envelopeSequence), f.context);
    }
    private void publish(UUID id) { PadPublishPacket.handle(new PadPublishPacket(id), f.context); }
    private boolean interact(Direction direction, ClickAction action, ItemStack input) {
        PadItem item = (PadItem) f.pad.getItem();
        return direction == Direction.PAD_ON_SLOT
                ? item.overrideStackedOnOther(f.pad, slot, action, f.player)
                : item.overrideOtherStackedOnMe(f.pad, input, slot, action, f.player, carried);
    }
}
