package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** 服务端白名单 CSV 分块下载到客户端本地。 */
public record WhitelistCsvExportPacket(String transferId, String fileName, int chunkIndex, int chunkCount,
        int totalBytes, byte[] chunk) implements CustomPacketPayload {
    public static final Type<WhitelistCsvExportPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("whitelist_csv_export"));
    public static final int CHUNK_BYTES = 64 * 1024;
    public static final int MAX_CHUNKS = 1024;
    public static final int MAX_TOTAL_BYTES = CHUNK_BYTES * MAX_CHUNKS;
    private static final int MAX_TRANSFER_ID_LENGTH = 64;
    private static final int MAX_FILE_NAME_LENGTH = 128;

    public WhitelistCsvExportPacket(String fileName, String csv) {
        this(singlePacket(fileName, csv));
    }

    private WhitelistCsvExportPacket(WhitelistCsvExportPacket packet) {
        this(packet.transferId, packet.fileName, packet.chunkIndex, packet.chunkCount,
                packet.totalBytes, packet.chunk);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistCsvExportPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WhitelistCsvExportPacket decode(RegistryFriendlyByteBuf buffer) {
            String transferId = buffer.readUtf(MAX_TRANSFER_ID_LENGTH);
            String fileName = buffer.readUtf(MAX_FILE_NAME_LENGTH);
            int chunkIndex = readBoundedInt(buffer, MAX_CHUNKS - 1, "chunk index");
            int chunkCount = readBoundedInt(buffer, MAX_CHUNKS, "chunk count");
            int totalBytes = readBoundedInt(buffer, MAX_TOTAL_BYTES, "total bytes");
            byte[] chunk = buffer.readByteArray(CHUNK_BYTES);
            WhitelistCsvExportPacket packet = new WhitelistCsvExportPacket(
                    transferId, fileName, chunkIndex, chunkCount, totalBytes, chunk);
            validate(packet, false);
            return packet;
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, WhitelistCsvExportPacket packet) {
            validate(packet, true);
            buffer.writeUtf(packet.transferId(), MAX_TRANSFER_ID_LENGTH);
            buffer.writeUtf(packet.fileName(), MAX_FILE_NAME_LENGTH);
            buffer.writeVarInt(packet.chunkIndex());
            buffer.writeVarInt(packet.chunkCount());
            buffer.writeVarInt(packet.totalBytes());
            buffer.writeByteArray(packet.chunk());
        }
    };

    public static List<WhitelistCsvExportPacket> createChunks(String csv) {
        byte[] bytes = encodeCsv(csv);
        TransferInfo transfer = createTransfer(bytes.length);
        List<WhitelistCsvExportPacket> packets = new ArrayList<>(transfer.chunkCount());
        for (int index = 0; index < transfer.chunkCount(); index++) {
            packets.add(packetAt(transfer, bytes, index));
        }
        return List.copyOf(packets);
    }

    /** 生产路径：限频后才生成 CSV，且逐块发送，不在内存中保留第二份完整分块列表。 */
    public static int exportTo(ServerPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("缺少 CSV 导出玩家");
        }
        if (!NetworkRateLimiter.allow(player.getUUID(), "whitelist_csv_export", 1)) {
            throw new IllegalArgumentException("导出过于频繁，请稍后再试");
        }
        return sendTo(player, BiliWhitelistManager.exportCsv(player.level().getServer()));
    }

    public static int sendTo(ServerPlayer player, String csv) {
        if (player == null) {
            throw new IllegalArgumentException("缺少 CSV 导出玩家");
        }
        byte[] bytes = encodeCsv(csv);
        TransferInfo transfer = createTransfer(bytes.length);
        for (int index = 0; index < transfer.chunkCount(); index++) {
            PacketDistributor.sendToPlayer(player, packetAt(transfer, bytes, index));
        }
        return transfer.chunkCount();
    }

    private static byte[] encodeCsv(String csv) {
        byte[] bytes = (csv == null ? "" : csv).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("白名单 CSV 超过 64 MiB 安全传输上限");
        }
        return bytes;
    }

    private static TransferInfo createTransfer(int totalBytes) {
        String transferId = UUID.randomUUID().toString();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        String fileName = "net_music_can_play_bili_link_whitelist_" + timestamp + "-"
                + transferId.substring(0, 8) + ".csv";
        int chunkCount = Math.max(1, (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES);
        return new TransferInfo(transferId, fileName, chunkCount, totalBytes);
    }

    private static WhitelistCsvExportPacket packetAt(TransferInfo transfer, byte[] bytes, int index) {
        int start = index * CHUNK_BYTES;
        int end = Math.min(bytes.length, start + CHUNK_BYTES);
        return new WhitelistCsvExportPacket(transfer.transferId(), transfer.fileName(), index,
                transfer.chunkCount(), transfer.totalBytes(), Arrays.copyOfRange(bytes, start, end));
    }

    private static WhitelistCsvExportPacket singlePacket(String fileName, String csv) {
        byte[] bytes = (csv == null ? "" : csv).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CHUNK_BYTES) {
            throw new IllegalArgumentException("单包 CSV 构造器只支持不超过 64 KiB 的测试数据");
        }
        return new WhitelistCsvExportPacket(UUID.randomUUID().toString(),
                fileName == null ? "" : fileName, 0, 1, bytes.length, bytes);
    }

    private static int readBoundedInt(RegistryFriendlyByteBuf buffer, int maximum, String field) {
        int value = buffer.readVarInt();
        if (value < 0 || value > maximum) {
            throw new DecoderException("Invalid whitelist CSV " + field + ": " + value);
        }
        return value;
    }

    private static void validate(WhitelistCsvExportPacket packet, boolean encoding) {
        String message = null;
        if (packet == null || packet.transferId() == null || packet.transferId().isBlank()
                || packet.transferId().length() > MAX_TRANSFER_ID_LENGTH) {
            message = "invalid transfer id";
        } else if (packet.fileName() == null || packet.fileName().length() > MAX_FILE_NAME_LENGTH) {
            message = "invalid file name";
        } else if (packet.chunkCount() <= 0 || packet.chunkCount() > MAX_CHUNKS) {
            message = "invalid chunk count";
        } else if (packet.chunkIndex() < 0 || packet.chunkIndex() >= packet.chunkCount()) {
            message = "invalid chunk index";
        } else if (packet.totalBytes() < 0 || packet.totalBytes() > MAX_TOTAL_BYTES) {
            message = "invalid total byte count";
        } else if (packet.chunk() == null || packet.chunk().length > CHUNK_BYTES) {
            message = "invalid chunk payload";
        }
        if (message != null) {
            if (encoding) {
                throw new EncoderException("Cannot encode whitelist CSV packet: " + message);
            }
            throw new DecoderException("Cannot decode whitelist CSV packet: " + message);
        }
    }

    private record TransferInfo(String transferId, String fileName, int chunkCount, int totalBytes) {
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
