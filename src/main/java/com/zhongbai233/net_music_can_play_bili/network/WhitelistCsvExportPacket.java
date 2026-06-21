package com.zhongbai233.net_music_can_play_bili.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 服务端白名单 CSV 下载到客户端本地。 */
public record WhitelistCsvExportPacket(String fileName, String csv) implements CustomPacketPayload {
    public static final Type<WhitelistCsvExportPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("whitelist_csv_export"));
    private static final int MAX_CSV_LENGTH = 1_048_576;

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistCsvExportPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WhitelistCsvExportPacket decode(RegistryFriendlyByteBuf buffer) {
            return new WhitelistCsvExportPacket(buffer.readUtf(128), buffer.readUtf(MAX_CSV_LENGTH));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, WhitelistCsvExportPacket packet) {
            buffer.writeUtf(packet.fileName(), 128);
            buffer.writeUtf(packet.csv(), MAX_CSV_LENGTH);
        }
    };

    public static WhitelistCsvExportPacket create(String csv) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return new WhitelistCsvExportPacket("net_music_can_play_bili_link_whitelist_" + timestamp + ".csv", csv);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WhitelistCsvExportPacket payload, IPayloadContext context) {
        context.enqueueWork(
                () -> com.zhongbai233.net_music_can_play_bili.client.WhitelistCsvExportClient.save(payload));
    }
}