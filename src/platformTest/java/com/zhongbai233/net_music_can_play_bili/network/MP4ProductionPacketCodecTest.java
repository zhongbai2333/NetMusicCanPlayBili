package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackEndReason;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import static com.zhongbai233.net_music_can_play_bili.network.PacketCodecFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class MP4ProductionPacketCodecTest {
    private static final UUID OWNER = new UUID(0L, 1L), SOURCE = new UUID(0L, 2L);

    @Test void controlPacketRetainsSelectedOccurrenceAndOptionalDevice() {
        for (UUID device : new UUID[] {SOURCE, null}) {
            var packet = new MP4PlaybackControlPacket(MP4PlaybackControlPacket.Action.SELECT, 7, 513, 123456789012L, device);
            assertEquals(packet, roundTrip(MP4PlaybackControlPacket.STREAM_CODEC, packet));
        }
    }

    @Test void protocolSevenSelectWireLayoutStaysStable() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            MP4PlaybackControlPacket.STREAM_CODEC.encode(buffer,
                    new MP4PlaybackControlPacket(MP4PlaybackControlPacket.Action.SELECT, 7, 500, 10000L, SOURCE));
            // SELECT=6, fixed-width queue/volume, VarLong time, present flag, 128-bit UUID.
            assertEquals("0600000007000001f4904e0100000000000000000000000000000002", ByteBufUtil.hexDump(buffer));
        } finally { buffer.release(); }
    }

    @Test void terminalReasonAndPhysicalTransportIdentitySurviveWire() {
        var packet = new MP4PlaybackEndPacket(SOURCE, "logical-session", "physical-token", PlaybackEndReason.FAILED);
        assertEquals(packet, roundTrip(MP4PlaybackEndPacket.STREAM_CODEC, packet));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 4, Integer.MAX_VALUE})
    void unknownTerminalReasonIsRejectedByRealDecoder(int invalidReason) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            buffer.writeUUID(SOURCE); buffer.writeUtf("session", 128); buffer.writeUtf("token", 128);
            buffer.writeVarInt(invalidReason);
            assertThrows(DecoderException.class, () -> MP4PlaybackEndPacket.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void endEncoderRejectsOverlongLogicalSessionAndPhysicalToken() {
        for (boolean session : new boolean[] {true, false}) {
            RegistryFriendlyByteBuf buffer = buffer();
            try {
                var packet = new MP4PlaybackEndPacket(SOURCE, session ? "a".repeat(129) : "session",
                        session ? "token" : "a".repeat(129), PlaybackEndReason.NATURAL_EOF);
                assertThrows(EncoderException.class, () -> MP4PlaybackEndPacket.STREAM_CODEC.encode(buffer, packet));
            } finally { buffer.release(); }
        }
    }

    @Test void retryRetainsExactSessionAndSeekTarget() {
        var packet = new MP4PlaybackRetryPacket(SOURCE, "session", Long.MAX_VALUE);
        assertEquals(packet, roundTrip(MP4PlaybackRetryPacket.STREAM_CODEC, packet));
        assertEquals(new MP4PlaybackRetryPacket(SOURCE, "", 0L), roundTrip(MP4PlaybackRetryPacket.STREAM_CODEC,
                new MP4PlaybackRetryPacket(SOURCE, null, -1L)));
    }

    @Test void retryDecoderRejectsOverlongSession() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            buffer.writeUUID(SOURCE); buffer.writeUtf("s".repeat(129)); buffer.writeVarLong(0L);
            assertThrows(DecoderException.class, () -> MP4PlaybackRetryPacket.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void stateAllFieldsAndBothClientVersionsSurviveWire() {
        var packet = new MP4StatePacket(true, true, true, false, 3, 19, 613, 2, true, 11, false, 444,
                true, 2, true, SOURCE, 123456789012L, 7654321L);
        assertEquals(packet, roundTrip(MP4StatePacket.STREAM_CODEC, packet));
        assertEquals(packet.toState(), roundTrip(MP4StatePacket.STREAM_CODEC, packet).toState());
    }

    @Test void stateWithoutDeviceNormalizesNegativeClientVersions() {
        var packet = new MP4StatePacket(false, false, false, true, 0, 0, 1000, 0, false, 0, true, 0,
                false, 0, false, null, -9L, -3L);
        var decoded = roundTrip(MP4StatePacket.STREAM_CODEC, packet);
        assertNull(decoded.deviceId()); assertEquals(0L, decoded.clientUpdatedAtMillis());
        assertEquals(0L, decoded.clientSequence()); assertEquals(packet.toState(), decoded.toState());
    }

    @Test void syncRetainsCarrierOccurrenceUrlsTimeAndIsolatedZone() {
        for (int sourceType : new int[] {MP4PlaybackSyncPacket.SOURCE_PLAYER, MP4PlaybackSyncPacket.SOURCE_ITEM,
                MP4PlaybackSyncPacket.SOURCE_BLOCK, MP4PlaybackSyncPacket.SOURCE_CONTAINER_ENTITY}) {
            var packet = new MP4PlaybackSyncPacket(OWNER, SOURCE, sourceType, 123,
                    -123.25D, 65.5D, 4096.125D, true, 17, "https://cdn.example/a?x=1", "BV-source",
                    "中文音轨", 3600, 987, "session", 999999L, true, AreaAudioZone.isolated(OWNER));
            assertEquals(packet, roundTrip(MP4PlaybackSyncPacket.STREAM_CODEC, packet));
        }
    }

    @Test void stopSyncIsCompleteAndUsesAuthoritativeSource() {
        var packet = MP4PlaybackSyncPacket.stop(OWNER, SOURCE, 3);
        var decoded = roundTrip(MP4PlaybackSyncPacket.STREAM_CODEC, packet);
        assertEquals(packet, decoded); assertFalse(decoded.playing());
        assertEquals(SOURCE, decoded.sourceId()); assertEquals(3, decoded.queueIndex());
        assertEquals(AreaAudioZone.unrestricted(), decoded.areaAudioZone());
    }

    @Test void timelineRetainsSourceSessionClockRoutingAndArea() {
        var packet = new MP4PlaybackTimelinePacket(SOURCE, "session", 123456789012L, 987, true,
                AreaAudioZone.isolated(OWNER));
        assertEquals(packet, roundTrip(MP4PlaybackTimelinePacket.STREAM_CODEC, packet));
    }

    @Test void timelineNormalizesAbsentSessionNegativeClockAndOutOfRangeVolume() {
        var decoded = roundTrip(MP4PlaybackTimelinePacket.STREAM_CODEC,
                new MP4PlaybackTimelinePacket(SOURCE, null, -1L, 1001, false, null));
        assertEquals(new MP4PlaybackTimelinePacket(SOURCE, "", 0L, 1000, false), decoded);
    }

    @Test void truncatedTerminalPayloadFailsInsteadOfFabricatingCompletion() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            MP4PlaybackEndPacket.STREAM_CODEC.encode(buffer,
                    new MP4PlaybackEndPacket(SOURCE, "session", "token", PlaybackEndReason.NATURAL_EOF));
            buffer.writerIndex(buffer.writerIndex() - 1);
            assertThrows(IndexOutOfBoundsException.class, () -> MP4PlaybackEndPacket.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
