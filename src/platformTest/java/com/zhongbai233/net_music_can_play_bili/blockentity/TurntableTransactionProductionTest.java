package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real BE-owned handler and NeoForge snapshot transactions, no capability registration or game runtime. */
class TurntableTransactionProductionTest {
    private static final BlockPos POS = BlockPos.ZERO;
    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }
    @Test void rootCommitAcceptsOnlyOneDiscAndPersistedContentsMatchActualResource() {
        var turntable = PlatformBootstrap.turntable(POS); var disc = disc(); var resource = ItemResource.of(disc);
        try (var tx = Transaction.openRoot()) {
            assertEquals(1, turntable.getItemHandler().insert(0, resource, 9, tx)); tx.commit();
        }
        assertTrue(turntable.hasDisc()); assertEquals(1, turntable.getDisc().getCount());
        assertTrue(resource.matches(turntable.getDisc())); assertEquals(1, turntable.getItemHandler().getAmountAsLong(0));
        assertEquals(1, turntable.getItemHandler().getCapacityAsLong(0, resource));
    }
    @Test void rootRollbackRestoresEmptyDiscWithoutCommitSideEffects() {
        var turntable = PlatformBootstrap.turntable(POS);
        var before = turntable.saveWithoutMetadata(PlatformBootstrap.registries());
        try (var tx = Transaction.openRoot()) { assertEquals(1, turntable.getItemHandler().insert(0, ItemResource.of(disc()), 1, tx)); assertTrue(turntable.hasDisc()); }
        assertFalse(turntable.hasDisc()); assertEquals(before, turntable.saveWithoutMetadata(PlatformBootstrap.registries()));
    }
    @Test void nestedCommitStillRollsBackWhenRootDoesNotCommit() {
        var turntable = PlatformBootstrap.turntable(POS);
        try (var root = Transaction.openRoot()) {
            try (var child = Transaction.open(root)) { assertEquals(1, turntable.getItemHandler().insert(0, ItemResource.of(disc()), 1, child)); child.commit(); }
            assertTrue(turntable.hasDisc());
        }
        assertFalse(turntable.hasDisc());
    }
    @Test void invalidResourceAndSecondDiscAreRejectedWithNoContentChange() {
        var turntable = PlatformBootstrap.turntable(POS); var handler = turntable.getItemHandler();
        try (var tx = Transaction.openRoot()) { assertEquals(0, handler.insert(0, ItemResource.of(Items.STONE), 1, tx)); tx.commit(); }
        assertFalse(turntable.hasDisc()); turntable.setDisc(disc());
        try (var tx = Transaction.openRoot()) { assertEquals(0, handler.insert(0, ItemResource.of(disc()), 1, tx)); tx.commit(); }
        assertEquals(1, turntable.getDisc().getCount());
    }
    @Test void defaultExtractionIsBlockedUntilPlaybackCompleted() {
        var turntable = PlatformBootstrap.turntable(POS); turntable.setDisc(disc()); var resource = ItemResource.of(turntable.getDisc());
        try (var tx = Transaction.openRoot()) { assertEquals(0, turntable.getItemHandler().extract(0, resource, 1, tx)); tx.commit(); }
        assertTrue(turntable.hasDisc());
        CompoundTag completed = turntable.saveWithoutMetadata(PlatformBootstrap.registries()); completed.putBoolean("PlaybackCompleted", true);
        turntable.loadCustomOnly(TagValueInput.create(new ProblemReporter.Collector(), PlatformBootstrap.registries(), completed));
        try (var tx = Transaction.openRoot()) { assertEquals(1, turntable.getItemHandler().extract(0, resource, 1, tx)); tx.commit(); }
        assertFalse(turntable.hasDisc()); assertFalse(turntable.isPlaying());
    }
    @Test void allowedExtractionRollbackRestoresDiscAndCommitRemovesIt() {
        var turntable = PlatformBootstrap.turntable(POS); turntable.setDisc(disc()); turntable.cycleExtractionMode();
        var resource = ItemResource.of(turntable.getDisc());
        try (var tx = Transaction.openRoot()) { assertEquals(1, turntable.getItemHandler().extract(0, resource, 1, tx)); assertFalse(turntable.hasDisc()); }
        assertTrue(turntable.hasDisc()); assertTrue(resource.matches(turntable.getDisc()));
        try (var tx = Transaction.openRoot()) { assertEquals(1, turntable.getItemHandler().extract(0, resource, 1, tx)); tx.commit(); }
        assertFalse(turntable.hasDisc()); assertFalse(turntable.hasPlaybackData());
    }
    private ItemStack disc() { return PlatformBootstrap.disc("https://example.test/a.mp3", "a", 30); }
}
