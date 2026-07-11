package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Binary codec for the Pad map snapshot disk cache file. */
final class PadMapSnapshotDiskCodec {
    private PadMapSnapshotDiskCodec() {
    }

    static void write(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.createDirectories(snapshot.path().getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(snapshot.path())))) {
                out.writeInt(PadMapDiskCacheFormat.SNAPSHOT.magic());
                out.writeInt(PadMapDiskCacheFormat.SNAPSHOT.version());
                out.writeInt(snapshot.centerX());
                out.writeInt(snapshot.centerY());
                out.writeInt(snapshot.centerZ());
                out.writeInt(snapshot.cellSizeBlocks());
                out.writeInt(snapshot.width());
                out.writeInt(snapshot.height());
                out.writeInt(snapshot.tiles().length);
                for (PadMapTileKind tile : snapshot.tiles()) {
                    out.writeByte(PadMapDiskCacheFormat.encodeTile(tile));
                }
            }
        } catch (IOException ignored) {
        }
    }

    static PadMapSnapshot read(Path path, int expectedWidth, int expectedHeight, int expectedCellSize) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (!PadMapDiskCacheFormat.SNAPSHOT.matches(in.readInt(), in.readInt())) {
                return null;
            }
            int centerX = in.readInt();
            int centerY = in.readInt();
            int centerZ = in.readInt();
            int cellSize = in.readInt();
            int width = in.readInt();
            int height = in.readInt();
            int length = in.readInt();
            if (width != expectedWidth || height != expectedHeight || cellSize != expectedCellSize
                    || length != width * height) {
                return null;
            }
            PadMapTileKind[] tiles = new PadMapTileKind[length];
            for (int i = 0; i < length; i++) {
                PadMapTileKind kind = PadMapDiskCacheFormat.decodeTile(in.readUnsignedByte());
                if (kind == null) {
                    return null;
                }
                tiles[i] = kind;
            }
            return new PadMapSnapshot(centerX, centerY, centerZ, cellSize, width, height, tiles);
        } catch (IOException ignored) {
            return null;
        }
    }

    record Snapshot(Path path, int centerX, int centerY, int centerZ, int cellSizeBlocks, int width, int height,
            PadMapTileKind[] tiles) {
    }
}
