package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.item.pad.*;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Calls the production SavedData codec and writes actual compressed world-style NBT. */
class PadDocumentSavedDataRoundTripTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }

    @Test void compressedNbtRoundTripPreservesAuthoritativeDocumentAndUnlocksOnlyTheCopy() throws Exception {
        UUID device = UUID.randomUUID();
        PadDocument original = document(true, List.of(
                new PadMediaEntry(5, PlatformBootstrap.disc("https://example.test/a.mp4", "A", 180)),
                new PadMediaEntry(2, PlatformBootstrap.disc("https://example.test/b.mp3", "B", 90))));
        PadDocumentSavedData source = new PadDocumentSavedData();
        source.put(device, original);
        assertTrue(source.isDirty());
        assertTrue(original.locked(), "world authority must not mutate the supplied locked item document");

        PadDocument restored = roundTrip(source).document(device).orElseThrow();
        assertEquals(original.title(), restored.title());
        assertEquals(original.author(), restored.author());
        assertEquals(original.updatedAtMillis(), restored.updatedAtMillis());
        assertEquals(original.sequence(), restored.sequence());
        assertEquals(original.mapSettings(), restored.mapSettings());
        assertEquals(original.triggerPoints(), restored.triggerPoints());
        assertFalse(restored.locked());
        assertEquals(List.of(2, 5), restored.mediaEntries().stream().map(PadMediaEntry::mediaId).toList());
        for (int i = 0; i < original.mediaEntries().size(); i++) {
            assertTrue(ItemStack.matches(original.mediaEntries().get(i).disc(), restored.mediaEntries().get(i).disc()));
        }
        assertEquals("https://example.test/b.mp3", ItemMusicCD.getSongInfo(restored.media(2).orElseThrow().disc()).songUrl);
    }

    @Test void invalidDeviceKeysDoNotDiscardValidDocuments() {
        UUID valid = UUID.randomUUID();
        CompoundTag documents = new CompoundTag();
        documents.put("invalid uuid", new CompoundTag());
        CompoundTag document = new CompoundTag();
        document.putString("title", "保留文档");
        documents.put(valid.toString(), document);
        CompoundTag root = new CompoundTag();
        root.put("documents", documents);
        PadDocumentSavedData decoded = decode(root);
        assertEquals("保留文档", decoded.document(valid).orElseThrow().title());
        CompoundTag encoded = encode(decoded);
        assertEquals(1, encoded.getCompoundOrEmpty("documents").size());
        assertFalse(encoded.getCompoundOrEmpty("documents").contains("invalid uuid"));
    }

    @Test void legacyMissingFieldsRestoreDefaultsAndVisibleManualTrigger() {
        UUID device = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        CompoundTag point = new CompoundTag();
        point.putString("pointId", pointId.toString());
        point.putString("name", "旧点位");
        ListTag points = new ListTag();
        points.add(point);
        CompoundTag document = new CompoundTag();
        document.put("triggerPoints", points);
        CompoundTag documents = new CompoundTag();
        documents.put(device.toString(), document);
        CompoundTag root = new CompoundTag();
        root.put("documents", documents);
        PadDocument restored = decode(root).document(device).orElseThrow();
        assertEquals(PadMapSettings.DEFAULT, restored.mapSettings());
        assertEquals(0L, restored.sequence());
        assertEquals("", restored.author());
        assertFalse(restored.locked());
        PadTriggerPoint trigger = restored.triggerPoints().getFirst();
        assertEquals(pointId, trigger.pointId());
        assertEquals(PadTriggerMode.MANUAL, trigger.triggerMode());
        assertTrue(trigger.visible());
        assertFalse(trigger.loop());
        assertEquals(1000, trigger.volumePerMille());
    }

    @Test void nonMusicItemsAreFilteredByProductionDecodeWhileMetadataAndTriggersSurvive() throws Exception {
        UUID device = UUID.randomUUID();
        PadDocumentSavedData source = new PadDocumentSavedData();
        PadDocument supplied = document(false, List.of(new PadMediaEntry(1, new ItemStack(Items.STICK))));
        source.put(device, supplied);
        PadDocument restored = roundTrip(source).document(device).orElseThrow();
        assertTrue(restored.mediaEntries().isEmpty());
        assertEquals(supplied.triggerPoints(), restored.triggerPoints());
        assertEquals(supplied.sequence(), restored.sequence());
    }

    @Test void productionDocumentSafetyLimitsSurviveSerialization() throws Exception {
        UUID device = UUID.randomUUID();
        List<PadMediaEntry> media = IntStream.rangeClosed(1, 70)
                .mapToObj(id -> new PadMediaEntry(id, PlatformBootstrap.disc("https://example.test/" + id + ".mp4", "D" + id, id)))
                .toList();
        List<PadTriggerPoint> points = IntStream.range(0, 140)
                .mapToObj(id -> new PadTriggerPoint(UUID.randomUUID(), "P" + id, id, 64, -id, 8, 1,
                        PadTriggerMode.MANUAL, false, 1000, true)).toList();
        PadDocumentSavedData source = new PadDocumentSavedData();
        source.put(device, new PadDocument("容量", "作者", false, 10L, 11L,
                PadMapSettings.DEFAULT, media, points));
        PadDocument restored = roundTrip(source).document(device).orElseThrow();
        assertEquals(64, restored.mediaEntries().size());
        assertEquals(128, restored.triggerPoints().size());
        assertEquals(-1, restored.nextFreeMediaId());
        assertEquals(64, restored.mediaEntries().getLast().mediaId());
    }

    @Test void emptyAndNullWritesDoNotCreateDocumentsOrDirtyTheStore() {
        PadDocumentSavedData source = new PadDocumentSavedData();
        source.put(null, PadDocument.DEFAULT);
        source.put(UUID.randomUUID(), null);
        assertFalse(source.isDirty());
        assertTrue(source.document(null).isEmpty());
        assertEquals(new CompoundTag(), encode(source));
        assertTrue(decode(new CompoundTag()).document(UUID.randomUUID()).isEmpty());
    }

    private PadDocumentSavedData roundTrip(PadDocumentSavedData source) throws Exception {
        Path file = directory.resolve(UUID.randomUUID() + ".dat");
        NbtIo.writeCompressed(encode(source), file);
        return decode(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
    }
    private static CompoundTag encode(PadDocumentSavedData source) {
        return (CompoundTag) PadDocumentSavedData.CODEC.encodeStart(NbtOps.INSTANCE, source).getOrThrow();
    }
    private static PadDocumentSavedData decode(CompoundTag tag) {
        return PadDocumentSavedData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }
    private static PadDocument document(boolean locked, List<PadMediaEntry> media) {
        return new PadDocument("片单 \uD83C\uDFB5", "作者", locked, 123456L, 42L,
                new PadMapSettings("minecraft:the_nether", -42, 19, 128, 2.5F, false), media,
                List.of(new PadTriggerPoint(UUID.randomUUID(), "手动", 1.25, 64.5, -3.75, 13, 5,
                                PadTriggerMode.MANUAL, false, 450, true),
                        new PadTriggerPoint(UUID.randomUUID(), "进入范围", -1, 70, 2, 32, 2,
                                PadTriggerMode.ENTER_RADIUS, true, 810, false)));
    }
}
