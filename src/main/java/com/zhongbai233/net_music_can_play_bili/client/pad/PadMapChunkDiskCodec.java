package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Binary codec for the Pad map chunk disk cache file. */
final class PadMapChunkDiskCodec {
    static final int TILE_COUNT = 16 * 16;

    private PadMapChunkDiskCodec() {
    }

    static void write(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.createDirectories(snapshot.path().getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(snapshot.path())))) {
                out.writeInt(PadMapDiskCacheFormat.CHUNKS.magic());
                out.writeInt(PadMapDiskCacheFormat.CHUNKS.version());
                out.writeInt(snapshot.entries().size());
                for (Entry entry : snapshot.entries()) {
                    out.writeUTF(entry.dimension());
                    out.writeInt(entry.chunkX());
                    out.writeInt(entry.chunkZ());
                    out.writeInt(entry.cellSize());
                    out.writeInt(entry.floorY());
                    for (int i = 0; i < TILE_COUNT; i++) {
                        PadMapTileKind tile = i < entry.tiles().length ? entry.tiles()[i] : PadMapTileKind.UNKNOWN;
                        out.writeByte(PadMapDiskCacheFormat.encodeTile(tile));
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    static List<Entry> read(Path path, int entryLimit) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (!PadMapDiskCacheFormat.CHUNKS.matches(in.readInt(), in.readInt())) {
                return null;
            }
            int count = Math.max(0, Math.min(in.readInt(), Math.max(0, entryLimit)));
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String dimension = in.readUTF();
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                int cellSize = in.readInt();
                int floorY = in.readInt();
                PadMapTileKind[] tiles = new PadMapTileKind[TILE_COUNT];
                for (int j = 0; j < tiles.length; j++) {
                    PadMapTileKind kind = PadMapDiskCacheFormat.decodeTile(in.readUnsignedByte());
                    if (kind == null) {
                        return null;
                    }
                    tiles[j] = kind;
                }
                entries.add(new Entry(dimension, chunkX, chunkZ, cellSize, floorY, tiles));
            }
            return entries;
        } catch (IOException ignored) {
            return null;
        }
    }

    record Entry(String dimension, int chunkX, int chunkZ, int cellSize, int floorY, PadMapTileKind[] tiles) {
    }

    record Snapshot(Path path, List<Entry> entries) {
    }
}
