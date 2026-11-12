package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Actual BE persistence and ItemStack codecs, including real compressed NBT IO. No game is launched. */
class MediaDevicePersistenceProductionTest {
    private static final BlockPos POS = new BlockPos(4, 70, -8);
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }

    @Test void turntableDiscConfigurationAndPausedProgressSurviveCompressedNbt() throws Exception {
        UUID source = UUID.randomUUID(); UUID owner = UUID.randomUUID();
        CompoundTag input = new CompoundTag();
        input.store("Disc", ItemStack.OPTIONAL_CODEC, PlatformBootstrap.disc("https://example.test/a.mp3", "曲目A", 40));
        input.putString("RawUrl", "https://example.test/a.mp3"); input.putString("PlayUrl", "https://example.test/signed.mp3");
        input.putString("SongName", "曲目A"); input.putInt("DurationSeconds", 40); input.putBoolean("Playing", false);
        input.putLong("ElapsedTicks", 137L); input.putInt("SeekGeneration", 7);
        input.putBoolean("RepeatOne", true); input.putString("RedstoneMode", "pulse_toggle");
        input.putString("ExtractionMode", "always"); input.putBoolean("PlaybackCompleted", true);
        input.putInt("VolumePerMille", 432); input.putString("PlaybackSourceId", source.toString());
        input.putString("PlaybackOwner", owner.toString());
        ModernTurntableBlockEntity turntable = load(PlatformBootstrap.turntable(POS), input);
        CompoundTag saved = onDisk(turntable.saveWithoutMetadata(PlatformBootstrap.registries()));
        ModernTurntableBlockEntity restored = load(PlatformBootstrap.turntable(POS), saved);
        assertEquals(saved, restored.saveWithoutMetadata(PlatformBootstrap.registries()));
        assertEquals(source, restored.getPlaybackSourceId().value()); assertEquals("曲目A", restored.getSongName());
        assertEquals("曲目A", ItemMusicCD.getSongInfo(restored.getDisc()).songName);
        assertEquals(6850, restored.getPlaybackElapsedMillis()); assertEquals(40, restored.getDurationSeconds());
        assertEquals(432, restored.getVolumePerMille()); assertTrue(restored.isRepeatOne());
        assertEquals(TurntableRedstoneMode.PULSE_TOGGLE, restored.getRedstoneMode());
        assertEquals(TurntableExtractionMode.ALWAYS, restored.getExtractionMode());
        assertEquals(owner.toString(), saved.getString("PlaybackOwner").orElseThrow());
    }

    @Test void turntableLegacyElapsedSecondsMigratesAndClampsNearEnd() {
        CompoundTag input = new CompoundTag(); input.putInt("DurationSeconds", 10); input.putInt("ElapsedSeconds", 99);
        ModernTurntableBlockEntity restored = load(PlatformBootstrap.turntable(POS), input);
        CompoundTag encoded = restored.saveWithoutMetadata(PlatformBootstrap.registries());
        assertEquals(199L, encoded.getLong("ElapsedTicks").orElseThrow());
        assertEquals(9950, restored.getPlaybackElapsedMillis());
        assertEquals(9, encoded.getInt("ElapsedSeconds").orElseThrow());
    }

    @Test void turntableMalformedIdentityModesVolumeAndSeekGenerationRecoverToDefaults() {
        CompoundTag input = new CompoundTag(); input.putString("PlaybackSourceId", "bad"); input.putString("PlaybackOwner", "bad");
        input.putString("RedstoneMode", "future-mode"); input.putString("ExtractionMode", "future-mode");
        input.putInt("VolumePerMille", -99); input.putInt("SeekGeneration", -5); input.putLong("ElapsedTicks", -9);
        ModernTurntableBlockEntity loaded = load(PlatformBootstrap.turntable(POS), input);
        assertNotNull(loaded.getPlaybackSourceId().value()); assertEquals(0, loaded.getVolumePerMille());
        assertEquals(TurntableRedstoneMode.IGNORE, loaded.getRedstoneMode());
        assertEquals(TurntableExtractionMode.AFTER_PLAYBACK, loaded.getExtractionMode());
        CompoundTag saved = loaded.saveWithoutMetadata(PlatformBootstrap.registries());
        assertEquals(0, saved.getInt("SeekGeneration").orElseThrow()); assertFalse(saved.contains("PlaybackOwner"));
        assertEquals(0, saved.getLong("ElapsedTicks").orElseThrow());
    }

    @Test void turntableRealSetterAndRemovalPreserveDiscAndStopPlaybackState() {
        ModernTurntableBlockEntity turntable = PlatformBootstrap.turntable(POS);
        ItemStack disc = PlatformBootstrap.disc("https://example.test/a.mp3", "a", 40);
        turntable.setDisc(disc); turntable.toggleRepeatOne(); turntable.cycleExtractionMode(); turntable.setVolumePerMille(1500);
        ModernTurntableBlockEntity restored = load(PlatformBootstrap.turntable(POS), turntable.saveWithoutMetadata(PlatformBootstrap.registries()));
        assertTrue(restored.hasDisc()); assertTrue(restored.isRepeatOne()); assertEquals(1000, restored.getVolumePerMille());
        assertEquals(TurntableExtractionMode.ALWAYS, restored.getExtractionMode());
        ItemStack removed = restored.removeDiscForBlockRemoval(); assertEquals("a", ItemMusicCD.getSongInfo(removed).songName);
        assertFalse(restored.hasDisc()); assertFalse(restored.isPlaying()); assertFalse(restored.hasPlaybackData());
    }

    @Test void liveStreamerAllPersistentFieldsSurviveCompressedNbt() throws Exception {
        UUID source = UUID.randomUUID(); UUID owner = UUID.randomUUID(); CompoundTag input = new CompoundTag();
        input.putString("RoomId", "12345"); input.putBoolean("Playing", true); input.putBoolean("AutoResumeRequested", true);
        input.putLong("StartedGameTime", 772); input.putInt("VolumePerMille", 321);
        input.putString("PlaybackSourceId", source.toString()); input.putString("PlaybackOwner", owner.toString());
        LiveStreamerBlockEntity live = load(PlatformBootstrap.liveStreamer(POS), input);
        CompoundTag saved = onDisk(live.saveWithoutMetadata(PlatformBootstrap.registries()));
        LiveStreamerBlockEntity restored = load(PlatformBootstrap.liveStreamer(POS), saved);
        assertEquals(saved, restored.saveWithoutMetadata(PlatformBootstrap.registries()));
        assertEquals("12345", restored.getRoomId()); assertTrue(restored.isPlaying()); assertFalse(restored.isWaitingForLive());
        assertEquals(321, restored.getVolumePerMille()); assertEquals(source, restored.getPlaybackSourceId().value());
        assertEquals(-1, restored.getPlaybackElapsedMillis()); assertEquals(owner.toString(), saved.getString("PlaybackOwner").orElseThrow());
    }

    @Test void liveStreamerLegacyPlayingStateDefaultsToAutoResumeAndNormalizesRoomUrl() {
        CompoundTag input = new CompoundTag(); input.putString("RoomId", "https://live.bilibili.com/12345"); input.putBoolean("Playing", true);
        LiveStreamerBlockEntity restored = load(PlatformBootstrap.liveStreamer(POS), input);
        assertEquals("12345", restored.getRoomId()); assertTrue(restored.isPlaying());
        assertTrue(restored.saveWithoutMetadata(PlatformBootstrap.registries()).getBoolean("AutoResumeRequested").orElseThrow());
    }

    @Test void liveStreamerInvalidRoomDisablesPlayingAndAutoResumeAndRepairsIdentity() {
        CompoundTag input = new CompoundTag(); input.putString("RoomId", "not-a-room"); input.putBoolean("Playing", true);
        input.putBoolean("AutoResumeRequested", true); input.putString("PlaybackSourceId", "broken");
        input.putString("PlaybackOwner", "broken"); input.putInt("VolumePerMille", 9000);
        LiveStreamerBlockEntity restored = load(PlatformBootstrap.liveStreamer(POS), input);
        assertEquals("", restored.getRoomId()); assertFalse(restored.isPlaying()); assertFalse(restored.isWaitingForLive());
        assertEquals(1000, restored.getVolumePerMille()); assertNotNull(restored.getPlaybackSourceId().value());
        assertFalse(restored.saveWithoutMetadata(PlatformBootstrap.registries()).contains("PlaybackOwner"));
    }

    @Test void liveStreamerRoomChangeStopsWaitingAndInvalidInputLeavesCurrentRoomUntouched() {
        CompoundTag input = new CompoundTag(); input.putString("RoomId", "12345"); input.putBoolean("AutoResumeRequested", true);
        LiveStreamerBlockEntity live = load(PlatformBootstrap.liveStreamer(POS), input);
        assertTrue(live.isWaitingForLive()); assertFalse(live.setRoomId(null, "invalid", null));
        assertEquals("12345", live.getRoomId()); assertTrue(live.isWaitingForLive());
        assertTrue(live.setRoomId(null, "https://live.bilibili.com/67890", null));
        assertEquals("67890", live.getRoomId()); assertFalse(live.isWaitingForLive()); assertFalse(live.isPlaying());
        live.setVolumePerMille(-1); assertEquals(0, live.getVolumePerMille());
    }

    private <T extends BlockEntity> T load(T entity, CompoundTag input) {
        ProblemReporter.Collector reporter = new ProblemReporter.Collector();
        entity.loadCustomOnly(TagValueInput.create(reporter, PlatformBootstrap.registries(), input));
        assertTrue(reporter.isEmpty(), "actual NBT reader reported errors"); return entity;
    }
    private CompoundTag onDisk(CompoundTag input) throws Exception {
        Path file = directory.resolve(UUID.randomUUID() + ".dat"); NbtIo.writeCompressed(input, file);
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }
}
