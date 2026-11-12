package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.block.ControlConsoleBlock;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsoleConfigHandlerTest extends ConsoleHandlerFixture {
    enum Gate { CANNOT_BUILD, OUTSIDE_EIGHT_BLOCKS, UNLOADED_CHUNK, WRONG_BLOCK_ENTITY, WRONG_OWNER,
        NO_EDIT_LEASE, FOREIGN_EDIT_LEASE, EXPIRED_EDIT_LEASE }

    @ParameterizedTest @EnumSource(Gate.class)
    void rejectedActualConfigHasNoDocumentOrDirtyUpdateEffects(Gate gate) {
        UUID lease = editingLease(); var before = console.document();
        switch (gate) {
            case CANNOT_BUILD -> when(actor.mayBuild()).thenReturn(false);
            case OUTSIDE_EIGHT_BLOCKS -> position(8.001D, 0D, 0D);
            case UNLOADED_CHUNK -> when(level.hasChunk(-2, -3)).thenReturn(false);
            case WRONG_BLOCK_ENTITY -> when(level.getBlockEntity(POS)).thenReturn(mock(BlockEntity.class));
            case WRONG_OWNER -> when(actor.getUUID()).thenReturn(OTHER);
            case NO_EDIT_LEASE -> lease = UUID.randomUUID();
            case FOREIGN_EDIT_LEASE -> {
                ControlConsoleEditLeaseRegistry.clear(); lease = ControlConsoleEditLeaseRegistry.acquire(
                        ControlConsoleEditLeasePacket.key(level, POS), OTHER, System.currentTimeMillis()).leaseId();
            }
            case EXPIRED_EDIT_LEASE -> {
                ControlConsoleEditLeaseRegistry.clear(); lease = ControlConsoleEditLeaseRegistry.acquire(
                        ControlConsoleEditLeasePacket.key(level, POS), OWNER, System.currentTimeMillis() - 20000L).leaseId();
            }
        }
        ControlConsoleConfigPacket.handle(config(lease, UUID.randomUUID(), before.revision(), "new"), context);
        assertEquals(ControlConsoleConfigResultPacket.Status.REJECTED, onlyAck(ControlConsoleConfigResultPacket.class).status());
        assertNoDocumentMutation(before);
        if (gate == Gate.UNLOADED_CHUNK) verify(level, never()).getBlockEntity(POS);
    }

    @Test void exactlyEightBlocksIsAcceptedAndUpdatePrecedesAcknowledgement() {
        UUID lease = editingLease(); long revision = console.documentRevision(); position(8D, 0D, 0D);
        ControlConsoleConfigPacket.handle(config(lease, UUID.randomUUID(), revision, "saved"), context);
        var ack = onlyAck(ControlConsoleConfigResultPacket.class);
        assertEquals(ControlConsoleConfigResultPacket.Status.APPLIED, ack.status());
        assertEquals(revision + 1L, ack.revision()); assertEquals("saved", console.document().displayName());
        assertEquals(List.of("dirty:" + (revision + 1L), "block-update:" + (revision + 1L),
                "ack:" + (revision + 1L)), effects);
    }

    @Test void duplicateOperationCommitsOnceAndAcknowledgesDuplicateAtSameRevision() {
        UUID lease = editingLease(), operation = UUID.randomUUID(); long revision = console.documentRevision();
        var packet = config(lease, operation, revision, "saved");
        ControlConsoleConfigPacket.handle(packet, context); ControlConsoleConfigPacket.handle(packet, context);
        assertEquals(2, sent.size());
        assertEquals(ControlConsoleConfigResultPacket.Status.APPLIED, ((ControlConsoleConfigResultPacket) sent.get(0)).status());
        var duplicate = (ControlConsoleConfigResultPacket) sent.get(1);
        assertEquals(ControlConsoleConfigResultPacket.Status.DUPLICATE, duplicate.status());
        assertEquals(revision + 1L, duplicate.revision()); assertEquals(revision + 1L, console.documentRevision());
        assertEquals(List.of("dirty:" + (revision + 1L), "block-update:" + (revision + 1L),
                "ack:" + (revision + 1L), "ack:" + (revision + 1L)), effects);
        verify(console, times(1)).replaceDocument(revision, "saved", 64D, 32D, 64D, List.of());
    }

    @Test void staleRevisionReturnsActualAuthorityWithoutOverwritingNewerDocument() {
        UUID lease = editingLease(); long stale = console.documentRevision();
        ControlConsoleConfigPacket.handle(config(lease, UUID.randomUUID(), stale, "first"), context);
        var current = console.document(); clearEffects();
        ControlConsoleConfigPacket.handle(config(lease, UUID.randomUUID(), stale, "stale"), context);
        var ack = onlyAck(ControlConsoleConfigResultPacket.class);
        assertEquals(ControlConsoleConfigResultPacket.Status.CONFLICT, ack.status());
        assertSame(current, console.document()); assertEquals(current, ack.authoritativeDocument());
        assertEquals(current.revision(), ack.revision()); assertEquals(List.of("ack:" + current.revision()), effects);
    }

    @Test void opTwoCanPassBuildGateAndEditAnotherOwnersConsole() {
        when(actor.getUUID()).thenReturn(OTHER); when(actor.mayBuild()).thenReturn(false);
        when(server.getProfilePermissions(any(NameAndId.class))).thenReturn(LevelBasedPermissionSet.GAMEMASTER);
        UUID lease = editingLease(); long revision = console.documentRevision();
        ControlConsoleConfigPacket.handle(config(lease, UUID.randomUUID(), revision, "admin"), context);
        assertEquals(ControlConsoleConfigResultPacket.Status.APPLIED, onlyAck(ControlConsoleConfigResultPacket.class).status());
        assertEquals(OWNER, console.document().ownerId()); assertEquals("admin", console.document().displayName());
    }

    @Test void invalidGeometryIsRejectedWithoutRememberingOperationThenCorrectedRetryCanCommit() {
        UUID lease = editingLease(), operation = UUID.randomUUID(); long revision = console.documentRevision();
        var before = console.document();
        var invalid = new ControlConsoleConfigPacket(POS, lease, operation, revision, "bad", Double.NaN, 32D, 64D, List.of());
        ControlConsoleConfigPacket.handle(invalid, context);
        assertEquals(ControlConsoleConfigResultPacket.Status.REJECTED, onlyAck(ControlConsoleConfigResultPacket.class).status());
        assertSame(before, console.document()); assertEquals(List.of("ack:" + revision), effects);
        clearEffects(); ControlConsoleConfigPacket.handle(config(lease, operation, revision, "fixed"), context);
        assertEquals(ControlConsoleConfigResultPacket.Status.APPLIED, onlyAck(ControlConsoleConfigResultPacket.class).status());
        assertEquals("fixed", console.document().displayName());
    }

    @Test void publicEditorCannotEscalateAclThroughActualAccessHandler() {
        UUID ownerLease = editingLease();
        ControlConsoleAccessPacket.handle(new ControlConsoleAccessPacket(POS, ownerLease, UUID.randomUUID(),
                console.documentRevision(), ControlConsoleDocument.AccessMode.PUBLIC_EDIT, Set.of()), context);
        assertEquals(ControlConsoleConfigResultPacket.Status.APPLIED, onlyAck(ControlConsoleConfigResultPacket.class).status());
        ControlConsoleEditLeaseRegistry.clear(); when(actor.getUUID()).thenReturn(OTHER);
        UUID editorLease = editingLease(); var before = console.document(); clearEffects();
        ControlConsoleAccessPacket.handle(new ControlConsoleAccessPacket(POS, editorLease, UUID.randomUUID(), before.revision(),
                ControlConsoleDocument.AccessMode.TRUSTED, Set.of(OTHER)), context);
        assertEquals(ControlConsoleConfigResultPacket.Status.REJECTED, onlyAck(ControlConsoleConfigResultPacket.class).status());
        assertSame(before, console.document()); assertEquals(List.of("ack:" + before.revision()), effects);
    }

    @Test void ownerAclUpdateIsIdempotentAndPreservesAuthorityOnConflict() {
        UUID lease = editingLease(), operation = UUID.randomUUID(); long revision = console.documentRevision();
        var packet = new ControlConsoleAccessPacket(POS, lease, operation, revision,
                ControlConsoleDocument.AccessMode.TRUSTED, Set.of(OTHER));
        ControlConsoleAccessPacket.handle(packet, context); ControlConsoleAccessPacket.handle(packet, context);
        assertEquals(ControlConsoleConfigResultPacket.Status.APPLIED, ((ControlConsoleConfigResultPacket) sent.get(0)).status());
        assertEquals(ControlConsoleConfigResultPacket.Status.DUPLICATE, ((ControlConsoleConfigResultPacket) sent.get(1)).status());
        assertEquals(revision + 1L, console.documentRevision()); assertEquals(Set.of(OTHER), console.document().trustedPlayerIds());
        clearEffects(); NetworkRateLimiter.removePlayer(OWNER); var current = console.document();
        ControlConsoleAccessPacket.handle(new ControlConsoleAccessPacket(POS, lease, UUID.randomUUID(), revision,
                ControlConsoleDocument.AccessMode.PUBLIC_EDIT, Set.of()), context);
        var conflict = onlyAck(ControlConsoleConfigResultPacket.class);
        assertEquals(ControlConsoleConfigResultPacket.Status.CONFLICT, conflict.status());
        assertSame(current, console.document()); assertEquals(current, conflict.authoritativeDocument());
        assertEquals(List.of("ack:" + current.revision()), effects);
    }

    // The trusted-UUID parsing case moved to gui/ConsoleTrustedUuidTest (package-private access).

    // Merged from network/ConsoleBindingHandlerTest.
    @Test void wrongConsoleBlockEntityCannotClaimOrBindSource() {
        freshConsole(); ItemStack linked = new ItemStack(Items.STONE);
        LinkHelper.writeControlConsoleLinkToItem(linked, SOURCE_POS, "minecraft:overworld", LinkHelper.ControlConsoleSourceKind.TURNTABLE);
        when(level.getBlockEntity(SOURCE_POS)).thenReturn(PlatformBootstrap.turntable(SOURCE_POS));
        when(level.getBlockEntity(POS)).thenReturn(mock(BlockEntity.class)); placement(linked);
        assertNull(console.document().ownerId()); assertFalse(console.document().hasSourceBinding());
        assertNotNull(LinkHelper.readControlConsoleLinkFromItem(linked)); assertTrue(effects.isEmpty());
    }

    @Test void foreignDimensionIsRejectedBeforeQueryingSourceBlockEntity() {
        freshConsole();var linked=new ItemStack(Items.STONE);
        LinkHelper.writeControlConsoleLinkToItem(linked,SOURCE_POS,"minecraft:the_nether",LinkHelper.ControlConsoleSourceKind.TURNTABLE);
        when(level.getBlockEntity(SOURCE_POS)).thenReturn(PlatformBootstrap.turntable(SOURCE_POS));
        clearInvocations(level);placement(linked);
        assertFalse(console.document().hasSourceBinding());verify(level,never()).getBlockEntity(SOURCE_POS);
        assertNotNull(LinkHelper.readControlConsoleLinkFromItem(linked));
    }

    // Merged from network/ConsoleLeaseHandlerTest.
    @Test void deniedBuildPermissionCannotClaimAnUnownedConsoleThroughLeaseOpen() {
        console = spy(com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap.console(POS));
        console.setLevel(level); when(level.getBlockEntity(POS)).thenReturn(console);
        when(actor.mayBuild()).thenReturn(false); clearEffects();
        ControlConsoleEditLeasePacket.handle(new ControlConsoleEditLeasePacket(POS,
                ControlConsoleEditLeasePacket.Action.OPEN, null), context);
        assertEquals(ControlConsoleEditLeaseResultPacket.Status.REJECTED, onlyAck(ControlConsoleEditLeaseResultPacket.class).status());
        assertNull(console.document().ownerId(), "rejected adventure player must not acquire console ownership");
        assertEquals(java.util.List.of("ack:0"), effects);
    }

    @Test void foreignPlayerCannotReleaseAnotherPlayersEditLeaseEvenWithItsExactToken() {
        UUID lease = editingLease(); when(actor.getUUID()).thenReturn(OTHER);
        ControlConsoleEditLeasePacket.handle(new ControlConsoleEditLeasePacket(POS,
                ControlConsoleEditLeasePacket.Action.RELEASE, lease), context);
        assertTrue(ControlConsoleEditLeaseRegistry.validate(ControlConsoleEditLeasePacket.key(level, POS), OWNER,
                lease, System.currentTimeMillis())); assertTrue(sent.isEmpty());
        verify(level, never()).getBlockEntity(POS);
    }

    @Test void anotherPublicEditorReceivesBusyInsteadOfStealingExclusiveEditLease() {
        console.replaceAccessControl(actor, UUID.randomUUID(), console.documentRevision(),
                ControlConsoleDocument.AccessMode.PUBLIC_EDIT, Set.of());
        UUID lease = editingLease(); when(actor.getUUID()).thenReturn(OTHER); clearEffects();
        ControlConsoleEditLeasePacket.handle(new ControlConsoleEditLeasePacket(POS,
                ControlConsoleEditLeasePacket.Action.OPEN, null), context);
        assertEquals(ControlConsoleEditLeaseResultPacket.Status.BUSY, onlyAck(ControlConsoleEditLeaseResultPacket.class).status());
        assertTrue(ControlConsoleEditLeaseRegistry.validate(ControlConsoleEditLeasePacket.key(level, POS), OWNER,
                lease, System.currentTimeMillis()));
    }

    private void freshConsole() {
        console = spy(PlatformBootstrap.console(POS)); console.setLevel(level); when(level.getBlockEntity(POS)).thenReturn(console);
        clearEffects();
    }
    private void placement(ItemStack stack) {
        ((ControlConsoleBlock) console.getBlockState().getBlock()).setPlacedBy(level, POS, console.getBlockState(), actor, stack);
    }
}
