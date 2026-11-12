package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigPacket;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigResultPacket;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleEditLeasePacket;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Control-console editor autosave/closing-save lifecycle. Each nested class keeps the exact fixture of the
 * original test file it was merged from (their static-mock setups differ and cannot be shared):
 * <ul>
 *   <li>{@link Autosave}: real constructor/edit/CommandStack/autosave/results/lifecycle; singleton, world/actor,
 *   terrain output, packet transport, lease cache, and widget/font reconstruction are controlled.</li>
 *   <li>{@link RejectedAutosave} (from PadEditorApproved11LifecycleRegressionTest): original screen
 *   constructor/edit/autosave/result and the actual client lease state.</li>
 *   <li>{@link ClosingSave} (from client/PadEditorFunctionalClosureClosingSaveTest): actual ControlConsoleClient
 *   closing state/lease/result logic with a real immutable document snapshot, no Screen.</li>
 *   <li>{@link ProductionAdapter} (from HolographicEditorProductionAdapterTest): real Screen tick/autosave/results
 *   and onClose with a fully mocked lease client.</li>
 * </ul>
 * This does not initialize FML, a GUI window, or a GPU/native resource.
 */
class PadEditorFunctionalClosureAutosaveTest {
    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }

    @Nested class Autosave {
        private final BlockPos pos = new BlockPos(2, 64, 3);
        private final UUID lease = UUID.randomUUID();
        private final List<AutoCloseable> mocks = new ArrayList<>();
        private final List<ControlConsoleConfigPacket> sent = new ArrayList<>();
        private HolographicScreenConfigTestScreen screen;
        private Minecraft minecraft;
        private MockedStatic<ControlConsoleClient> client;

        @BeforeEach void setup() {
            ControlConsoleClient.clearLease();
            minecraft = mock(Minecraft.class); minecraft.level = mock(ClientLevel.class); minecraft.player = mock(LocalPlayer.class);
            when(minecraft.player.isAlive()).thenReturn(true);
            when(minecraft.level.hasChunk(anyInt(), anyInt())).thenReturn(true);
            var console = PlatformBootstrap.console(pos);
            assertTrue(console.replaceDocument(0, "scene", 64, 32, 64, List.of(ControlConsoleElement.defaultScreen())));
            when(minecraft.level.getBlockEntity(pos)).thenReturn(console);
            var singleton = mockStatic(Minecraft.class); mocks.add(singleton); singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            client = mockStatic(ControlConsoleClient.class, CALLS_REAL_METHODS); mocks.add(client);
            client.when(() -> ControlConsoleClient.hasLease(pos)).thenReturn(true);
            client.when(() -> ControlConsoleClient.leaseId(pos)).thenReturn(lease);
            client.when(() -> ControlConsoleClient.tickLease(pos)).thenAnswer(call -> null);
            client.when(() -> ControlConsoleClient.releaseLease(pos)).thenAnswer(call -> null);
            mocks.add(mockStatic(TerrainPreviewManager.class));
            var transport = mockStatic(ClientPacketDistributor.class); mocks.add(transport);
            transport.when(() -> ClientPacketDistributor.sendToServer(any(ControlConsoleConfigPacket.class)))
                    .thenAnswer(call -> { sent.add(call.getArgument(0)); return null; });
            screen = spy(HolographicScreenConfigTestScreen.forControlConsole(pos));
            doNothing().when(screen).init();
            assertEquals(1, screen.currentConsoleDocument().revision()); screen.selectedScreen = 0; minecraft.screen = screen;
        }
        @AfterEach void cleanup() throws Exception {
            client.when(ControlConsoleClient::clearLease).thenCallRealMethod(); ControlConsoleClient.clearLease();
            for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        }

        @Test void pendingSaveRetainsLaterEditAndOldDuplicateAckCannotClearNextOperation() {
            screen.editSelected("first", element -> element.offsetX = 2F); screen.sendConsoleAutosave();
            var first = sent.getFirst();
            screen.editSelected("second", element -> element.offsetX = 7F); ticks(20);
            assertEquals(1, sent.size()); assertEquals(first.operationId(), screen.consolePendingOperation);
            acknowledge(first.operationId(), 2, ControlConsoleConfigResultPacket.Status.APPLIED);
            assertEquals(7F, screen.consoleElementsSnapshot().getFirst().offsetX());
            ticks(11); assertEquals(2, sent.size());
            var second = sent.get(1); assertEquals(2, second.expectedRevision()); assertEquals(7F, second.elements().getFirst().offsetX());
            assertNotEquals(first.operationId(), second.operationId());
            acknowledge(first.operationId(), 2, ControlConsoleConfigResultPacket.Status.DUPLICATE);
            assertEquals(second.operationId(), screen.consolePendingOperation);
            acknowledge(second.operationId(), 3, ControlConsoleConfigResultPacket.Status.APPLIED);
            assertNull(screen.consolePendingOperation); screen.onClose(); assertEquals(2, sent.size());
        }

        @Test void closingWithNewEditBeforePendingAckMustPersistLatestDraft() throws Exception {
            enableRealCloseSaveBridge();
            screen.editSelected("first", element -> element.offsetX = 2F); screen.sendConsoleAutosave();
            var first = sent.getFirst();
            screen.editSelected("second", element -> element.offsetX = 7F);
            doAnswer(call -> { minecraft.screen = null; return null; }).when(minecraft).popGuiLayer();
            screen.onClose(); assertNull(minecraft.screen);
            HolographicEditorLifecycleScreen.acceptControlConsoleConfigResult(new ControlConsoleConfigResultPacket(pos,
                    first.operationId(), 2, ControlConsoleConfigResultPacket.Status.APPLIED));
            assertEquals(2, sent.size(), "closing while the first save is pending must not lose the later local edit");
            assertEquals(7F, sent.get(1).elements().getFirst().offsetX());
            assertEquals(2, sent.get(1).expectedRevision());
        }

        private void acknowledge(UUID operation, int revision, ControlConsoleConfigResultPacket.Status status) {
            screen.acceptConsoleConfigResult(new ControlConsoleConfigResultPacket(pos, operation, revision, status,
                    status == ControlConsoleConfigResultPacket.Status.CONFLICT ? screen.consoleDraft.withRevision(revision) : null));
        }
        private void enableRealCloseSaveBridge() throws Exception {
            // Execute the production closing bridge and state; only UI output and transport remain controlled.
            when(minecraft.getConnection()).thenReturn(mock(ClientPacketListener.class));
            var leaseClass = Class.forName(ControlConsoleClient.class.getName() + "$ActiveLease");
            var constructor = leaseClass.getDeclaredConstructor(BlockPos.class, UUID.class, long.class); constructor.setAccessible(true);
            var active = ControlConsoleClient.class.getDeclaredField("activeLease"); active.setAccessible(true);
            active.set(null, constructor.newInstance(pos, lease, Long.MAX_VALUE));
            client.when(() -> ControlConsoleClient.leaseId(any(BlockPos.class))).thenCallRealMethod();
            client.when(() -> ControlConsoleClient.deferCloseSave(any(BlockPos.class), any(UUID.class), any())).thenCallRealMethod();
            client.when(() -> ControlConsoleClient.acceptClosingSaveResult(any())).thenCallRealMethod();
            client.when(() -> ControlConsoleClient.releaseLease(any(BlockPos.class))).thenCallRealMethod();
        }
        private void ticks(int count) { for (int i = 0; i < count; i++) screen.tick(); }
    }

    /** Merged from PadEditorApproved11LifecycleRegressionTest. */
    @Nested class RejectedAutosave {
        private final BlockPos pos = new BlockPos(2, 64, 3);
        private final UUID lease = UUID.randomUUID();
        private final List<AutoCloseable> mocks = new ArrayList<>();
        private final List<ControlConsoleConfigPacket> saves = new ArrayList<>();
        private Minecraft minecraft;
        private HolographicScreenConfigTestScreen screen;

        @BeforeEach void setup() throws Exception {
            ControlConsoleClient.clearLease();
            minecraft = mock(Minecraft.class);
            minecraft.level = mock(ClientLevel.class);
            minecraft.player = mock(LocalPlayer.class);
            when(minecraft.player.isAlive()).thenReturn(true);
            when(minecraft.level.hasChunk(anyInt(), anyInt())).thenReturn(true);
            var console = PlatformBootstrap.console(pos);
            assertTrue(console.replaceDocument(0, "scene", 64, 32, 64, List.of(ControlConsoleElement.defaultScreen())));
            when(minecraft.level.getBlockEntity(pos)).thenReturn(console);
            var singleton = mockStatic(Minecraft.class); mocks.add(singleton);
            singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            var client = mockStatic(ControlConsoleClient.class, CALLS_REAL_METHODS); mocks.add(client);
            client.when(() -> ControlConsoleClient.tickLease(pos)).thenAnswer(call -> null);
            mocks.add(mockStatic(TerrainPreviewManager.class));
            var transport = mockStatic(ClientPacketDistributor.class); mocks.add(transport);
            transport.when(() -> ClientPacketDistributor.sendToServer(any(ControlConsoleConfigPacket.class)))
                    .thenAnswer(call -> { saves.add(call.getArgument(0)); return null; });
            seedLease(lease);
            screen = newScreen();
            doAnswer(call -> {
                var old = minecraft.screen;
                if (old != null) old.removed(); // Vanilla setScreen removal callback boundary.
                minecraft.screen = call.getArgument(0);
                return null;
            }).when(minecraft).setScreen(any());
            doAnswer(call -> { screen.removed(); minecraft.screen = null; return null; })
                    .when(minecraft).popGuiLayer();
        }
        @AfterEach void cleanup() throws Exception {
            ControlConsoleClient.clearLease();
            for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        }

        @Test void rejectedAutomaticSavePausesWithoutClearingDirtyDraftOrFabricatingSavedFingerprint() {
            int originalSaved = screen.consoleSavedFingerprint;
            screen.editSelected("dirty", e -> e.offsetX = 2F);
            ticks(11);
            assertEquals(1, saves.size());
            rejectLast();
            for (int i = 0; i < 200; i++) {
                ticks(1);
                if (screen.consolePendingOperation != null) rejectLast();
            }
            assertEquals(1, saves.size(), "same rejected lease/state must not emit one save every tick");
            assertNull(screen.consolePendingOperation);
            assertFalse(screen.consoleSaveConflict);
            assertEquals(originalSaved, screen.consoleSavedFingerprint);
            assertEquals(2F, screen.consoleElementsSnapshot().getFirst().offsetX());
            screen.editSelected("still local", e -> e.offsetX = 7F);
            ticks(30);
            assertEquals(1, saves.size(), "ordinary editing cannot rearm a rejected unchanged lease");
            assertEquals(7F, screen.consoleElementsSnapshot().getFirst().offsetX());
        }

        private HolographicScreenConfigTestScreen newScreen() {
            var result = spy(HolographicScreenConfigTestScreen.forControlConsole(pos));
            doNothing().when(result).init();
            assertEquals(1, result.currentConsoleDocument().revision());
            result.selectedScreen = 0; minecraft.screen = result; return result;
        }
        private void seedLease(UUID id) throws Exception {
            var type = Class.forName(ControlConsoleClient.class.getName() + "$ActiveLease");
            var ctor = type.getDeclaredConstructor(BlockPos.class, UUID.class, long.class); ctor.setAccessible(true);
            var field = ControlConsoleClient.class.getDeclaredField("activeLease"); field.setAccessible(true);
            field.set(null, ctor.newInstance(pos, id, Long.MAX_VALUE));
        }
        private void rejectLast() {
            var last = saves.getLast();
            screen.acceptConsoleConfigResult(new ControlConsoleConfigResultPacket(pos, last.operationId(), 1,
                    ControlConsoleConfigResultPacket.Status.REJECTED));
        }
        private void ticks(int count) { for (int i = 0; i < count; i++) screen.tick(); }
    }

    /** Merged from client/PadEditorFunctionalClosureClosingSaveTest (uses only public ControlConsoleClient API). */
    @Nested class ClosingSave {
        final BlockPos pos = new BlockPos(2, 64, 3);
        final UUID lease = UUID.randomUUID(), firstOperation = UUID.randomUUID();
        final List<CustomPacketPayload> sent = new ArrayList<>();
        Minecraft minecraft; ClientLevel level; LocalPlayer player; ClientPacketListener connection;
        MockedStatic<Minecraft> singleton; MockedStatic<ClientPacketDistributor> transport;
        ControlConsoleDocument draft;

        @BeforeEach void setup() throws Exception {
            ControlConsoleClient.clearLease(); minecraft = mock(Minecraft.class);
            level = mock(ClientLevel.class); player = mock(LocalPlayer.class); connection = mock(ClientPacketListener.class);
            minecraft.level = level; minecraft.player = player; when(player.isAlive()).thenReturn(true);
            when(minecraft.getConnection()).thenReturn(connection);
            singleton = mockStatic(Minecraft.class); singleton.when(Minecraft::getInstance).thenReturn(minecraft);
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
            ControlConsoleClient.clearLease(); transport.close(); singleton.close();
        }

        @Test void firstSuccessUsesActualAuthorityRevisionAndFinalAckReleasesExactlyOnce() {
            var status = ControlConsoleConfigResultPacket.Status.APPLIED;
            begin(); assertTrue(sent.isEmpty());
            assertTrue(ControlConsoleClient.acceptClosingSaveResult(result(firstOperation, 73, status)));
            var saved = configs().getFirst(); assertEquals(73, saved.expectedRevision()); assertEquals(lease, saved.leaseId());
            assertEquals(pos, saved.pos()); assertNotEquals(firstOperation, saved.operationId());
            assertEquals(draft.elements(), saved.elements()); assertEquals(7F, saved.elements().getFirst().offsetX());
            assertEquals("latest draft", saved.displayName()); assertEquals(lease, ControlConsoleClient.leaseId(pos));
            assertTrue(leases().isEmpty(), "RELEASE waits for the final submission acknowledgement");
            assertFalse(ControlConsoleClient.acceptClosingSaveResult(result(firstOperation, 73, status)));
            assertEquals(1, configs().size());
            assertTrue(ControlConsoleClient.acceptClosingSaveResult(result(saved.operationId(), 74, status)));
            assertEquals(List.of(ControlConsoleEditLeasePacket.Action.RELEASE), actions()); assertNull(ControlConsoleClient.leaseId(pos));
            assertFalse(ControlConsoleClient.acceptClosingSaveResult(result(saved.operationId(), 74, status)));
            assertEquals(1, leases().size());
        }

        @Test void finalNegativeAcknowledgementNeverResubmitsDraft() {
            var status = ControlConsoleConfigResultPacket.Status.REJECTED;
            begin(); ControlConsoleClient.acceptClosingSaveResult(result(firstOperation, 2, ControlConsoleConfigResultPacket.Status.APPLIED));
            var saved = configs().getFirst();
            assertTrue(ControlConsoleClient.acceptClosingSaveResult(result(saved.operationId(), 20, status)));
            assertEquals(1, configs().size()); assertEquals(List.of(ControlConsoleEditLeasePacket.Action.RELEASE), actions());
            assertNull(closing()); verify(player).sendSystemMessage(any());
        }

        private void begin() { assertTrue(ControlConsoleClient.deferCloseSave(pos, firstOperation, draft)); }
        private ControlConsoleConfigResultPacket result(UUID operation, long revision, ControlConsoleConfigResultPacket.Status status) {
            return new ControlConsoleConfigResultPacket(pos, operation, revision, status,
                    status == ControlConsoleConfigResultPacket.Status.CONFLICT ? draft.withRevision(revision) : null);
        }
        private List<ControlConsoleConfigPacket> configs() { return sent.stream().filter(ControlConsoleConfigPacket.class::isInstance).map(ControlConsoleConfigPacket.class::cast).toList(); }
        private List<ControlConsoleEditLeasePacket> leases() { return sent.stream().filter(ControlConsoleEditLeasePacket.class::isInstance).map(ControlConsoleEditLeasePacket.class::cast).toList(); }
        private List<ControlConsoleEditLeasePacket.Action> actions() { return leases().stream().map(ControlConsoleEditLeasePacket::action).toList(); }
        private Object closing() {
            try { var f = ControlConsoleClient.class.getDeclaredField("closingSave"); f.setAccessible(true); return f.get(null); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        private void seedLease(BlockPos target, UUID token, long renewal) throws Exception {
            var type = Class.forName(ControlConsoleClient.class.getName() + "$ActiveLease");
            var constructor = type.getDeclaredConstructor(BlockPos.class, UUID.class, long.class); constructor.setAccessible(true);
            var f = ControlConsoleClient.class.getDeclaredField("activeLease"); f.setAccessible(true);
            f.set(null, constructor.newInstance(target, token, renewal));
        }
    }

    /** Merged from HolographicEditorProductionAdapterTest. */
    @Nested class ProductionAdapter {
        private final BlockPos pos = new BlockPos(2, 64, 3);
        private final UUID lease = UUID.randomUUID();
        private final List<AutoCloseable> mocks = new ArrayList<>();
        private final List<ControlConsoleConfigPacket> sent = new ArrayList<>();
        private HolographicScreenConfigTestScreen screen;
        private Minecraft minecraft;
        private MockedStatic<ControlConsoleClient> client;

        @BeforeEach void setup() {
            minecraft = mock(Minecraft.class); minecraft.level = mock(ClientLevel.class); minecraft.player = mock(LocalPlayer.class);
            when(minecraft.player.isAlive()).thenReturn(true);
            when(minecraft.level.hasChunk(anyInt(), anyInt())).thenReturn(true);
            var console = PlatformBootstrap.console(pos);
            assertTrue(console.replaceDocument(0, "scene", 64, 32, 64, List.of(ControlConsoleElement.defaultScreen())));
            when(minecraft.level.getBlockEntity(pos)).thenReturn(console);
            var singleton = mockStatic(Minecraft.class); mocks.add(singleton); singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            client = mockStatic(ControlConsoleClient.class); mocks.add(client);
            client.when(() -> ControlConsoleClient.hasLease(pos)).thenReturn(true);
            client.when(() -> ControlConsoleClient.leaseId(pos)).thenReturn(lease);
            mocks.add(mockStatic(TerrainPreviewManager.class));
            var transport = mockStatic(ClientPacketDistributor.class); mocks.add(transport);
            transport.when(() -> ClientPacketDistributor.sendToServer(any(ControlConsoleConfigPacket.class)))
                    .thenAnswer(call -> { sent.add(call.getArgument(0)); return null; });
            screen = spy(HolographicScreenConfigTestScreen.forControlConsole(pos));
            doNothing().when(screen).init(); // Explicit UI widget/font reconstruction boundary, not edit/save logic.
            assertEquals(1, screen.currentConsoleDocument().revision());
            screen.selectedScreen = 0;
            minecraft.screen = screen;
        }
        @AfterEach void cleanup() throws Exception { for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close(); }

        @Test void closeSavesImmediateStructuralEditOnceAndReleasesLease() {
            screen.edit("add", () -> screen.screens.add(HolographicEditorScreenState.PreviewScreenSpec.defaults()));
            screen.onClose(); screen.onClose();
            assertEquals(1, sent.size()); assertEquals(2, sent.getFirst().elements().size());
            assertEquals(2, sent.getFirst().elements().stream().map(ControlConsoleElement::elementId).distinct().count());
            client.verify(() -> ControlConsoleClient.releaseLease(pos), times(2));
            verify(minecraft, times(2)).popGuiLayer();
        }

        @Test void conflictBlocksFurtherAutosaveAndReloadInstallsServerElements() {
            screen.editSelected("local", element -> element.offsetX = 4.5F); screen.sendConsoleAutosave();
            var authoritative = screen.consoleDraft.withRevision(9);
            var serverElement = ControlConsoleElement.defaultScreen();
            authoritative = new ControlConsoleDocument(
                    authoritative.schemaVersion(), authoritative.consoleId(), 9, authoritative.ownerId(), authoritative.accessMode(),
                    authoritative.trustedPlayerIds(), "server", null, null, 0, 0, 0, 64, 32, 64, List.of(serverElement));
            screen.acceptConsoleConfigResult(new ControlConsoleConfigResultPacket(pos, sent.getFirst().operationId(), 9,
                    ControlConsoleConfigResultPacket.Status.CONFLICT, authoritative));
            assertTrue(screen.consoleSaveConflict); ticks(30); assertEquals(1, sent.size());
            screen.reloadAuthoritativeConsoleDocument();
            assertFalse(screen.consoleSaveConflict); assertEquals(authoritative, screen.consoleDraft);
            assertEquals(List.of(serverElement), screen.consoleElementsSnapshot());
            screen.onClose(); assertEquals(1, sent.size());
        }

        private void ticks(int count) { for (int i = 0; i < count; i++) screen.tick(); }
    }
}
