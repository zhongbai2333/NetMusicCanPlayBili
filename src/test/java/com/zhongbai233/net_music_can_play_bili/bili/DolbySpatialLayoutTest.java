package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DolbySpatialLayoutTest {
    @Test
    void mapsFivePointOneBedIntoJocOrderWithoutLfe() {
        float[][] pcm = new float[6][300];
        for (int channel = 0; channel < pcm.length; channel++) {
            Arrays.fill(pcm[channel], channel + 1.0f);
        }

        float[][] block = DolbySpatialLayout.buildJocDownmixBlock(pcm, 6, 5, 32);

        assertEquals(5, block.length);
        assertEquals(1.0f, block[0][0]);
        assertEquals(3.0f, block[1][0]);
        assertEquals(2.0f, block[2][0]);
        assertEquals(5.0f, block[3][0]);
        assertEquals(6.0f, block[4][0]);
    }

    @Test
    void preservesKnownBedLayoutAndObjectStatistics() {
        float[][] positions = DolbySpatialLayout.computeBedPositions(6, 1.5f);
        assertArrayEquals(new float[] { 0.0f, 0.0f, 0.0f }, positions[3], 0.00001f);
        assertEquals(1.5f, positions[2][2], 0.00001f);
        assertArrayEquals(new String[] { "FL", "FR", "FC", "LFE", "SL", "SR" },
                DolbySpatialLayout.bedChannelNames(6));
        assertEquals(2, DolbySpatialLayout.centerChannelIndex(6));

        float[][] objects = { { 1.0f, -1.0f }, { 0.0f, 0.0f }, { 0.25f, 0.25f } };
        float[] rms = DolbySpatialLayout.rmsByObject(objects, 3);
        float[] peaks = DolbySpatialLayout.peakByObject(objects, 3);
        assertArrayEquals(new float[] { 1.0f, 0.0f, 0.25f }, rms, 0.00001f);
        assertArrayEquals(new float[] { 1.0f, 0.0f, 0.25f }, peaks, 0.00001f);
        assertEquals(2, DolbySpatialLayout.countActiveObjects(rms));
        assertEquals(1.0f, DolbySpatialLayout.max(peaks));
    }
}
