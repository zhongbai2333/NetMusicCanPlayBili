package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.network.WhitelistCsvExportPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 客户端将服务端分块下发的白名单 CSV 流式写入临时文件，校验后再原子落盘。 */
public final class WhitelistCsvExportClient {
    private static final long TRANSFER_TIMEOUT_MILLIS = 120_000L;
    private static final int MAX_ACTIVE_TRANSFERS = 4;
    private static final Map<String, Transfer> TRANSFERS = new LinkedHashMap<>();

    private WhitelistCsvExportClient() {
    }

    public static synchronized void save(WhitelistCsvExportPacket payload) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        pruneExpired(now);
        try {
            validate(payload);
            Transfer transfer = TRANSFERS.get(payload.transferId());
            if (transfer == null) {
                if (payload.chunkIndex() != 0) {
                    throw new IOException("传输必须从第一个分块开始");
                }
                evictOldestIfFull();
                transfer = Transfer.create(minecraft, payload, now);
                TRANSFERS.put(payload.transferId(), transfer);
                scheduleExpiry(payload.transferId(), transfer.temporary, TRANSFER_TIMEOUT_MILLIS);
            } else if (!transfer.matches(payload)) {
                discard(payload.transferId(), transfer);
                throw new IOException("分块元数据不一致");
            }

            transfer.accept(payload, now);
            if (!transfer.complete()) {
                return;
            }
            Path path = transfer.finish();
            TRANSFERS.remove(payload.transferId());
            notifyPlayer(minecraft, "白名单 CSV 已导出到本地：" + path.toAbsolutePath());
        } catch (Exception e) {
            if (payload != null && payload.transferId() != null) {
                Transfer transfer = TRANSFERS.remove(payload.transferId());
                deleteTemporary(transfer);
            }
            notifyPlayer(minecraft, "白名单 CSV 导出失败：" + e.getMessage());
        }
    }

    private static void validate(WhitelistCsvExportPacket payload) throws IOException {
        if (payload == null || payload.transferId() == null || payload.transferId().isBlank()) {
            throw new IOException("缺少传输标识");
        }
        if (payload.chunkCount() <= 0 || payload.chunkCount() > WhitelistCsvExportPacket.MAX_CHUNKS
                || payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.chunkCount()) {
            throw new IOException("分块序号无效");
        }
        if (payload.totalBytes() < 0 || payload.totalBytes() > WhitelistCsvExportPacket.MAX_TOTAL_BYTES
                || payload.chunk() == null || payload.chunk().length > WhitelistCsvExportPacket.CHUNK_BYTES) {
            throw new IOException("分块大小无效");
        }
    }

    private static void pruneExpired(long now) {
        Iterator<Map.Entry<String, Transfer>> iterator = TRANSFERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Transfer transfer = iterator.next().getValue();
            if (now - transfer.updatedAt > TRANSFER_TIMEOUT_MILLIS) {
                iterator.remove();
                deleteTemporary(transfer);
            }
        }
    }

    private static void evictOldestIfFull() {
        if (TRANSFERS.size() < MAX_ACTIVE_TRANSFERS) {
            return;
        }
        Iterator<Map.Entry<String, Transfer>> iterator = TRANSFERS.entrySet().iterator();
        if (iterator.hasNext()) {
            Transfer transfer = iterator.next().getValue();
            iterator.remove();
            deleteTemporary(transfer);
        }
    }

    private static void discard(String transferId, Transfer transfer) {
        TRANSFERS.remove(transferId);
        deleteTemporary(transfer);
    }

    private static void deleteTemporary(Transfer transfer) {
        if (transfer == null) {
            return;
        }
        transfer.closeQuietly();
        try {
            Files.deleteIfExists(transfer.temporary);
        } catch (IOException ignored) {
        }
    }

    private static void scheduleExpiry(String transferId, Path temporary, long delayMillis) {
        CompletableFuture.delayedExecutor(Math.max(1L, delayMillis), TimeUnit.MILLISECONDS)
                .execute(() -> expireTransfer(transferId, temporary));
    }

    private static synchronized void expireTransfer(String transferId, Path temporary) {
        Transfer transfer = TRANSFERS.get(transferId);
        if (transfer == null || !transfer.temporary.equals(temporary)) {
            return;
        }
        long remaining = TRANSFER_TIMEOUT_MILLIS - (System.currentTimeMillis() - transfer.updatedAt);
        if (remaining > 0L) {
            scheduleExpiry(transferId, temporary, remaining);
            return;
        }
        TRANSFERS.remove(transferId);
        deleteTemporary(transfer);
    }

    private static void cleanupAbandoned(Path directory, long now) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, ".ncpb-whitelist-*.part")) {
            for (Path file : files) {
                try {
                    long age = now - Files.getLastModifiedTime(file).toMillis();
                    if (age >= TRANSFER_TIMEOUT_MILLIS) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void notifyPlayer(Minecraft minecraft, String message) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal(message));
        }
    }

    private static String safeFileName(String value) {
        String name = value == null || value.isBlank() ? "net_music_can_play_bili_link_whitelist.csv" : value;
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return safe.isBlank() || ".".equals(safe) || "..".equals(safe)
                ? "net_music_can_play_bili_link_whitelist.csv"
                : safe;
    }

    private static String safeTransferId(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private static final class Transfer {
        private final String fileName;
        private final int chunkCount;
        private final int totalBytes;
        private final Path target;
        private final Path temporary;
        private final OutputStream output;
        private int nextChunkIndex;
        private int receivedBytes;
        private long updatedAt;
        private boolean closed;

        private Transfer(String fileName, int chunkCount, int totalBytes, Path target, Path temporary,
                OutputStream output, long now) {
            this.fileName = fileName;
            this.chunkCount = chunkCount;
            this.totalBytes = totalBytes;
            this.target = target;
            this.temporary = temporary;
            this.output = output;
            this.updatedAt = now;
        }

        private static Transfer create(Minecraft minecraft, WhitelistCsvExportPacket first, long now)
                throws IOException {
            Path dir = minecraft.gameDirectory.toPath().resolve("exports").resolve("net_music_can_play_bili");
            Files.createDirectories(dir);
            cleanupAbandoned(dir, now);
            String fileName = safeFileName(first.fileName());
            Path target = dir.resolve(fileName);
            Path temporary = dir.resolve(".ncpb-whitelist-" + safeTransferId(first.transferId()) + ".part");
            Files.deleteIfExists(temporary);
            OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return new Transfer(first.fileName(), first.chunkCount(), first.totalBytes(),
                    target, temporary, output, now);
        }

        private boolean matches(WhitelistCsvExportPacket packet) {
            return chunkCount == packet.chunkCount()
                    && totalBytes == packet.totalBytes()
                    && java.util.Objects.equals(fileName, packet.fileName());
        }

        private void accept(WhitelistCsvExportPacket packet, long now) throws IOException {
            if (packet.chunkIndex() != nextChunkIndex) {
                throw new IOException("CSV 分块乱序或重复");
            }
            int nextBytes = receivedBytes + packet.chunk().length;
            if (nextBytes > totalBytes) {
                throw new IOException("收到的数据超过声明长度");
            }
            output.write(packet.chunk());
            nextChunkIndex++;
            receivedBytes = nextBytes;
            updatedAt = now;
        }

        private boolean complete() {
            return nextChunkIndex == chunkCount;
        }

        private Path finish() throws IOException {
            if (!complete() || receivedBytes != totalBytes) {
                throw new IOException("分块不完整或总长度不匹配");
            }
            closeOutput();
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        }

        private void closeOutput() throws IOException {
            if (!closed) {
                output.close();
                closed = true;
            }
        }

        private void closeQuietly() {
            try {
                closeOutput();
            } catch (IOException ignored) {
            }
        }
    }
}
