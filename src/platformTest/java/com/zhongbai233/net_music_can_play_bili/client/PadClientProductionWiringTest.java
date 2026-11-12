package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMapSettings;
import com.zhongbai233.net_music_can_play_bili.network.PadStatePacket;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real cache/index/version/pending packet code, with client actor and connection transport replaced. No GUI constructed. */
class PadClientProductionWiringTest {
    private final UUID device = UUID.randomUUID();
    private final List<PadStatePacket> sent = new ArrayList<>();
    private Minecraft minecraft;
    private LocalPlayer player;
    private ClientPacketListener connection;
    private MockedStatic<Minecraft> singleton;
    private ItemStack pad, lockedCopy;

    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }
    @BeforeEach void setup() {
        PadClient.clearCachedDocuments();
        minecraft = mock(Minecraft.class); player = mock(LocalPlayer.class); connection = mock(ClientPacketListener.class);
        minecraft.player = player;
        when(minecraft.getConnection()).thenReturn(connection);
        pad = stack(false); lockedCopy = stack(true);
        when(player.getMainHandItem()).thenReturn(pad);
        when(player.getOffhandItem()).thenReturn(lockedCopy);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(pad);
        Inventory inventory = mock(Inventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContainerSize()).thenReturn(2);
        when(inventory.getItem(0)).thenReturn(pad);
        when(inventory.getItem(1)).thenReturn(lockedCopy);
        singleton = mockStatic(Minecraft.class); singleton.when(Minecraft::getInstance).thenReturn(minecraft);
        doAnswer(call -> { if (call.getArgument(0) instanceof PadStatePacket packet) sent.add(packet); return null; })
                .when(connection).send(any(net.minecraft.network.protocol.common.custom.CustomPacketPayload.class));
    }
    @AfterEach void cleanup() { PadClient.clearCachedDocuments(); singleton.close(); }

    @Test void dirtyDocumentSendsLatestRealPacketOnceThenWaitsTenTicks() {
        PadDocument first = document("first", 200, 1, false);
        PadDocument newest = document("latest", 201, 2, false);
        PadClient.markDocumentDirty(pad, first);
        PadClient.markDocumentDirty(pad, newest);
        tick();
        assertEquals(1, sent.size()); assertEquals(device, sent.getFirst().deviceId());
        assertEquals(newest, sent.getFirst().document());
        assertTrue(sent.getFirst().clientUpdatedAtMillis() > 0); assertTrue(sent.getFirst().clientSequence() > 0);
        PadDocument next = document("next", 202, 3, false);
        PadClient.markDocumentDirty(pad, next);
        ticks(9); assertEquals(1, sent.size());
        tick(); assertEquals(2, sent.size()); assertEquals(next, sent.getLast().document());
        assertTrue(sent.getLast().clientUpdatedAtMillis() >= sent.getFirst().clientUpdatedAtMillis());
        assertTrue(sent.getLast().clientSequence() > sent.getFirst().clientSequence());
        ticks(25); assertEquals(2, sent.size());
    }

    @Test void olderMirrorCannotEraseNewerPendingDocument() {
        PadDocument dirty = document("unsent edit", 300, 7, false);
        PadClient.markDocumentDirty(pad, dirty);
        PadClient.receiveMirroredDocument(device, document("older", 300, 6, true), 10);
        assertEquals("unsent edit", PadClient.cachedDocumentFor(pad).title());
        tick(); assertEquals(1, sent.size()); assertEquals(dirty, sent.getFirst().document());
    }

    private ItemStack stack(boolean locked) {
        ItemStack result = PlatformBootstrap.pad(); PadItem.writeDeviceId(result, device);
        PadItem.writeDocument(result, document("item original", 100, 1, locked)); return result;
    }
    private PadDocument document(String title, long time, long seq, boolean locked) {
        return new PadDocument(title, "author", locked, time, seq, PadMapSettings.DEFAULT, List.of(), List.of());
    }
    private void tick() { PadClient.tickHeldDeviceSync(); }
    private void ticks(int count) { for (int i = 0; i < count; i++) tick(); }
}
