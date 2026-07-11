package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;

/** Pure calculations for reusing overlapping pixels between map snapshots. */
final class PadMapRenderReusePlan {
    private PadMapRenderReusePlan() {
    }

    static Plan between(PadMapSnapshot previous, PadMapSnapshot next) {
        if (previous == null || next == null || previous.width() != next.width()
                || previous.height() != next.height() || previous.cellSizeBlocks() != next.cellSizeBlocks()
                || previous.centerY() != next.centerY() || previous.displayScale() != next.displayScale()) {
            return Plan.none();
        }
        int cellSize = next.cellSizeBlocks();
        int worldDx = next.centerX() - previous.centerX();
        int worldDz = next.centerZ() - previous.centerZ();
        if (cellSize <= 0 || Math.floorMod(worldDx, cellSize) != 0 || Math.floorMod(worldDz, cellSize) != 0) {
            return Plan.none();
        }
        int cellDx = worldDx / cellSize;
        int cellDz = worldDz / cellSize;
        if (Math.abs(cellDx) >= next.width() || Math.abs(cellDz) >= next.height()) {
            return Plan.none();
        }
        // 地图纹理 X 轴与 tile 数组相反；Z 轴方向一致。
        return new Plan(true, cellDx, -cellDz);
    }

    record Plan(boolean reusable, int textureCellShiftX, int textureCellShiftY) {
        static Plan none() {
            return new Plan(false, 0, 0);
        }
    }
}