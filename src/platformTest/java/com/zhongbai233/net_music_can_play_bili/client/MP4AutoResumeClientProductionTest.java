package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Post tick/unload wiring, inventory lookup, mirrored config, resume fingerprint and outgoing packet.
 * Renderer, audio, terrain and unrelated device clients are inert static boundaries; no client is constructed. */
class MP4AutoResumeClientProductionTest {
    private static final UUID PLAYER = new UUID(94L, 1L), DEVICE = new UUID(94L, 2L);
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private final List<MP4PlaybackControlPacket> sent = new ArrayList<>();
    private Minecraft minecraft;
    private LocalPlayer player;
    private ClientPacketListener connection;
    private ItemStack stack;
    private long configTime;

    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }

    @BeforeEach void setUp() throws Exception {
        MP4AutoResumeClient.reset();
        MP4Client.clearCachedStates();
        MP4FocusState.resetAll();
        ClientMediaPlaybackRegistry.clear();
        trackedLevel(null);
        minecraft = mock(Minecraft.class);
        player = mock(LocalPlayer.class);
        connection = mock(ClientPacketListener.class);
        minecraft.player = player;
        minecraft.level = mock(ClientLevel.class);
        Field renderer = Minecraft.class.getDeclaredField("gameRenderer");
        renderer.setAccessible(true);
        renderer.set(minecraft, mock(GameRenderer.class));
        when(minecraft.getConnection()).thenReturn(connection);
        when(player.getUUID()).thenReturn(PLAYER);
        when(player.getEyePosition()).thenReturn(Vec3.ZERO);
        stack = PlatformBootstrap.mp4();
        MP4Item.writeDeviceId(stack, DEVICE);
        assertTrue(MP4Item.addDisc(stack, PlatformBootstrap.disc("https://media.invalid/a", 120)));
        assertTrue(MP4Item.addDisc(stack, PlatformBootstrap.disc("https://media.invalid/b", 120)));
        when(player.getMainHandItem()).thenAnswer(i -> stack);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenAnswer(i -> stack);
        when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(ItemStack.EMPTY);
        when(player.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        var inventory = mock(Inventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContainerSize()).thenReturn(1);
        when(inventory.getItem(0)).thenAnswer(i -> stack);
        var singleton = mockStatic(Minecraft.class);
        mocks.add(singleton);
        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
        doAnswer(i -> { if (i.getArgument(0) instanceof MP4PlaybackControlPacket packet) sent.add(packet); return null; })
                .when(connection).send(any(net.minecraft.network.protocol.common.custom.CustomPacketPayload.class));
        mockPeripheralLifecycle();
        mirror(true, 0, 650, 250);
    }

    @AfterEach void tearDown() throws Exception {
        MP4AutoResumeClient.reset(); MP4Client.clearCachedStates(); MP4FocusState.resetAll();
        ClientMediaPlaybackRegistry.clear(); trackedLevel(null);
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
    }

    @Test void realPostTickWaitsFortyTicksThenSendsOneFingerprintWithAuthoritativeTarget() {
        ticks(40); assertTrue(sent.isEmpty());
        tick();
        assertEquals(List.of(new MP4PlaybackControlPacket(MP4PlaybackControlPacket.Action.START,
                0, 650, 30_000L, DEVICE)), sent);
        ticks(80); assertEquals(1, sent.size());
        assertEquals(250, MP4Client.cachedStateFor(stack).progressPerMille());
    }

    // Merged from MP4AutoResumeEpochRepairProductionTest (identical fixture).
    @Test void authoritativeStopRevokesRecoveryBeforeReadinessDespiteStaleMirror() {
        new Mp4ClientMediaSyncPolicy().stop(DEVICE);
        ticks(120);
        assertTrue(sent.isEmpty(), "STOP beats delayed configuration mirror");
    }

    @Test void pausedSnapshotNeverAutoplaysAndPlayingMirrorDoesNotRearmConsumedEpoch() {
        mirror(false, 0, 650, 250); ticks(80); assertTrue(sent.isEmpty());
        mirror(true, 0, 650, 250); tick(); assertEquals(1, sent.size());
        mirror(false, 0, 650, 250); ticks(5);
        mirror(true, 0, 650, 250); ticks(80);
        assertEquals(1, sent.size());
    }

    private void mirror(boolean playing, int index, int volume, int progress) {
        MP4Client.receiveMirroredState(MP4Item.readDeviceId(stack),
                new MP4Item.State(playing, false, true, false, 5, index, 0, volume,
                        0, false, false, 0, false, progress, false), ++configTime, false, MP4Item.readQueue(stack));
    }
    private void tick() { ClientMediaLifecycleHandler.onClientTick(new ClientTickEvent.Post()); }
    private void ticks(int count) { for (int i = 0; i < count; i++) tick(); }
    private static void trackedLevel(ClientLevel level) throws Exception {
        Field field = ClientMediaLifecycleHandler.class.getDeclaredField("lastTrackedLevel");
        field.setAccessible(true); field.set(null, level);
    }

    private void mockPeripheralLifecycle() throws Exception {
        // These are only side-effect boundaries around the real lifecycle -> AutoResume/cache composition.
        String base = "com.zhongbai233.net_music_can_play_bili.";
        String[] classes = {
                "client.renderer.video.VideoCloseDiagnostics", "media.audio.OpenALSpatialAudio",
                "media.audio.AudioNativeCloseDiagnostics", "client.diagnostics.ClientMemoryDiagnostics",
                "client.diagnostics.ClientMemoryProtection", "client.audio.ClientAudioOutputRegistry",
                "client.audio.SyncedMediaSound", "client.audio.ModernTurntablePlaybackCoordinator",
                "client.ClientMediaMovingSound", "client.sync.ClientMediaDemandScheduler",
                "client.ControlConsoleClient", "client.PadClient", "client.MP4HandheldVideoClient",
                "client.pad.PadMapClientCache", "client.terrain.TerrainPreviewManager",
                "client.renderer.ControlConsoleRenderer", "client.renderer.item.PadItemScreenRenderer",
                "client.renderer.item.MP4ItemScreenRenderer", "client.renderer.video.VideoBillboardPreview",
                "client.ModernTurntableVideoClient", "client.LiveStreamerVideoClient",
                "client.sync.LiveRoomMetadataRegistry", "client.sync.ModernTurntableTimeline",
                "link.ClientLinkRegistry", "client.audio.SyncedStreamRecoveryRegistry",
                "media.stream.CdnHealthTracker", "client.audio.ModernTurntablePlaybackTracker",
                "client.audio.ClientAudioEndpointIndex", "client.sync.ClientMediaPlaybackSessions",
                "client.sync.ClientMediaAudioRouting", "client.PadFocusState",
                "client.sync.ClientAiSubtitleRegistry", "bili.HttpAudioStreamHandler"
        };
        for (String name : classes) mocks.add(mockStatic(Class.forName(base + name)));
    }
}
