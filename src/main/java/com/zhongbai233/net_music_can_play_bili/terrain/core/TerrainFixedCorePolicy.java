package com.zhongbai233.net_music_can_play_bili.terrain.core;

/** 固定地形实景核心：球内实景，边缘稳定抖动，球外只保留线框。 */
public final class TerrainFixedCorePolicy {
    public static final double RADIUS = 12.5D;
    public static final double FADE_WIDTH = 3.0D;
    public static final double SOLID_RADIUS = RADIUS - FADE_WIDTH;

    private TerrainFixedCorePolicy() {
    }

    public static double retention(double centerX, double centerY, double centerZ,
            int blockX, int blockY, int blockZ) {
        double dx = blockX + 0.5D - centerX;
        double dy = blockY + 0.5D - centerY;
        double dz = blockZ + 0.5D - centerZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= SOLID_RADIUS) {
            return 1.0D;
        }
        if (distance >= RADIUS) {
            return 0.0D;
        }
        double t = (distance - SOLID_RADIUS) / FADE_WIDTH;
        double smooth = t * t * (3.0D - 2.0D * t);
        return 1.0D - smooth;
    }

    public static boolean rendersBlock(long seed, double centerX, double centerY, double centerZ,
            int blockX, int blockY, int blockZ) {
        double retention = retention(centerX, centerY, centerZ, blockX, blockY, blockZ);
        return retention >= 1.0D || retention > 0.0D && unitHash(seed, blockX, blockY, blockZ, 0) < retention;
    }

    public static boolean emitsBranch(long seed, int x, int y, int z, int axis) {
        return unitHash(seed, x, y, z, axis + 17) < 0.22D;
    }

    public static double branchLength(long seed, int x, int y, int z, int axis) {
        return 0.5D + unitHash(seed, x, y, z, axis + 31) * 2.0D;
    }

    public static int branchDirection(long seed, int x, int y, int z, int axis) {
        return (int) Math.floor(unitHash(seed, x, y, z, axis + 47) * 6.0D);
    }

    /** 按到固定核心的距离生成 MID 4³ / FAR 8³ 材质单元。 */
    public static int overviewCellSize(double distance) {
        if (!Double.isFinite(distance) || distance > RADIUS + 24.0D) {
            return 8;
        }
        return 4;
    }

        public static boolean sectionMayContainDetail(double centerX, double centerY, double centerZ,
            int minBlockX, int minBlockY, int minBlockZ) {
        double nearestX = Math.clamp(centerX, minBlockX + 0.5D,
            minBlockX + TerrainSectionKey.SIZE - 0.5D);
        double nearestY = Math.clamp(centerY, minBlockY + 0.5D,
            minBlockY + TerrainSectionKey.SIZE - 0.5D);
        double nearestZ = Math.clamp(centerZ, minBlockZ + 0.5D,
            minBlockZ + TerrainSectionKey.SIZE - 0.5D);
        double dx = nearestX - centerX;
        double dy = nearestY - centerY;
        double dz = nearestZ - centerZ;
        return dx * dx + dy * dy + dz * dz < RADIUS * RADIUS;
        }

    private static double unitHash(long seed, int x, int y, int z, int salt) {
        long value = seed;
        value ^= mix((long) x * 0x9E3779B97F4A7C15L);
        value ^= mix((long) y * 0xC2B2AE3D27D4EB4FL);
        value ^= mix((long) z * 0x165667B19E3779F9L);
        value ^= mix((long) salt * 0xD6E8FEB86659FD93L);
        return (mix(value) >>> 11) * 0x1.0p-53;
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
}
