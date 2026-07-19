package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

class PadMapSnapshotDiskCodecTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripsSnapshotFile() throws Exception {
        Path path = Files.createTempFile(tempDir, "pad-map-snapshot", ".bin");
        try {
            PadMapTileKind[] tiles = new PadMapTileKind[] {
                    PadMapTileKind.UNKNOWN,
                    PadMapTileKind.GRASS,
                    null,
                    PadMapTileKind.WATER
            };
            PadMapSnapshotDiskCodec.write(new PadMapSnapshotDiskCodec.Snapshot(path, 10, 64, -20, 2, 2, 2, tiles));
            PadMapSnapshot snapshot = PadMapSnapshotDiskCodec.read(path, 2, 2, 2);
            if (snapshot == null) {
                throw new AssertionError("snapshot should round trip");
            }
            if (snapshot.centerX() != 10 || snapshot.centerY() != 64 || snapshot.centerZ() != -20
                    || snapshot.cellSizeBlocks() != 2 || snapshot.width() != 2 || snapshot.height() != 2) {
                throw new AssertionError("snapshot metadata did not round trip");
            }
            if (snapshot.tiles()[0] != PadMapTileKind.UNKNOWN
                    || snapshot.tiles()[1] != PadMapTileKind.GRASS
                    || snapshot.tiles()[2] != PadMapTileKind.UNKNOWN
                    || snapshot.tiles()[3] != PadMapTileKind.WATER) {
                throw new AssertionError("snapshot tiles did not round trip");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void rejectsUnexpectedShape() throws Exception {
        Path path = Files.createTempFile(tempDir, "pad-map-snapshot-shape", ".bin");
        try {
            PadMapTileKind[] tiles = new PadMapTileKind[] { PadMapTileKind.GRASS };
            PadMapSnapshotDiskCodec.write(new PadMapSnapshotDiskCodec.Snapshot(path, 0, 64, 0, 1, 1, 1, tiles));
            if (PadMapSnapshotDiskCodec.read(path, 2, 1, 1) != null) {
                throw new AssertionError("unexpected width should reject snapshot cache");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void rejectsInvalidHeader() throws Exception {
        Path path = Files.createTempFile(tempDir, "pad-map-snapshot-header", ".bin");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                out.writeInt(0x12345678);
                out.writeInt(PadMapDiskCacheFormat.SNAPSHOT.version());
            }
            if (PadMapSnapshotDiskCodec.read(path, 1, 1, 1) != null) {
                throw new AssertionError("invalid header should reject snapshot cache");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
