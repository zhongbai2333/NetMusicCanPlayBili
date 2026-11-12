package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMapSettings;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real runtime cache plus real item NBT seed; world disk loading is tested separately by the SavedData codec. */
class PadDocumentStoreIntegrationTest {
    @BeforeEach void initialize() { PlatformBootstrap.initialize(); PadDocumentStore.clearRuntime(); }
    @AfterEach void clear() { PadDocumentStore.clearRuntime(); }

    @Test void legacySeedMigratesToUnlockedAuthorityWithoutChangingTheLockedItemCopy() {
        UUID device = UUID.randomUUID();
        ItemStack seed = seed("legacy", true, 50, 9);
        PadDocument document = PadDocumentStore.getOrCreate(null, device, seed);
        assertEquals("legacy", document.title());
        assertFalse(document.locked());
        assertEquals(50, document.updatedAtMillis());
        assertEquals(9, document.sequence());
        assertTrue(PadItem.readDocument(seed).locked());
        assertSame(document, PadDocumentStore.get(device));
    }

    @Test void newerTimestampWinsEvenWhenItsSequenceIsLower() {
        UUID device = UUID.randomUUID();
        PadDocumentStore.getOrCreate(null, device, seed("old", false, 100, 99));
        PadDocument selected = PadDocumentStore.getOrCreate(null, device, seed("new", true, 101, 1));
        assertEquals("new", selected.title());
        assertEquals(1, selected.sequence());
        assertFalse(selected.locked());
        assertSame(selected, PadDocumentStore.get(device));
    }

    @Test void sameTimestampUsesSequenceAndAnEqualOrOlderItemCannotRollBackAuthority() {
        UUID device = UUID.randomUUID();
        PadDocumentStore.getOrCreate(null, device, seed("first", false, 100, 3));
        PadDocument newer = PadDocumentStore.getOrCreate(null, device, seed("higher sequence", false, 100, 4));
        assertEquals("higher sequence", newer.title());
        assertSame(newer, PadDocumentStore.getOrCreate(null, device, seed("equal", false, 100, 4)));
        assertSame(newer, PadDocumentStore.getOrCreate(null, device, seed("older time high sequence", false, 99, 1000)));
        assertSame(newer, PadDocumentStore.getOrCreate(null, device, seed("older sequence", false, 100, 2)));
    }

    @Test void clearingRuntimeRemovesPreviousWorldAuthorityBeforeReusingDeviceId() {
        UUID device = UUID.randomUUID();
        PadDocumentStore.getOrCreate(null, device, seed("world A", false, 1000, 90));
        PadDocumentStore.clearRuntime();
        assertEquals(PadDocument.DEFAULT, PadDocumentStore.get(device));
        PadDocument replacement = PadDocumentStore.getOrCreate(null, device, seed("world B", false, 10, 1));
        assertEquals("world B", replacement.title());
        assertEquals(10, replacement.updatedAtMillis());
    }

    @Test void updateKeepsDifferentDeviceIdsIndependentAndNormalizesOnlyAuthorityLock() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PadDocument supplied = document("first", true, 10, 1);
        PadDocumentStore.update(null, first, supplied);
        PadDocumentStore.update(null, second, document("second", false, 20, 2));
        assertTrue(supplied.locked());
        assertFalse(PadDocumentStore.get(first).locked());
        assertEquals("first", PadDocumentStore.get(first).title());
        assertEquals("second", PadDocumentStore.get(second).title());
        assertEquals(PadDocument.DEFAULT, PadDocumentStore.get(UUID.randomUUID()));
    }

    @Test void nullDeviceAndMissingLegacyDocumentUseDefaultWithoutCreatingCrossDeviceState() {
        assertEquals(PadDocument.DEFAULT, PadDocumentStore.getOrCreate(null, null, PlatformBootstrap.pad()));
        assertEquals(PadDocument.DEFAULT, PadDocumentStore.getOrCreate(null, UUID.randomUUID(), PlatformBootstrap.pad()));
        PadDocumentStore.update(null, null, document("ignored", false, 1, 1));
        assertEquals(PadDocument.DEFAULT, PadDocumentStore.get(null));
    }

    private static ItemStack seed(String title, boolean locked, long time, long sequence) {
        ItemStack stack = PlatformBootstrap.pad();
        PadItem.writeDocument(stack, document(title, locked, time, sequence));
        return stack;
    }
    private static PadDocument document(String title, boolean locked, long time, long sequence) {
        return new PadDocument(title, "author", locked, time, sequence, PadMapSettings.DEFAULT, List.of(), List.of());
    }
}
