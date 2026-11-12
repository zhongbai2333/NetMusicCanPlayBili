package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.*;
import com.zhongbai233.net_music_can_play_bili.network.*;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Calls the actual {@link ControlConsoleClient} closing-save state/lease/result/tick logic with a real
 * immutable document snapshot. Covers only what happens when an acknowledgement or lease goes stale:
 * a late result must not release or mutate a replacement lease, and an expired closing context must not
 * restart. The submit/ack sequencing lives in {@code gui.PadEditorFunctionalClosureAutosaveTest}.
 * Minecraft singleton/world/player/connection and packet transport are doubles; private state is seeded
 * by reflection without replacing production transitions. No Screen, GPU, socket or desktop input.
 */
class PadEditorFunctionalClosureClosingSaveTest {
    final BlockPos pos = new BlockPos(2, 64, 3);
    final UUID lease = UUID.randomUUID(), firstOperation = UUID.randomUUID();
    final List<CustomPacketPayload> sent = new ArrayList<>();
    Minecraft minecraft;
    ClientLevel level;
    LocalPlayer player;
    ClientPacketListener connection;
    MockedStatic<Minecraft> singleton;
    MockedStatic<ClientPacketDistributor> transport;
    ControlConsoleDocument draft;

    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }

    @BeforeEach void setup() throws Exception {
        ControlConsoleClient.clearLease();
        minecraft = mock(Minecraft.class);
        level = mock(ClientLevel.class);
        player = mock(LocalPlayer.class);
        connection = mock(ClientPacketListener.class);
        minecraft.level = level;
        minecraft.player = player;
        when(player.isAlive()).thenReturn(true);
        when(minecraft.getConnection()).thenReturn(connection);
        singleton = mockStatic(Minecraft.class);
        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
        transport = mockStatic(ClientPacketDistributor.class);
        transport.when(() -> ClientPacketDistributor.sendToServer(any(CustomPacketPayload.class)))
                .thenAnswer(call -> { sent.add(call.getArgument(0)); return null; });
        seedLease(pos, lease, Long.MAX_VALUE);
        var original = ControlConsoleDocument.empty();
        var element = new ControlConsoleElement(original.elements().getFirst().elementId(), ControlConsoleElement.Type.SCREEN,
                "latest", 2.2F, 7F, 0.05F, 0.75F, 16F / 9F, 0F, 0F, 0F);
        draft = new ControlConsoleDocument(original.schemaVersion(), original.consoleId(), 1, original.ownerId(),
                original.accessMode(), original.trustedPlayerIds(), "latest draft", null, null, 0, 0, 0,
                64, 32, 64, List.of(element));
    }

    @AfterEach void cleanup() {
        ControlConsoleClient.clearLease();
        transport.close();
        singleton.close();
    }

    @Test void worldReplacementDiscardsLateAcknowledgementWithoutNetworkEffects() {
        begin();
        minecraft.level = mock(ClientLevel.class);
        assertTrue(ControlConsoleClient.acceptClosingSaveResult(
                result(firstOperation, 2, ControlConsoleConfigResultPacket.Status.APPLIED)));
        assertNull(closing());
        assertNull(ControlConsoleClient.leaseId(pos));
        assertTrue(sent.isEmpty());
    }

    @Test void oldClosingResultCannotReleaseOrMutateNewLease() throws Exception {
        begin();
        var replacement = UUID.randomUUID();
        seedLease(pos, replacement, Long.MAX_VALUE);
        assertTrue(ControlConsoleClient.acceptClosingSaveResult(
                result(firstOperation, 2, ControlConsoleConfigResultPacket.Status.APPLIED)));
        assertNull(closing());
        assertEquals(replacement, ControlConsoleClient.leaseId(pos));
        assertTrue(sent.isEmpty());
    }

    @Test void phaseTimeoutReleasesOnceAndLateAckCannotRestart() throws Exception {
        begin();
        long deadline = (long) accessor(closing(), "deadlineNanos");
        ControlConsoleClient.tickClosingSave(deadline - 1);
        assertTrue(sent.isEmpty());
        ControlConsoleClient.tickClosingSave(deadline);
        assertEquals(List.of(ControlConsoleEditLeasePacket.Action.RELEASE), actions());
        assertNull(closing());
        verify(player).sendSystemMessage(any());
        assertFalse(ControlConsoleClient.acceptClosingSaveResult(
                result(firstOperation, 2, ControlConsoleConfigResultPacket.Status.APPLIED)));
        ControlConsoleClient.tickClosingSave(deadline + 1);
        assertEquals(1, sent.size());
    }

    @Test void acknowledgementAfterDeadlineCannotSubmitBeforeNextClientTick() throws Exception {
        begin();
        replaceClosingDeadline(System.nanoTime() - 1);
        assertTrue(ControlConsoleClient.acceptClosingSaveResult(
                result(firstOperation, 2, ControlConsoleConfigResultPacket.Status.APPLIED)));
        assertTrue(configs().isEmpty());
        assertEquals(List.of(ControlConsoleEditLeasePacket.Action.RELEASE), actions());
        assertNull(closing());
        verify(player).sendSystemMessage(any());
    }

    private void begin() { assertTrue(ControlConsoleClient.deferCloseSave(pos, firstOperation, draft)); }

    private ControlConsoleConfigResultPacket result(UUID operation, long revision, ControlConsoleConfigResultPacket.Status status) {
        return new ControlConsoleConfigResultPacket(pos, operation, revision, status,
                status == ControlConsoleConfigResultPacket.Status.CONFLICT ? draft.withRevision(revision) : null);
    }

    private List<ControlConsoleConfigPacket> configs() {
        return sent.stream().filter(ControlConsoleConfigPacket.class::isInstance)
                .map(ControlConsoleConfigPacket.class::cast).toList();
    }

    private List<ControlConsoleEditLeasePacket> leases() {
        return sent.stream().filter(ControlConsoleEditLeasePacket.class::isInstance)
                .map(ControlConsoleEditLeasePacket.class::cast).toList();
    }

    private List<ControlConsoleEditLeasePacket.Action> actions() {
        return leases().stream().map(ControlConsoleEditLeasePacket::action).toList();
    }

    private Object closing() { return field("closingSave"); }

    private Object field(String name) {
        try {
            var f = ControlConsoleClient.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Object accessor(Object object, String name) throws Exception {
        var method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(object);
    }

    private void seedLease(BlockPos target, UUID token, long renewal) throws Exception {
        var type = Class.forName(ControlConsoleClient.class.getName() + "$ActiveLease");
        var constructor = type.getDeclaredConstructor(BlockPos.class, UUID.class, long.class);
        constructor.setAccessible(true);
        var f = ControlConsoleClient.class.getDeclaredField("activeLease");
        f.setAccessible(true);
        f.set(null, constructor.newInstance(target, token, renewal));
    }

    private void replaceClosingDeadline(long deadline) throws Exception {
        Object pending = closing();
        var components = pending.getClass().getRecordComponents();
        var types = new Class<?>[components.length];
        var arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            arguments[i] = components[i].getName().equals("deadlineNanos")
                    ? deadline : accessor(pending, components[i].getName());
        }
        var constructor = pending.getClass().getDeclaredConstructor(types);
        constructor.setAccessible(true);
        var f = ControlConsoleClient.class.getDeclaredField("closingSave");
        f.setAccessible(true);
        f.set(null, constructor.newInstance(arguments));
    }
}
