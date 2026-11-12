package com.zhongbai233.net_music_can_play_bili.item.pad;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real PadItem document writes/reads and compressed CustomData, without a game process. */
class PadItemDocumentRoundTripTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }

    @Test void itemNbtRoundTripPreservesIdentityLockMediaIdsAndAllTriggerFields() throws Exception {
        ItemStack pad = PlatformBootstrap.pad();
        UUID device = PadItem.getOrCreateDeviceId(pad);
        ItemStack disc = PlatformBootstrap.disc("https://example.test/video.mp4", "片段", 180);
        PadTriggerPoint point = new PadTriggerPoint(UUID.randomUUID(), "入口", -1.5, 64.25, 5.75,
                24, 7, PadTriggerMode.ENTER_RADIUS, true, 640, false);
        PadDocument original = new PadDocument("Pad", "作者", true, 123456L, 17L,
                PadMapSettings.DEFAULT, List.of(new PadMediaEntry(7, disc)), List.of(point));
        PadItem.writeDocument(pad, original);
        ItemStack restored = restoreCustomData(pad);
        PadDocument decoded = PadItem.readLegacyDocument(restored);
        assertNotNull(decoded);
        assertEquals(device, PadItem.readDeviceId(restored));
        assertEquals(original.title(), decoded.title());
        assertEquals(original.author(), decoded.author());
        assertEquals(original.locked(), decoded.locked());
        assertEquals(original.updatedAtMillis(), decoded.updatedAtMillis());
        assertEquals(original.sequence(), decoded.sequence());
        assertEquals(original.triggerPoints(), decoded.triggerPoints());
        assertEquals(List.of(7), decoded.mediaEntries().stream().map(PadMediaEntry::mediaId).toList());
        assertTrue(ItemStack.matches(disc, decoded.media(7).orElseThrow().disc()));
    }

    @Test void legacyMissingVisibleAndInvalidModeUseProductionDefaults() throws Exception {
        ItemStack pad = PlatformBootstrap.pad();
        CompoundTag root = new CompoundTag();
        root.putString("pad_title", "旧文档");
        CompoundTag point = new CompoundTag();
        UUID id = UUID.randomUUID();
        point.putString("id", id.toString());
        point.putString("mode", "future-mode");
        ListTag points = new ListTag();
        points.add(point);
        points.add(StringTag.valueOf("broken non-compound trigger"));
        root.put("pad_trigger_points", points);
        pad.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        PadDocument restored = PadItem.readDocument(restoreCustomData(pad));
        assertEquals(1, restored.triggerPoints().size());
        PadTriggerPoint trigger = restored.triggerPoints().getFirst();
        assertEquals(id, trigger.pointId());
        assertTrue(trigger.visible());
        assertEquals(PadTriggerMode.MANUAL, trigger.triggerMode());
        assertEquals(8, trigger.radiusBlocks());
        assertEquals(1000, trigger.volumePerMille());
    }

    @Test void stripDocumentPreservesDeviceIdentityLockAndUnrelatedCustomData() {
        ItemStack pad = PlatformBootstrap.pad();
        UUID id = PadItem.getOrCreateDeviceId(pad);
        PadItem.writeDocument(pad, new PadDocument("标题", "作者", true, 50L, 3L,
                PadMapSettings.DEFAULT, List.of(), List.of()));
        pad.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                data -> data.update(tag -> tag.putString("other_mod_data", "keep")));
        PadItem.stripDocumentData(pad);
        assertNull(PadItem.readLegacyDocument(pad));
        assertEquals(id, PadItem.readDeviceId(pad));
        assertTrue(PadItem.readDocument(pad).locked());
        assertEquals("keep", pad.get(DataComponents.CUSTOM_DATA).copyTag().getString("other_mod_data").orElseThrow());
    }

    @Test void addRemoveRoundTripConsumesOneDiscReusesFreeIdAndProtectsLockedDocument() throws Exception {
        ItemStack pad = PlatformBootstrap.pad();
        ItemStack disc = PlatformBootstrap.disc("https://example.test/music.mp3", "音轨", 45);
        disc.setCount(3);
        assertTrue(PadItem.addDisc(pad, disc));
        assertEquals(2, disc.getCount());
        ItemStack restored = restoreCustomData(pad);
        PadDocument document = PadItem.readDocument(restored);
        assertEquals(1, document.sequence());
        assertEquals(1, document.mediaEntries().getFirst().mediaId());
        assertEquals(1, document.mediaEntries().getFirst().disc().getCount());
        ItemStack removed = PadItem.removeLastDisc(restored);
        assertEquals("https://example.test/music.mp3", ItemMusicCD.getSongInfo(removed).songUrl);
        assertEquals(1, removed.getCount());
        assertEquals(2, PadItem.readDocument(restored).sequence());
        assertTrue(PadItem.addDisc(restored, disc));
        assertEquals(1, PadItem.readDocument(restored).mediaEntries().getFirst().mediaId());
        PadItem.writeLocked(restored, true);
        CompoundTag before = restored.get(DataComponents.CUSTOM_DATA).copyTag();
        assertFalse(PadItem.addDisc(restored, disc));
        assertTrue(PadItem.removeLastDisc(restored).isEmpty());
        assertEquals(before, restored.get(DataComponents.CUSTOM_DATA).copyTag());
        assertEquals(1, disc.getCount());
    }

    @Test void fullDocumentRejectsInsertionWithoutConsumingOrChangingNbt() {
        ItemStack pad = PlatformBootstrap.pad();
        ItemStack disc = PlatformBootstrap.disc("https://example.test/a.mp4", "A", 30);
        List<PadMediaEntry> entries = IntStream.rangeClosed(1, 64)
                .mapToObj(id -> new PadMediaEntry(id, disc)).toList();
        PadItem.writeDocument(pad, new PadDocument("满", "", false, 1L, 2L,
                PadMapSettings.DEFAULT, entries, List.of()));
        CompoundTag before = pad.get(DataComponents.CUSTOM_DATA).copyTag();
        assertFalse(PadItem.addDisc(pad, disc));
        assertEquals(1, disc.getCount());
        assertEquals(before, pad.get(DataComponents.CUSTOM_DATA).copyTag());
        assertEquals(64, PadItem.readDocument(pad).mediaEntries().size());
    }

    @Test void biliDiscInsertionPersistsStableSelectionAndKeepsOriginalInputMetadata() throws Exception {
        ItemStack pad = PlatformBootstrap.pad();
        ItemStack supplied = PlatformBootstrap.disc("https://www.bilibili.com/video/BV1234567890?p=2", "第二集", 120);
        supplied.setCount(2);
        assertTrue(PadItem.addDisc(pad, supplied));
        ItemStack persistedDisc = PadItem.readDocument(restoreCustomData(pad)).mediaEntries().getFirst().disc();
        ItemMusicCD.SongInfo persisted = ItemMusicCD.getSongInfo(persistedDisc);
        assertEquals("BV1234567890|p=2", persisted.songUrl);
        assertEquals("第二集", persisted.songName);
        assertEquals(120, persisted.songTime);
        assertEquals(1, supplied.getCount());
        assertEquals("https://www.bilibili.com/video/BV1234567890?p=2", ItemMusicCD.getSongInfo(supplied).songUrl);
    }

    @Test void invalidDiscAndWrongCarrierProduceNoDocumentWriteOrConsumption() {
        ItemStack pad = PlatformBootstrap.pad();
        ItemStack ordinary = new ItemStack(Items.STICK, 3);
        assertFalse(PadItem.addDisc(pad, ordinary));
        assertNull(pad.get(DataComponents.CUSTOM_DATA));
        assertEquals(3, ordinary.getCount());
        PadItem.writeDocument(ordinary, PadDocument.DEFAULT);
        assertNull(ordinary.get(DataComponents.CUSTOM_DATA));
        assertTrue(PadItem.removeLastDisc(ordinary).isEmpty());
    }

    private ItemStack restoreCustomData(ItemStack original) throws Exception {
        Path file = directory.resolve(UUID.randomUUID() + ".dat");
        NbtIo.writeCompressed(original.get(DataComponents.CUSTOM_DATA).copyTag(), file);
        ItemStack restored = PlatformBootstrap.pad();
        restored.set(DataComponents.CUSTOM_DATA, CustomData.of(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())));
        return restored;
    }
}
