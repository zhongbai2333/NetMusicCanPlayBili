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

/** Binary codec for the Pad map cell disk cache file. */
final class PadMapCellDiskCodec {
    private PadMapCellDiskCodec() {
    }

    static void write(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.createDirectories(snapshot.path().getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(snapshot.path())))) {
                out.writeInt(PadMapDiskCacheFormat.CELLS.magic());
                out.writeInt(PadMapDiskCacheFormat.CELLS.version());
                out.writeInt(snapshot.entries().size());
                for (Entry entry : snapshot.entries()) {
                    out.writeUTF(entry.dimension());
                    out.writeInt(entry.cellSize());
                    out.writeInt(entry.cellX());
                    out.writeInt(entry.cellZ());
                    out.writeByte(PadMapDiskCacheFormat.encodeTile(entry.kind()));
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
            if (!PadMapDiskCacheFormat.CELLS.matches(in.readInt(), in.readInt())) {
                return null;
            }
            int count = Math.max(0, Math.min(in.readInt(), Math.max(0, entryLimit)));
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String dimension = in.readUTF();
                int cellSize = in.readInt();
                int cellX = in.readInt();
                int cellZ = in.readInt();
                PadMapTileKind kind = PadMapDiskCacheFormat.decodeTile(in.readUnsignedByte());
                if (kind == null) {
                    return null;
                }
                if (kind != PadMapTileKind.UNKNOWN) {
                    entries.add(new Entry(dimension, cellSize, cellX, cellZ, kind));
                }
            }
            return entries;
        } catch (IOException ignored) {
            return null;
        }
    }

    record Entry(String dimension, int cellSize, int cellX, int cellZ, PadMapTileKind kind) {
    }

    record Snapshot(Path path, List<Entry> entries) {
    }
}
