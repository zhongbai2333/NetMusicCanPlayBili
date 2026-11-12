package com.zhongbai233.net_music_can_play_bili.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.connection.ConnectionType;
import static org.junit.jupiter.api.Assertions.*;

/** Real NeoForge buffer; no game process, network connection, or product codec substitute. */
final class PacketCodecFixture {
    private PacketCodecFixture() {}
    static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
    }
    static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        return roundTrip(codec, value, RegistryAccess.EMPTY);
    }
    static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value, RegistryAccess registries) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, ConnectionType.OTHER);
        try {
            codec.encode(buffer, value);
            T decoded = codec.decode(buffer);
            assertEquals(0, buffer.readableBytes(), "codec must consume exactly one complete payload");
            return decoded;
        } finally { buffer.release(); }
    }
}
