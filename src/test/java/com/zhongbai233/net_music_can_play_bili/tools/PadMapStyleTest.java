package com.zhongbai233.net_music_can_play_bili.tools;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapStyleProcessor;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

class PadMapStyleTest {
    @Test
    void fillsSingleCellHoleInBuilding() {
        PadMapSnapshot snapshot = snapshot(7, 7, new String[] {
                ".......",
                ".FFFF..",
                ".FFFF..",
                ".FFFF..",
                ".......",
                ".......",
                ".......",
        });
        snapshot = new PadMapSnapshot(snapshot.centerX(), snapshot.centerZ(), snapshot.cellSizeBlocks(),
                snapshot.width(), snapshot.height(), withBuildings(snapshot, new String[] {
                        ".......",
                        ".BBBB..",
                        ".B.BB..",
                        ".BBBB..",
                        ".......",
                        ".......",
                        ".......",
                }), 1.0F);
        PadMapStyleProcessor.StyledMap styled = new PadMapStyleProcessor().style(snapshot);
        assertMask(styled.buildingCore(), styled.width(), 3, 2, "building core should fill a one-cell interior gap");
    }

    @Test
    void fillsOutdoorSingleCellHoleWithoutBridging() {
        PadMapSnapshot snapshot = snapshot(7, 5, new String[] {
                ".......",
                ".BBB...",
                ".B.B...",
                ".BBB...",
                ".......",
        });
        PadMapStyleProcessor.StyledMap styled = new PadMapStyleProcessor().style(snapshot);
        assertMask(styled.buildingZone(), styled.width(), 4, 2, "outdoor building should fill one-cell pinhole");
        snapshot = snapshot(7, 5, new String[] {
                ".......",
                ".BB.BB.",
                ".BB.BB.",
                ".......",
                ".......",
        });
        styled = new PadMapStyleProcessor().style(snapshot);
        if (styled.buildingZone()[index(styled.width(), 3, 1)] || styled.buildingZone()[index(styled.width(), 3, 2)]) {
            throw new AssertionError("outdoor pinhole fill should not bridge separated buildings");
        }
    }

    @Test
    void fillsEnclosedOutdoorCourtyard() {
        PadMapSnapshot snapshot = snapshot(11, 9, new String[] {
                "...........",
                ".BBBBBBBBB.",
                ".BBBBBBBBB.",
                ".BBB...BBB.",
                ".BBB...BBB.",
                ".BBB...BBB.",
                ".BBBBBBBBB.",
                ".BBBBBBBBB.",
                "...........",
        });
        PadMapStyleProcessor.StyledMap styled = new PadMapStyleProcessor().style(snapshot);
        assertMask(styled.buildingZone(), styled.width(), 5, 4, "enclosed outdoor courtyard should be filled");
        assertMask(styled.buildingCore(), styled.width(), 5, 4, "filled courtyard should participate in building core");
    }

    @Test
    void doesNotFillLargeCourtyardPlaza() {
        PadMapSnapshot snapshot = snapshot(17, 13, new String[] {
                ".................",
                ".BBBBBBBBBBBBBBB.",
                ".BBBBBBBBBBBBBBB.",
                ".BBB.........BBB.",
                ".BBB.........BBB.",
                ".BBB.........BBB.",
                ".BBB.........BBB.",
                ".BBB.........BBB.",
                ".BBB.........BBB.",
                ".BBBBBBBBBBBBBBB.",
                ".BBBBBBBBBBBBBBB.",
                ".................",
                ".................",
        });
        PadMapStyleProcessor.StyledMap styled = new PadMapStyleProcessor().style(snapshot);
        if (styled.buildingZone()[index(styled.width(), 8, 6)] || styled.buildingCore()[index(styled.width(), 8, 6)]) {
            throw new AssertionError("large courtyard plaza should remain open and not become building zone/core");
        }
    }

    @Test
    void keepsOpenCourtyardOpen() {
        PadMapSnapshot snapshot = snapshot(11, 9, new String[] {
                "...........",
                ".BBBBBBBBB.",
                ".BBBBBBBBB.",
                ".BBB....BB.",
                ".BBB....BB.",
                ".BBB....BB.",
                ".BBBBB.BBB.",
                ".BBBBB.BBB.",
                "...........",
        });
        PadMapStyleProcessor.StyledMap styled = new PadMapStyleProcessor().style(snapshot);
        if (styled.buildingZone()[index(styled.width(), 5, 4)] || styled.buildingZone()[index(styled.width(), 6, 4)]) {
            throw new AssertionError("open courtyard should not be filled as a closed building hole");
        }
    }

    @Test
    void ignoresSingleBuildingNoise() {
        PadMapSnapshot snapshot = snapshot(5, 5, new String[] {
                ".....",
                ".....",
                "..B..",
                ".....",
                ".....",
        });
        PadMapStyleProcessor.StyledMap styled = new PadMapStyleProcessor().style(snapshot);
        assertNoMask(styled.buildingCore(), "single building noise should not become a core building");
        assertNoMask(styled.buildingZone(), "single building noise should not become a building zone");
    }

    @Test
    void keepsSeparatedBuildingsSeparated() {
        PadMapSnapshot snapshot = snapshot(7, 5, new String[] {
                ".......",
                ".BB.BB.",
                ".BB.BB.",
                ".......",
                ".......",
        });
        PadMapStyleProcessor.StyledMap styled = new PadMapStyleProcessor().style(snapshot);
        assertMask(styled.buildingZone(), styled.width(), 1, 1, "left building zone missing");
        assertMask(styled.buildingZone(), styled.width(), 5, 1, "right building zone missing");
        if (styled.buildingZone()[index(styled.width(), 3, 1)] || styled.buildingZone()[index(styled.width(), 3, 2)]) {
            throw new AssertionError("separated buildings should not be merged into one building zone");
        }
    }

    private static PadMapSnapshot snapshot(int width, int height, String[] rows) {
        if (rows.length != height) {
            throw new IllegalArgumentException("row count mismatch");
        }
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        Arrays.fill(tiles, PadMapTileKind.GRASS);
        for (int z = 0; z < height; z++) {
            if (rows[z].length() != width) {
                throw new IllegalArgumentException("row width mismatch at " + z);
            }
            for (int x = 0; x < width; x++) {
                char c = rows[z].charAt(x);
                tiles[index(width, x, z)] = c == 'B' ? PadMapTileKind.BUILDING
                        : c == 'F' ? PadMapTileKind.INDOOR_FLOOR
                                : PadMapTileKind.GRASS;
            }
        }
        return new PadMapSnapshot(0, 0, 1, width, height, tiles);
    }

    private static PadMapTileKind[] withBuildings(PadMapSnapshot snapshot, String[] rows) {
        PadMapTileKind[] tiles = snapshot.tiles().clone();
        for (int z = 0; z < snapshot.height(); z++) {
            for (int x = 0; x < snapshot.width(); x++) {
                if (rows[z].charAt(x) == 'B') {
                    tiles[index(snapshot.width(), x, z)] = PadMapTileKind.BUILDING;
                }
            }
        }
        return tiles;
    }

    private static void assertMask(boolean[] mask, int width, int x, int z, String message) {
        if (!mask[index(width, x, z)]) {
            throw new AssertionError(message);
        }
    }

    private static void assertNoMask(boolean[] mask, String message) {
        for (boolean value : mask) {
            if (value) {
                throw new AssertionError(message);
            }
        }
    }

    private static int index(int width, int x, int z) {
        return z * width + x;
    }
}
