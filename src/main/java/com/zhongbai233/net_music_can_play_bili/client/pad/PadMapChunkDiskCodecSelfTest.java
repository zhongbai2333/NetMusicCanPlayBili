package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Lightweight self tests for chunk disk cache binary codec. */
public final class PadMapChunkDiskCodecSelfTest {
    private PadMapChunkDiskCodecSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        roundTripsChunkEntries();
        padsShortTileArraysAsUnknown();
        respectsEntryLimit();
        rejectsInvalidHeader();
        System.out.println("PadMapChunkDiskCodecSelfTest passed");
    }

    private static void roundTripsChunkEntries() throws Exception {
        Path path = Files.createTempFile("pad-map-chunks", ".bin");
        try {
            PadMapTileKind[] tiles = filledTiles(PadMapTileKind.GRASS);
            tiles[42] = PadMapTileKind.WATER;
            PadMapChunkDiskCodec.write(new PadMapChunkDiskCodec.Snapshot(path, List.of(
                    new PadMapChunkDiskCodec.Entry("overworld", 1, -2, 4, 64, tiles))));
            List<PadMapChunkDiskCodec.Entry> entries = PadMapChunkDiskCodec.read(path, 16);
            if (entries == null || entries.size() != 1) {
                throw new AssertionError("chunk entry should round trip");
            }
            PadMapChunkDiskCodec.Entry entry = entries.get(0);
            if (!"overworld".equals(entry.dimension()) || entry.chunkX() != 1 || entry.chunkZ() != -2
                    || entry.cellSize() != 4 || entry.floorY() != 64) {
                throw new AssertionError("chunk metadata did not round trip: " + entry);
            }
            if (entry.tiles().length != PadMapChunkDiskCodec.TILE_COUNT
                    || entry.tiles()[0] != PadMapTileKind.GRASS
                    || entry.tiles()[42] != PadMapTileKind.WATER) {
                throw new AssertionError("chunk tiles did not round trip");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void padsShortTileArraysAsUnknown() throws Exception {
        Path path = Files.createTempFile("pad-map-chunks-short", ".bin");
        try {
            PadMapTileKind[] tiles = new PadMapTileKind[] { PadMapTileKind.TREE };
            PadMapChunkDiskCodec.write(new PadMapChunkDiskCodec.Snapshot(path, List.of(
                    new PadMapChunkDiskCodec.Entry("overworld", 0, 0, 1, 64, tiles))));
            List<PadMapChunkDiskCodec.Entry> entries = PadMapChunkDiskCodec.read(path, 16);
            if (entries == null || entries.get(0).tiles()[0] != PadMapTileKind.TREE
                    || entries.get(0).tiles()[1] != PadMapTileKind.UNKNOWN) {
                throw new AssertionError("short tile arrays should be padded as UNKNOWN");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void respectsEntryLimit() throws Exception {
        Path path = Files.createTempFile("pad-map-chunks-limit", ".bin");
        try {
            PadMapChunkDiskCodec.write(new PadMapChunkDiskCodec.Snapshot(path, List.of(
                    new PadMapChunkDiskCodec.Entry("overworld", 1, 1, 1, 64, filledTiles(PadMapTileKind.GRASS)),
                    new PadMapChunkDiskCodec.Entry("overworld", 2, 2, 1, 64, filledTiles(PadMapTileKind.WATER)))));
            List<PadMapChunkDiskCodec.Entry> entries = PadMapChunkDiskCodec.read(path, 1);
            if (entries == null || entries.size() != 1 || entries.get(0).chunkX() != 1) {
                throw new AssertionError("entry limit should cap loaded chunk entries");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void rejectsInvalidHeader() throws Exception {
        Path path = Files.createTempFile("pad-map-chunks-header", ".bin");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                out.writeInt(0x12345678);
                out.writeInt(PadMapDiskCacheFormat.CHUNKS.version());
            }
            if (PadMapChunkDiskCodec.read(path, 16) != null) {
                throw new AssertionError("invalid chunk cache header should be rejected");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static PadMapTileKind[] filledTiles(PadMapTileKind kind) {
        PadMapTileKind[] tiles = new PadMapTileKind[PadMapChunkDiskCodec.TILE_COUNT];
        java.util.Arrays.fill(tiles, kind);
        return tiles;
    }
}
