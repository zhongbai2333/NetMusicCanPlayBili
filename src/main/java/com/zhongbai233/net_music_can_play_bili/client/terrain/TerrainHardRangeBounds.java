package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;

/** 将中控台中心与半轴 hardRange 转为包含端点、经过世界高度裁剪的方块范围。 */
public final class TerrainHardRangeBounds {
    private TerrainHardRangeBounds() {
    }

        public static TerrainBounds around(int consoleX, int consoleY, int consoleZ,
            double halfX, double halfY, double halfZ,
            int levelMinY, int levelMaxY) {
        validateRange(halfX);
        validateRange(halfY);
        validateRange(halfZ);
        if (levelMaxY <= levelMinY) {
            throw new IllegalArgumentException("invalid world height");
        }
        double centerX = consoleX + 0.5D;
        double centerY = consoleY + 0.5D;
        double centerZ = consoleZ + 0.5D;
        int minX = floorClamped(centerX - halfX);
        int maxX = floorClamped(centerX + halfX);
        int minY = Math.max(levelMinY, floorClamped(centerY - halfY));
        int maxY = Math.min(levelMaxY - 1, floorClamped(centerY + halfY));
        int minZ = floorClamped(centerZ - halfZ);
        int maxZ = floorClamped(centerZ + halfZ);
        if (maxY < minY) {
            int clamped = Math.clamp(consoleY, levelMinY, levelMaxY - 1);
            minY = clamped;
            maxY = clamped;
        }
        return new TerrainBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void validateRange(double range) {
        if (!Double.isFinite(range) || range < 0.0D) {
            throw new IllegalArgumentException("hardRange must be finite and non-negative");
        }
    }

    private static int floorClamped(double value) {
        if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.floor(value);
    }
}