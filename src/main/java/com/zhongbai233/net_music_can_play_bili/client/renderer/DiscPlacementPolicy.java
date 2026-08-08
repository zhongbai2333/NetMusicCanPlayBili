package com.zhongbai233.net_music_can_play_bili.client.renderer;

/**
 * 将北向 Blockbench 唱片中心绕方块中心旋转到其他水平朝向。
 */
final class DiscPlacementPolicy {
    static final double MODEL_SCALE = 0.75D;
    static final double BASE_CENTER_X = 9.7449D / 16.0D;
    static final double BASE_CENTER_Z = 7.7449D / 16.0D;

    private DiscPlacementPolicy() {
    }

    static Placement forClockwiseQuarterTurns(int quarterTurns) {
        double offsetX = BASE_CENTER_X - 0.5D;
        double offsetZ = BASE_CENTER_Z - 0.5D;
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 0 -> placement(offsetX, offsetZ);
            case 1 -> placement(-offsetZ, offsetX);
            case 2 -> placement(-offsetX, -offsetZ);
            default -> placement(offsetZ, -offsetX);
        };
    }

    private static Placement placement(double offsetX, double offsetZ) {
        return new Placement((0.5D + offsetX) / MODEL_SCALE, (0.5D + offsetZ) / MODEL_SCALE);
    }

    record Placement(double anchorX, double anchorZ) {
    }
}