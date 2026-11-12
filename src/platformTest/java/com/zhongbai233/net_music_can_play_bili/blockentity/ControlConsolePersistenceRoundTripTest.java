package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual block entity save/load implementation, including migration and future data. */
class ControlConsolePersistenceRoundTripTest {
    private static final BlockPos POSITION = new BlockPos(12, 64, -30);
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }

    @Test void committedEditorDocumentSurvivesCompressedNbtWithAllTransformAndMediaFields() throws Exception {
        ControlConsoleBlockEntity console = console();
        UUID owner = UUID.randomUUID();
        assertTrue(console.claimIfUnowned(owner));
        ControlConsoleElement element = new ControlConsoleElement(UUID.randomUUID(),
                ControlConsoleElement.Type.SUBTITLE, "高级字幕", 2.5F, 0.25F, -0.5F, 1.5F, 2.0F,
                35.0F, -15.0F, 7.0F, "FIXED", "字幕内容", true, true,
                1.25F, 0xFF102030, 0.65F, 3, 48.0F, true,
                0xFF304050, 0x60000000, ControlConsoleElement.Alignment.LEFT, 5.5F, true,
                false, true, 1.2F, 0.8F, 1.5F, 0.2F, -0.4F, 0.7F, 0.15F, -0.25F, 0.55F);
        assertTrue(console.replaceDocument(0L, "持久化场景", 100, 100, 100, List.of(element)));
        console.linkTo("minecraft:the_nether", new BlockPos(-9, 72, 15),
                ControlConsoleDocument.SourceKind.LIVE_STREAMER);
        ControlConsoleDocument expected = console.document();
        ControlConsoleBlockEntity restored = restore(onDisk(console.saveWithoutMetadata(PlatformBootstrap.registries())));
        assertEquals(expected, restored.document(), "every document field is compared by the production record");
        assertEquals(2L, restored.documentRevision());
        assertEquals(expected.consoleId(), restored.document().consoleId());
        assertEquals(element.elementId(), restored.document().elements().getFirst().elementId());
        assertFalse(restored.isDocumentReadOnly());
    }

    @Test void savedAclAndStableIdentitySurviveRealLoadSaveLoad() throws Exception {
        CompoundTag encoded = console().saveWithoutMetadata(PlatformBootstrap.registries());
        CompoundTag document = encoded.getCompoundOrEmpty("Document");
        UUID consoleId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        document.putString("ConsoleId", consoleId.toString());
        document.putString("OwnerId", owner.toString());
        document.putString("AccessMode", "TRUSTED");
        CompoundTag entry = new CompoundTag();
        entry.putString("PlayerId", trusted.toString());
        ListTag trustedPlayers = new ListTag();
        trustedPlayers.add(entry);
        document.put("TrustedPlayers", trustedPlayers);
        document.putLong("DocumentRevision", 73L);
        ControlConsoleBlockEntity loaded = restore(onDisk(encoded));
        ControlConsoleBlockEntity restored = restore(onDisk(loaded.saveWithoutMetadata(PlatformBootstrap.registries())));
        assertEquals(loaded.document(), restored.document());
        assertEquals(consoleId, restored.document().consoleId());
        assertEquals(73L, restored.documentRevision());
        assertTrue(restored.document().canEdit(owner, false));
        assertTrue(restored.document().canEdit(trusted, false));
        assertFalse(restored.document().canEdit(UUID.randomUUID(), false));
    }

    @Test void futureSchemaDocumentIsReadOnlyAndPreservedWithoutLosingUnknownFields() throws Exception {
        CompoundTag futureDocument = new CompoundTag();
        futureDocument.putString("FutureProperty", "opaque");
        futureDocument.putLong("DocumentRevision", 900L);
        CompoundTag nested = new CompoundTag();
        nested.putIntArray("new-transform", new int[]{1, 2, 3});
        futureDocument.put("FutureStructure", nested);
        CompoundTag input = new CompoundTag();
        input.putInt("SchemaVersion", ControlConsoleDocument.CURRENT_SCHEMA_VERSION + 10);
        input.put("Document", futureDocument);
        ControlConsoleBlockEntity restored = restore(onDisk(input));
        assertTrue(restored.isDocumentReadOnly());
        assertFalse(restored.claimIfUnowned(UUID.randomUUID()));
        assertEquals(ControlConsoleBlockEntity.ReplaceResult.READ_ONLY,
                restored.replaceDocument(UUID.randomUUID(), 0L, "edit", 100, 100, 100, List.of()));
        CompoundTag saved = onDisk(restored.saveWithoutMetadata(PlatformBootstrap.registries()));
        assertEquals(input.getInt("SchemaVersion"), saved.getInt("SchemaVersion"));
        assertEquals(futureDocument, saved.getCompoundOrEmpty("Document"));
    }

    @Test void legacySchemaMigrationGeneratesStableIdsAndMigratesOnlyLegacyDefaultRange() {
        CompoundTag input = legacyV4();
        ControlConsoleBlockEntity first = restore(input);
        ControlConsoleBlockEntity second = restore(input.copy());
        assertEquals(first.document().consoleId(), second.document().consoleId());
        assertEquals(first.document().elements().getFirst().elementId(), second.document().elements().getFirst().elementId());
        assertEquals(ControlConsoleDocument.CURRENT_SCHEMA_VERSION, first.documentSchemaVersion());
        assertEquals(64.0, first.document().hardRangeX());
        assertEquals(32.0, first.document().hardRangeY());
        assertEquals(64.0, first.document().hardRangeZ());
        ControlConsoleElement element = first.document().elements().getFirst();
        assertEquals(1.0F, element.scaleX());
        assertEquals(0.0F, element.pivotX());
        assertEquals(0.0F, element.skewXByY());
        assertEquals(1.0F, element.brightness());
        input.getCompoundOrEmpty("Document").putDouble("HardRangeY", 6.0);
        ControlConsoleBlockEntity customized = restore(input);
        assertEquals(8.0, customized.document().hardRangeX());
        assertEquals(6.0, customized.document().hardRangeY());
    }

    @Test void oneCorruptElementDoesNotDiscardValidSavedScene() {
        CompoundTag input = legacyV4();
        CompoundTag document = input.getCompoundOrEmpty("Document");
        document.putString("DisplayName", "保留场景");
        ListTag elements = document.getListOrEmpty("Elements");
        CompoundTag invalid = new CompoundTag();
        invalid.putString("Type", "SCREEN");
        invalid.putString("Name", "invalid scale");
        invalid.putFloat("ScaleX", -1.0F);
        elements.add(invalid);
        ControlConsoleBlockEntity restored = restore(input);
        assertEquals("保留场景", restored.document().displayName());
        assertEquals(1, restored.document().elements().size());
        assertEquals("旧屏幕", restored.document().elements().getFirst().name());
    }

    @Test void staleCommitAfterReloadIsRejectedWithoutChangingPersistedDocument() throws Exception {
        ControlConsoleBlockEntity console = console();
        UUID operation = UUID.randomUUID();
        assertEquals(ControlConsoleBlockEntity.ReplaceResult.APPLIED,
                console.replaceDocument(operation, 0L, "新文档", 100, 100, 100, List.of()));
        assertEquals(ControlConsoleBlockEntity.ReplaceResult.DUPLICATE,
                console.replaceDocument(operation, 0L, "重复", 100, 100, 100, List.of()));
        ControlConsoleBlockEntity restored = restore(onDisk(console.saveWithoutMetadata(PlatformBootstrap.registries())));
        ControlConsoleDocument before = restored.document();
        assertEquals(ControlConsoleBlockEntity.ReplaceResult.CONFLICT,
                restored.replaceDocument(UUID.randomUUID(), 0L, "过时提交", 100, 100, 100, List.of()));
        assertEquals(before, restored.document());
        assertEquals(console.saveWithoutMetadata(PlatformBootstrap.registries()),
                restored.saveWithoutMetadata(PlatformBootstrap.registries()));
    }

    private static ControlConsoleBlockEntity console() { return PlatformBootstrap.console(POSITION); }
    private static ControlConsoleBlockEntity restore(CompoundTag tag) {
        ControlConsoleBlockEntity console = console();
        ProblemReporter.Collector problems = new ProblemReporter.Collector();
        console.loadCustomOnly(TagValueInput.create(problems, PlatformBootstrap.registries(), tag));
        assertTrue(problems.isEmpty(), "actual NBT reader reported a serialization problem");
        return console;
    }
    private CompoundTag onDisk(CompoundTag tag) throws Exception {
        Path file = directory.resolve(UUID.randomUUID() + ".dat");
        NbtIo.writeCompressed(tag, file);
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }
    private static CompoundTag legacyV4() {
        CompoundTag document = new CompoundTag();
        document.putString("DisplayName", "旧文档");
        document.putDouble("HardRangeX", 8.0);
        document.putDouble("HardRangeY", 4.0);
        document.putDouble("HardRangeZ", 8.0);
        CompoundTag element = new CompoundTag();
        element.putString("Type", "SCREEN");
        element.putString("Name", "旧屏幕");
        ListTag elements = new ListTag();
        elements.add(element);
        document.put("Elements", elements);
        CompoundTag root = new CompoundTag();
        root.putInt("SchemaVersion", 4);
        root.put("Document", document);
        return root;
    }
}
