package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainNeighborhoodIndexTest {
    @Test
    void mapsEveryCellInTwentyCubedNeighborhoodExactlyOnce() {
        Set<Integer> indices = new HashSet<>();
        for (int y = TerrainNeighborhoodIndex.MIN_LOCAL;
                y <= TerrainNeighborhoodIndex.MAX_LOCAL; y++) {
            for (int z = TerrainNeighborhoodIndex.MIN_LOCAL;
                    z <= TerrainNeighborhoodIndex.MAX_LOCAL; z++) {
                for (int x = TerrainNeighborhoodIndex.MIN_LOCAL;
                        x <= TerrainNeighborhoodIndex.MAX_LOCAL; x++) {
                    assertTrue(indices.add(TerrainNeighborhoodIndex.index(x, y, z)));
                }
            }
        }

        assertEquals(TerrainNeighborhoodIndex.CELL_COUNT, indices.size());
        assertEquals(0, TerrainNeighborhoodIndex.index(-2, -2, -2));
        assertEquals(TerrainNeighborhoodIndex.CELL_COUNT - 1,
                TerrainNeighborhoodIndex.index(17, 17, 17));
        assertThrows(IndexOutOfBoundsException.class,
                () -> TerrainNeighborhoodIndex.index(18, 0, 0));
    }

    @Test
    void mapsBorderCellsToThreeByThreeNeighborChunks() {
        assertEquals(0, TerrainNeighborhoodIndex.neighborChunkIndex(-2, -2));
        assertEquals(4, TerrainNeighborhoodIndex.neighborChunkIndex(0, 0));
        assertEquals(4, TerrainNeighborhoodIndex.neighborChunkIndex(15, 15));
        assertEquals(8, TerrainNeighborhoodIndex.neighborChunkIndex(17, 17));
        assertEquals(3, TerrainNeighborhoodIndex.neighborChunkIndex(-1, 8));
        assertEquals(5, TerrainNeighborhoodIndex.neighborChunkIndex(16, 8));
        assertThrows(IndexOutOfBoundsException.class,
                () -> TerrainNeighborhoodIndex.neighborChunkIndex(18, 0));
    }
}