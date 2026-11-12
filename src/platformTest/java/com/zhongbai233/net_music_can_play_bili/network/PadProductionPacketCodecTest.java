package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.item.pad.*;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.zhongbai233.net_music_can_play_bili.network.PacketCodecFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class PadProductionPacketCodecTest {
    private static final UUID DEVICE = new UUID(1L, 2L), POINT = new UUID(3L, 4L);

    @Test void controlPacketRetainsDeviceOptionalPointAndSeekTarget() {
        for (UUID point : new UUID[] {POINT, null}) {
            var packet = new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.SEEK, DEVICE, point, 123456789012L);
            assertEquals(packet, roundTrip(PadPlaybackControlPacket.STREAM_CODEC, packet));
        }
    }

    @Test void negativeControlTimeIsNormalizedOnWire() {
        assertEquals(new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.SEEK, DEVICE, POINT, 0L),
                roundTrip(PadPlaybackControlPacket.STREAM_CODEC,
                        new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.SEEK, DEVICE, POINT, -1L)));
    }

    @Test void retryPreservesLogicalSessionAndTriggerIdentity() {
        var packet = new PadPlaybackRetryPacket(DEVICE, POINT, "active-logical-session", Long.MAX_VALUE);
        assertEquals(packet, roundTrip(PadPlaybackRetryPacket.STREAM_CODEC, packet));
        assertEquals(new PadPlaybackRetryPacket(DEVICE, POINT, "", 0L),
                roundTrip(PadPlaybackRetryPacket.STREAM_CODEC,
                        new PadPlaybackRetryPacket(DEVICE, POINT, null, -1L)));
    }

    @Test void documentWithActualNetMusicDiscAndTriggersRoundTripsThroughProductionPacket() {
        var document = document();
        var packet = new PadStatePacket(DEVICE, document, 123456789L, 9123L);
        var decoded = roundTrip(PadStatePacket.STREAM_CODEC, packet, PlatformBootstrap.registries());
        assertEquals(DEVICE, decoded.deviceId()); assertEquals(packet.clientUpdatedAtMillis(), decoded.clientUpdatedAtMillis());
        assertEquals(packet.clientSequence(), decoded.clientSequence()); assertDocument(document, decoded.document());
    }

    @Test void authoritativePadMirrorPreservesDocumentAndServerTimestamp() {
        var packet = new PadStateMirrorPacket(DEVICE, document(), 987654321L);
        var decoded = roundTrip(PadStateMirrorPacket.STREAM_CODEC, packet, PlatformBootstrap.registries());
        assertEquals(DEVICE, decoded.deviceId()); assertEquals(packet.updatedGameTime(), decoded.updatedGameTime());
        assertDocument(packet.document(), decoded.document());
    }

    @Test void absentDocumentUsesDefaultAndNegativeClientClockNormalizes() {
        var decoded = roundTrip(PadStatePacket.STREAM_CODEC,
                new PadStatePacket(DEVICE, null, -1L, -5L));
        assertEquals(PadDocument.DEFAULT, decoded.document());
        assertEquals(0L, decoded.clientUpdatedAtMillis()); assertEquals(0L, decoded.clientSequence());
    }

    private static PadDocument document() {
        return new PadDocument("试播地图", "作者", true, 123456L, 17L,
                new PadMapSettings("minecraft:the_nether", -300, 4096, 256, 2.5F, false),
                List.of(new PadMediaEntry(7, PlatformBootstrap.disc("https://example.test/a.mp4", "视频", 91)),
                        new PadMediaEntry(2, PlatformBootstrap.disc("https://example.test/b.mp3", "音乐", 42))),
                List.of(new PadTriggerPoint(POINT, "入口", -2.25D, 64.75D, 4096.125D, 32, 7,
                        PadTriggerMode.ENTER_RADIUS, true, 678, false),
                        new PadTriggerPoint(DEVICE, "手动", 9D, -30D, 5D, 128, 2,
                                PadTriggerMode.MANUAL, false, 1000, true)));
    }

    private static void assertDocument(PadDocument expected, PadDocument actual) {
        assertEquals(expected.title(), actual.title()); assertEquals(expected.author(), actual.author());
        assertEquals(expected.locked(), actual.locked()); assertEquals(expected.updatedAtMillis(), actual.updatedAtMillis());
        assertEquals(expected.sequence(), actual.sequence()); assertEquals(expected.mapSettings(), actual.mapSettings());
        assertEquals(expected.triggerPoints(), actual.triggerPoints());
        assertEquals(expected.mediaEntries().size(), actual.mediaEntries().size());
        for (int i = 0; i < expected.mediaEntries().size(); i++) {
            var left = expected.mediaEntries().get(i); var right = actual.mediaEntries().get(i);
            assertEquals(left.mediaId(), right.mediaId()); assertEquals(1, right.disc().getCount());
            var a = ItemMusicCD.getSongInfo(left.disc()); var b = ItemMusicCD.getSongInfo(right.disc());
            assertNotNull(b); assertEquals(a.songUrl, b.songUrl); assertEquals(a.songName, b.songName);
            assertEquals(a.songTime, b.songTime); assertEquals(a.vip, b.vip);
        }
    }
}
