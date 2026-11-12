package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.datafixers.DataFixer;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real CODEC/NBT/storage IO. The level boundary and unrelated data fixer are test doubles. */
class MP4SavedDataIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }
    @BeforeEach void clearRuntime() { MP4DeviceStateStore.clearRuntime(); }

    @Test void progressEntryAndDeviceConfigurationSurviveNbtCodecRoundTrip() {
        UUID id = UUID.randomUUID();
        MP4PlaybackSavedData data = new MP4PlaybackSavedData();
        data.record(id, 1, 2345, 30, 456, "session-real", true);
        data.putDevice(id, entry(1, 2345, 30));
        MP4PlaybackSavedData restored = roundTrip(data);
        assertEquals(data.get(id).orElseThrow(), restored.get(id).orElseThrow());
        var device = restored.device(id).orElseThrow();
        assertEquals(data.device(id).orElseThrow().state(), device.state());
        assertEquals(2345, device.elapsedMillis()); assertEquals(30, device.durationSeconds());
        assertEquals("session-real", device.sessionId()); assertEquals(9, device.updatedGameTime());
        assertTrue(device.queue().isEmpty(), "queue remains item content, rather than a duplicate in world SavedData");
        assertEquals(2345, restored.elapsedMillis(id, 1, 99));
        assertEquals(99, restored.elapsedMillis(id, 0, 99));
    }

    @Test void invalidExternalUuidKeysAreIgnoredWithoutLosingValidRecords() {
        UUID id = UUID.randomUUID();
        MP4PlaybackSavedData data = new MP4PlaybackSavedData(); data.record(id, 0, 12, 10, 700, "", false);
        CompoundTag tag = (CompoundTag) MP4PlaybackSavedData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        CompoundTag entries = tag.getCompoundOrEmpty("entries"); entries.put("invalid-uuid", new CompoundTag());
        tag.put("entries", entries);
        CompoundTag devices = new CompoundTag(); devices.put("also-invalid", new CompoundTag()); tag.put("devices", devices);
        MP4PlaybackSavedData restored = MP4PlaybackSavedData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertEquals(12, restored.get(id).orElseThrow().elapsedMillis());
        CompoundTag canonical = (CompoundTag) MP4PlaybackSavedData.CODEC.encodeStart(NbtOps.INSTANCE, restored).getOrThrow();
        assertFalse(canonical.getCompoundOrEmpty("entries").contains("invalid-uuid"));
        assertFalse(canonical.getCompoundOrEmpty("devices").contains("also-invalid"));
    }

    @Test void emptyLegacySavedDataLoadsDefaults() {
        MP4PlaybackSavedData restored = MP4PlaybackSavedData.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        assertTrue(restored.get(UUID.randomUUID()).isEmpty());
        assertTrue(restored.device(UUID.randomUUID()).isEmpty());
        assertEquals(91, restored.elapsedMillis(UUID.randomUUID(), 1, 91));
    }

    @Test void progressEntryNormalizationAndDirtyClearAreProductionOperations() {
        UUID id = UUID.randomUUID(); MP4PlaybackSavedData data = new MP4PlaybackSavedData();
        assertFalse(data.isDirty()); data.put(null, new MP4PlaybackSavedData.Entry(0, 0, 0, 700, "", false));
        assertFalse(data.isDirty());
        data.record(id, -5, 99999, 10, 5000, null, true);
        assertTrue(data.isDirty());
        var normalized = data.get(id).orElseThrow();
        assertEquals(0, normalized.queueIndex()); assertEquals(9950, normalized.elapsedMillis());
        assertEquals(1000, normalized.volumePerMille()); assertEquals("", normalized.sessionId());
        data.putDevice(id, entry(0, 0, 30));
        data.setDirty(false); data.clear(id);
        assertTrue(data.isDirty()); assertTrue(data.get(id).isEmpty()); assertTrue(data.device(id).isEmpty());
        data.setDirty(false); data.clear(id); assertFalse(data.isDirty());
    }

    @Test void actualStorageWritesCompressedFileAndReloadsDeviceAfterRuntimeClear() throws Exception {
        UUID id = UUID.randomUUID();
        ItemStack device = PlatformBootstrap.mp4(); MP4Item.writeDeviceId(device, id);
        assertTrue(MP4Item.addDisc(device, PlatformBootstrap.disc("https://example.test/a.mp3", "a", 30)));
        assertTrue(MP4Item.addDisc(device, PlatformBootstrap.disc("https://example.test/b.mp3", "b", 40)));
        DataFixer fixer = noMigrationFixer();
        try (SavedDataStorage storage = new SavedDataStorage(null, directory, fixer, PlatformBootstrap.registries())) {
            ServerLevel level = mock(ServerLevel.class); when(level.getDataStorage()).thenReturn(storage);
            MP4DeviceStateStore.getOrCreate(level, id, device);
            MP4DeviceStateStore.recordPlayback(level, id, 1, 5000, 40, 700, "session-persisted", true);
            MP4PlaybackSavedData.get(level).record(id, 1, 5000, 40, 700, "session-persisted", true);
            MP4DeviceStateStore.flush(level);
            storage.saveAndJoin();
            assertTrue(Files.exists(directory.resolve("net_music_can_play_bili/mp4_playback.dat")));
        }
        MP4DeviceStateStore.clearRuntime();
        try (SavedDataStorage reloadedStorage = new SavedDataStorage(null, directory, fixer, PlatformBootstrap.registries())) {
            ServerLevel level = mock(ServerLevel.class); when(level.getDataStorage()).thenReturn(reloadedStorage);
            var restored = MP4DeviceStateStore.getOrCreate(level, id, device);
            assertEquals(2, restored.queue().size()); assertEquals(1, restored.state().selectedQueueIndex());
            assertEquals(5000, restored.elapsedMillis()); assertEquals(40, restored.durationSeconds());
            assertEquals("session-persisted", restored.sessionId()); assertTrue(restored.state().playing());
            assertEquals(5000, MP4PlaybackSavedData.get(level).elapsedMillis(id, 1, 0));
        }
    }

    @Test void savedAuthorityRejoinsCurrentPhysicalQueueRatherThanOldWorldCopy() {
        UUID id = UUID.randomUUID(); MP4PlaybackSavedData data = roundTrip(dataWith(id, entry(1, 1000, 30)));
        SavedDataStorage storage = mock(SavedDataStorage.class);
        when(storage.computeIfAbsent(MP4PlaybackSavedData.TYPE)).thenReturn(data);
        ServerLevel level = mock(ServerLevel.class); when(level.getDataStorage()).thenReturn(storage);
        ItemStack currentCarrier = PlatformBootstrap.mp4();
        assertTrue(MP4Item.addDisc(currentCarrier, PlatformBootstrap.disc("https://example.test/replaced.mp3", "replacement", 20)));
        var restored = MP4DeviceStateStore.getOrCreate(level, id, currentCarrier);
        assertEquals(1, restored.queue().size()); assertEquals(0, restored.state().selectedQueueIndex());
        assertEquals("https://example.test/replaced.mp3", com.github.tartaricacid.netmusic.item.ItemMusicCD.getSongInfo(restored.queue().getFirst()).songUrl);
        assertEquals(1000, restored.elapsedMillis()); assertEquals("session-real", restored.sessionId());
    }

    private MP4PlaybackSavedData roundTrip(MP4PlaybackSavedData value) {
        return MP4PlaybackSavedData.CODEC.parse(NbtOps.INSTANCE,
                MP4PlaybackSavedData.CODEC.encodeStart(NbtOps.INSTANCE, value).getOrThrow()).getOrThrow();
    }
    private MP4PlaybackSavedData dataWith(UUID id, MP4DeviceStateStore.DeviceEntry entry) {
        MP4PlaybackSavedData data = new MP4PlaybackSavedData(); data.putDevice(id, entry); return data;
    }
    private MP4DeviceStateStore.DeviceEntry entry(int index, long elapsed, int duration) {
        MP4Item.State state = new MP4Item.State(true, true, false, true, 7, index, 1, 456, 2,
                true, true, 1, true, 123, true);
        return new MP4DeviceStateStore.DeviceEntry(state, List.of(PlatformBootstrap.disc("https://example.test/a.mp3", 30),
                PlatformBootstrap.disc("https://example.test/b.mp3", 40)), elapsed, duration, "session-real", 9);
    }
    private DataFixer noMigrationFixer() {
        DataFixer fixer = mock(DataFixer.class);
        when(fixer.update(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        return fixer;
    }
}
