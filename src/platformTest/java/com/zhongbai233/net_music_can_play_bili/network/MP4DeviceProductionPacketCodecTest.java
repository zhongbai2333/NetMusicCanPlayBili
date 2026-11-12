package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import java.util.ArrayList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.zhongbai233.net_music_can_play_bili.network.PacketCodecFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class MP4DeviceProductionPacketCodecTest {
    private static final UUID DEVICE = new UUID(99L, 101L);
    @Test void handResponseRetainsBothHandSlotsAndExactIdentity() {
        for (InteractionHand hand : InteractionHand.values()) {
            var packet = new MP4DeviceIdPacket(hand, DEVICE);
            assertEquals(packet, roundTrip(MP4DeviceIdPacket.STREAM_CODEC, packet));
        }
    }
    @Test void inventoryResponseRetainsSlotAndExactIdentity() {
        var packet = new MP4InventoryDeviceIdPacket(35, DEVICE);
        assertEquals(packet, roundTrip(MP4InventoryDeviceIdPacket.STREAM_CODEC, packet));
    }
    @Test void containerResponseRetainsSlotAndExactIdentity() {
        var packet = new MP4ContainerDeviceIdPacket(103, DEVICE);
        assertEquals(packet, roundTrip(MP4ContainerDeviceIdPacket.STREAM_CODEC, packet));
    }
    @Test void authoritativeMirrorRetainsDuplicateOccurrencesEmptySlotsAndState() {
        var disc = PlatformBootstrap.disc("https://example.test/same.mp4", "重复视频", 91);
        var state = new MP4StatePacket(true, true, true, false, 2, 1, 876, 2, true, 3, true, 555,
                true, 2, true, DEVICE, 654321L, 9L);
        var packet = new MP4DeviceStateMirrorPacket(DEVICE, state, 123456L, true,
                List.of(disc, disc.copy(), ItemStack.EMPTY));
        var decoded = roundTrip(MP4DeviceStateMirrorPacket.STREAM_CODEC, packet, PlatformBootstrap.registries());
        assertEquals(DEVICE, decoded.deviceId()); assertEquals(state, decoded.state());
        assertEquals(packet.updatedGameTime(), decoded.updatedGameTime()); assertTrue(decoded.headphoneLinked());
        assertEquals(3, decoded.queue().size()); assertTrue(decoded.queue().get(2).isEmpty());
        for (int i = 0; i < 2; i++) {
            var song = ItemMusicCD.getSongInfo(decoded.queue().get(i)); assertNotNull(song);
            assertEquals("https://example.test/same.mp4", song.songUrl); assertEquals("重复视频", song.songName);
            assertEquals(91, song.songTime); assertEquals(1, decoded.queue().get(i).getCount());
        }
        assertNotSame(decoded.queue().get(0), decoded.queue().get(1));
    }
    @Test void oversizedMirrorEncoderUsesActualEighteenSlotProductLimitInOrder() {
        var queue = new ArrayList<ItemStack>();
        for (int i = 0; i < MP4Item.MAX_QUEUE_SIZE + 2; i++) {
            queue.add(PlatformBootstrap.disc("https://example.test/" + i + ".mp4", "track-" + i, i + 1));
        }
        var state = new MP4StatePacket(false, false, true, false, 0, 17, 1000, 0, false, 0, true, 0,
                false, 0, false);
        var decoded = roundTrip(MP4DeviceStateMirrorPacket.STREAM_CODEC,
                new MP4DeviceStateMirrorPacket(DEVICE, state, 99L, false, queue), PlatformBootstrap.registries());
        assertEquals(18, decoded.queue().size());
        for (int i = 0; i < 18; i++) {
            assertEquals("https://example.test/" + i + ".mp4", ItemMusicCD.getSongInfo(decoded.queue().get(i)).songUrl);
        }
        assertEquals(17, decoded.state().selectedQueueIndex());
    }
    @Test void absentMirrorQueueEncodesAnEmptyQueue() {
        var state = new MP4StatePacket(false, false, true, false, 0, 0, 1000, 0, false, 0, true, 0,
                false, 0, false);
        var decoded = roundTrip(MP4DeviceStateMirrorPacket.STREAM_CODEC,
                new MP4DeviceStateMirrorPacket(DEVICE, state, 99L, false, null));
        assertEquals(List.of(), decoded.queue()); assertEquals(state, decoded.state());
    }
}
