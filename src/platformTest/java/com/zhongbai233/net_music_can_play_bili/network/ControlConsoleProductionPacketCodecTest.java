package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.zhongbai233.net_music_can_play_bili.network.PacketCodecFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlConsoleProductionPacketCodecTest {
    private static final BlockPos POS = new BlockPos(-12345, -32, 98765);
    private static final UUID LEASE = new UUID(1L, 2L), OPERATION = new UUID(3L, 4L), OWNER = new UUID(5L, 6L);

    @Test void aclPacketPreservesAuthorityRevisionModeAndTrustedIdentities() {
        var packet = new ControlConsoleAccessPacket(POS, LEASE, OPERATION, 19L,
                ControlConsoleDocument.AccessMode.TRUSTED, Set.of(OWNER, OPERATION));
        assertEquals(packet, roundTrip(ControlConsoleAccessPacket.STREAM_CODEC, packet));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 257, Integer.MAX_VALUE})
    void accessDecoderRejectsInvalidTrustedPlayerCount(int count) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeAccessHeader(buffer); buffer.writeUtf("TRUSTED", 16); buffer.writeVarInt(count);
            assertThrows(IllegalArgumentException.class, () -> ControlConsoleAccessPacket.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void accessDecoderRejectsUnknownMode() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeAccessHeader(buffer); buffer.writeUtf("ADMIN", 16); buffer.writeVarInt(0);
            assertThrows(IllegalArgumentException.class, () -> ControlConsoleAccessPacket.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @ParameterizedTest @EnumSource(value = ControlConsoleEditLeasePacket.Action.class, names = {"OPEN", "RENEW"})
    void editLeaseActionsPreserveLeaseAndBlock(ControlConsoleEditLeasePacket.Action action) {
        var packet = new ControlConsoleEditLeasePacket(POS, action,
                action == ControlConsoleEditLeasePacket.Action.OPEN ? null : LEASE);
        assertEquals(packet, roundTrip(ControlConsoleEditLeasePacket.STREAM_CODEC, packet));
    }

    @Test void editLeaseDecoderRejectsUnknownActionAndMissingRenewalIdentity() {
        for (int action : new int[] {99, 1, 2}) {
            RegistryFriendlyByteBuf buffer = buffer();
            try {
                BlockPos.STREAM_CODEC.encode(buffer, POS); buffer.writeVarInt(action); buffer.writeBoolean(false);
                assertThrows(IllegalArgumentException.class, () -> ControlConsoleEditLeasePacket.STREAM_CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
    }

    @Test void consumerLeaseRetainsIdentityAndGeneration() {
        var packet = new ControlConsoleConsumerLeasePacket(POS,
                ControlConsoleConsumerLeasePacket.Action.ACQUIRE_OR_RENEW, LEASE, Long.MAX_VALUE);
        assertEquals(packet, roundTrip(ControlConsoleConsumerLeasePacket.STREAM_CODEC, packet));
    }

    @Test void consumerDecoderRejectsUnknownActionAndReleaseWithoutLease() {
        for (int action : new int[] {99, 1}) {
            RegistryFriendlyByteBuf buffer = buffer();
            try {
                BlockPos.STREAM_CODEC.encode(buffer, POS); buffer.writeVarInt(action); buffer.writeBoolean(false);
                buffer.writeVarLong(5L);
                assertThrows(IllegalArgumentException.class, () -> ControlConsoleConsumerLeasePacket.STREAM_CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
    }

    @Test void configurationPreservesAllElementTransformsAndEditorFields() {
        var packet = new ControlConsoleConfigPacket(POS, LEASE, OPERATION, 98L, "演出控制台", 64D, 31D, 96D,
                List.of(element()));
        assertEquals(packet, roundTrip(ControlConsoleConfigPacket.STREAM_CODEC, packet));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 4097, Integer.MAX_VALUE})
    void configurationDecoderRejectsUnboundedElementCount(int count) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeAccessHeader(buffer); buffer.writeUtf("console", 64);
            buffer.writeDouble(64D); buffer.writeDouble(32D); buffer.writeDouble(64D); buffer.writeVarInt(count);
            assertThrows(IllegalArgumentException.class, () -> ControlConsoleConfigPacket.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void configurationDecoderRejectsDuplicateElementIdentity() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeAccessHeader(buffer); buffer.writeUtf("console", 64);
            buffer.writeDouble(64D); buffer.writeDouble(32D); buffer.writeDouble(64D); buffer.writeVarInt(2);
            ControlConsoleConfigPacket.writeElement(buffer, element());
            ControlConsoleConfigPacket.writeElement(buffer, element());
            assertThrows(IllegalArgumentException.class, () -> ControlConsoleConfigPacket.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void conflictResponsePreservesAuthoritativeAclBindingAndFullDocument() {
        var document = new ControlConsoleDocument(7, OWNER, 99L, OWNER,
                ControlConsoleDocument.AccessMode.TRUSTED, Set.of(LEASE), "控制台",
                "minecraft:the_nether", ControlConsoleDocument.SourceKind.LIVE_STREAMER, -4, 65, 4096,
                100D, 20D, 30D, List.of(element()));
        var packet = new ControlConsoleConfigResultPacket(POS, OPERATION, 99L,
                ControlConsoleConfigResultPacket.Status.CONFLICT, document);
        assertEquals(packet, roundTrip(ControlConsoleConfigResultPacket.STREAM_CODEC, packet));
    }

    @Test void authoritativeDocumentDecoderRejectsRepeatedTrustedIdentity() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            buffer.writeVarInt(7); buffer.writeUUID(OWNER); buffer.writeLong(10L); buffer.writeBoolean(true);
            buffer.writeUUID(OWNER); buffer.writeUtf("TRUSTED", 16); buffer.writeVarInt(2);
            buffer.writeUUID(LEASE); buffer.writeUUID(LEASE);
            assertThrows(IllegalArgumentException.class, () -> ControlConsoleDocumentPacketCodec.decode(buffer));
        } finally { buffer.release(); }
    }

    private static void writeAccessHeader(RegistryFriendlyByteBuf buffer) {
        BlockPos.STREAM_CODEC.encode(buffer, POS); buffer.writeUUID(LEASE); buffer.writeUUID(OPERATION); buffer.writeLong(19L);
    }
    private static ControlConsoleElement element() {
        return new ControlConsoleElement(LEASE, ControlConsoleElement.Type.SUBTITLE, "字幕",
                8.5F, -2.25F, 3.5F, 1.5F, 1.777F, 35F, 10F, -15F,
                "LYRICS", "歌词与翻译", true, true, 1.25F, 0xFF112233, 0.75F, 2, 48F, true,
                0xFF445566, 0x77112233, ControlConsoleElement.Alignment.CENTER, 3.5F, true,
                false, true, 1.2F, 0.8F, 1.7F, 0.25F, -0.5F, 0.75F, 0.2F, -0.3F, 0.65F);
    }
}
