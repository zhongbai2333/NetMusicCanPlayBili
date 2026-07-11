package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Lightweight self tests for cell disk cache binary codec. */
public final class PadMapCellDiskCodecSelfTest {
    private PadMapCellDiskCodecSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        roundTripsCellEntries();
        filtersUnknownEntriesOnRead();
        respectsEntryLimit();
        rejectsInvalidHeader();
        System.out.println("PadMapCellDiskCodecSelfTest passed");
    }

    private static void roundTripsCellEntries() throws Exception {
        Path path = Files.createTempFile("pad-map-cells", ".bin");
        try {
            PadMapCellDiskCodec.write(new PadMapCellDiskCodec.Snapshot(path, List.of(
                    new PadMapCellDiskCodec.Entry("overworld", 2, 10, -20, PadMapTileKind.GRASS),
                    new PadMapCellDiskCodec.Entry("nether", 4, -1, 7, PadMapTileKind.WATER))));
            List<PadMapCellDiskCodec.Entry> entries = PadMapCellDiskCodec.read(path, 16);
            if (entries == null || entries.size() != 2) {
                throw new AssertionError("cell entries should round trip");
            }
            PadMapCellDiskCodec.Entry first = entries.get(0);
            if (!"overworld".equals(first.dimension()) || first.cellSize() != 2 || first.cellX() != 10
                    || first.cellZ() != -20 || first.kind() != PadMapTileKind.GRASS) {
                throw new AssertionError("first cell entry metadata did not round trip: " + first);
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void filtersUnknownEntriesOnRead() throws Exception {
        Path path = Files.createTempFile("pad-map-cells-unknown", ".bin");
        try {
            PadMapCellDiskCodec.write(new PadMapCellDiskCodec.Snapshot(path, List.of(
                    new PadMapCellDiskCodec.Entry("overworld", 1, 1, 1, PadMapTileKind.UNKNOWN),
                    new PadMapCellDiskCodec.Entry("overworld", 1, 2, 2, PadMapTileKind.TREE))));
            List<PadMapCellDiskCodec.Entry> entries = PadMapCellDiskCodec.read(path, 16);
            if (entries == null || entries.size() != 1 || entries.get(0).kind() != PadMapTileKind.TREE) {
                throw new AssertionError("UNKNOWN cell entries should be skipped on read");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void respectsEntryLimit() throws Exception {
        Path path = Files.createTempFile("pad-map-cells-limit", ".bin");
        try {
            PadMapCellDiskCodec.write(new PadMapCellDiskCodec.Snapshot(path, List.of(
                    new PadMapCellDiskCodec.Entry("overworld", 1, 1, 1, PadMapTileKind.GRASS),
                    new PadMapCellDiskCodec.Entry("overworld", 1, 2, 2, PadMapTileKind.WATER))));
            List<PadMapCellDiskCodec.Entry> entries = PadMapCellDiskCodec.read(path, 1);
            if (entries == null || entries.size() != 1 || entries.get(0).cellX() != 1) {
                throw new AssertionError("entry limit should cap loaded cell entries");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void rejectsInvalidHeader() throws Exception {
        Path path = Files.createTempFile("pad-map-cells-header", ".bin");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                out.writeInt(0x12345678);
                out.writeInt(PadMapDiskCacheFormat.CELLS.version());
            }
            if (PadMapCellDiskCodec.read(path, 16) != null) {
                throw new AssertionError("invalid cell cache header should be rejected");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
