package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.datafixers.DataFixer;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.*;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual world storage IO and production store bridge; only ServerLevel and unrelated data fixer are doubles. */
class PadWorldStorageBridgeIntegrationTest {
    @TempDir Path directory;
    @BeforeEach void initialize() { PlatformBootstrap.initialize(); PadDocumentStore.clearRuntime(); }
    @AfterEach void clear() { PadDocumentStore.clearRuntime(); }

    @Test void authoritativeDocumentReopensFromActualDatFileAfterRuntimeClear() throws Exception {
        UUID device = UUID.randomUUID();
        PadDocument supplied = document("世界权威", true, 1000, 15,
                new PadMapSettings("minecraft:the_nether", -20, 31, 128, 2.5F, false));
        ItemStack oldLockedSeed = seed(device, document("旧物品副本", true, 1, 1, PadMapSettings.DEFAULT));
        try (SavedDataStorage storage = storage()) {
            ServerLevel level = level(storage);
            PadDocumentStore.update(level, device, supplied);
            assertTrue(PadDocumentSavedData.get(level).isDirty());
            storage.saveAndJoin();
            Path file = directory.resolve("net_music_can_play_bili/pad_documents.dat");
            assertTrue(Files.isRegularFile(file));
            assertTrue(Files.size(file) > 0);
        }
        PadDocumentStore.clearRuntime();
        try (SavedDataStorage reopened = storage()) {
            ServerLevel level = level(reopened);
            PadDocument restored = PadDocumentStore.getOrCreate(level, device, oldLockedSeed);
            assertDocument(supplied, restored);
            assertFalse(restored.locked());
            assertTrue(supplied.locked());
            assertTrue(PadItem.readDocument(oldLockedSeed).locked());
            assertEquals("旧物品副本", PadItem.readDocument(oldLockedSeed).title());
            assertSame(restored, PadDocumentStore.get(device));
            assertDocument(supplied, PadDocumentSavedData.get(level).document(device).orElseThrow());
        }
    }

    @Test void firstLegacyItemSeedIsMigratedSavedAndRestoredWithoutTheOriginalItem() throws Exception {
        UUID device = UUID.randomUUID();
        PadDocument legacy = document("legacy迁移", true, 200, 4, PadMapSettings.DEFAULT);
        try (SavedDataStorage storage = storage()) {
            ServerLevel level = level(storage);
            PadDocument migrated = PadDocumentStore.getOrCreate(level, device, seed(device, legacy));
            assertDocument(legacy, migrated);
            assertFalse(migrated.locked());
            assertTrue(PadDocumentSavedData.get(level).isDirty());
            storage.saveAndJoin();
        }
        PadDocumentStore.clearRuntime();
        try (SavedDataStorage reopened = storage()) {
            ItemStack emptyCopy = PlatformBootstrap.pad();
            PadItem.writeDeviceId(emptyCopy, device);
            assertNull(PadItem.readLegacyDocument(emptyCopy));
            PadDocument restored = PadDocumentStore.getOrCreate(level(reopened), device, emptyCopy);
            assertDocument(legacy, restored);
            assertFalse(restored.locked());
            assertEquals(device, PadItem.readDeviceId(emptyCopy));
        }
    }

    @Test void newerItemCopyUpdatesRealSavedAuthorityAndPersistsAcrossAnotherReopen() throws Exception {
        UUID device = UUID.randomUUID();
        PadDocument older = document("先前世界", false, 100, 99, PadMapSettings.DEFAULT);
        PadDocument newer = document("较新物品", true, 101, 2, PadMapSettings.DEFAULT);
        try (SavedDataStorage storage = storage()) {
            PadDocumentStore.update(level(storage), device, older);
            storage.saveAndJoin();
        }
        PadDocumentStore.clearRuntime();
        try (SavedDataStorage reopened = storage()) {
            ServerLevel level = level(reopened);
            PadDocument updated = PadDocumentStore.getOrCreate(level, device, seed(device, newer));
            assertDocument(newer, updated);
            assertFalse(updated.locked());
            assertDocument(newer, PadDocumentSavedData.get(level).document(device).orElseThrow());
            assertTrue(PadDocumentSavedData.get(level).isDirty());
            reopened.saveAndJoin();
        }
        PadDocumentStore.clearRuntime();
        try (SavedDataStorage reopenedAgain = storage()) {
            PadDocument restored = PadDocumentStore.getOrCreate(level(reopenedAgain), device, PlatformBootstrap.pad());
            assertDocument(newer, restored);
        }
    }

    private SavedDataStorage storage() {
        DataFixer fixer = mock(DataFixer.class);
        when(fixer.update(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        return new SavedDataStorage(null, directory, fixer, PlatformBootstrap.registries());
    }
    private static ServerLevel level(SavedDataStorage storage) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.getDataStorage()).thenReturn(storage);
        return level;
    }
    private static ItemStack seed(UUID device, PadDocument document) {
        ItemStack stack = PlatformBootstrap.pad();
        PadItem.writeDeviceId(stack, device);
        PadItem.writeDocument(stack, document);
        return stack;
    }
    private static PadDocument document(String title, boolean locked, long time, long sequence, PadMapSettings map) {
        return new PadDocument(title, "作者", locked, time, sequence, map,
                List.of(new PadMediaEntry(3, PlatformBootstrap.disc("https://example.test/persist.mp4", "片段", 50))),
                List.of(new PadTriggerPoint(UUID.randomUUID(), "触发点", -3.25, 70.5, 4.75, 24, 3,
                        PadTriggerMode.ENTER_RADIUS, true, 725, false)));
    }
    private static void assertDocument(PadDocument expected, PadDocument actual) {
        assertEquals(expected.title(), actual.title());
        assertEquals(expected.author(), actual.author());
        assertEquals(expected.updatedAtMillis(), actual.updatedAtMillis());
        assertEquals(expected.sequence(), actual.sequence());
        assertEquals(expected.mapSettings(), actual.mapSettings());
        assertEquals(expected.triggerPoints(), actual.triggerPoints());
        assertEquals(expected.mediaEntries().size(), actual.mediaEntries().size());
        for (int i = 0; i < expected.mediaEntries().size(); i++) {
            assertEquals(expected.mediaEntries().get(i).mediaId(), actual.mediaEntries().get(i).mediaId());
            assertTrue(ItemStack.matches(expected.mediaEntries().get(i).disc(), actual.mediaEntries().get(i).disc()));
        }
    }
}
