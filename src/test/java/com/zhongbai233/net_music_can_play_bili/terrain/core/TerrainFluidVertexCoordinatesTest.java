package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainFluidVertexCoordinatesTest {
    @Test
    void vanillaSectionLocalVerticesOnlyReceivePreviewHalfBlockOffset() {
        assertEquals(4.75F, TerrainFluidVertexCoordinates.previewX(5.25F));
        assertEquals(5.875F, TerrainFluidVertexCoordinates.previewY(5.875F));
        assertEquals(5.0F, TerrainFluidVertexCoordinates.previewZ(5.5F));
    }

    @Test
    void sectionOriginMatchesBlockModelHalfBlockConvention() {
        assertEquals(-0.5F, TerrainFluidVertexCoordinates.previewX(0.0F));
        assertEquals(0.0F, TerrainFluidVertexCoordinates.previewY(0.0F));
        assertEquals(-0.5F, TerrainFluidVertexCoordinates.previewZ(0.0F));
    }
}