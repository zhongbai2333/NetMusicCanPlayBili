package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.Arrays;

/**
 * Pad 地图样式后处理。
 * <p>
 * 这里不依赖渲染 API，方便离线脚本、普通测试和 GameTest 共用同一套建筑/水域/绿地区域 mask 逻辑。
 */
public final class PadMapStyleProcessor {
    private Workspace workspace;

    public StyledMap style(PadMapSnapshot map) {
        int width = map.width();
        int height = map.height();
        Workspace workspace = workspace(width, height);
        workspace.clearInputs();
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                PadMapTileKind kind = map.tile(width - 1 - x, z);
                int index = index(width, x, z);
                switch (kind) {
                    case TREE -> workspace.tree[index] = true;
                    case FARMLAND -> workspace.farmland[index] = true;
                    case INDOOR_FLOOR -> workspace.indoorFloor[index] = true;
                    case BUILDING -> workspace.building[index] = true;
                    case WATER -> workspace.water[index] = true;
                    default -> {
                    }
                }
            }
        }

        largeComponents(workspace.tree, workspace.component, workspace.visited, workspace.queue, width, height, 10);
        softenArea(workspace.component, workspace.greenArea, width, height, 2, 9);
        largeComponents(workspace.farmland, workspace.component, workspace.visited, workspace.queue, width, height, 8);
        softenArea(workspace.component, workspace.farmlandArea, width, height, 1, 4);
        largeComponents(workspace.water, workspace.component, workspace.visited, workspace.queue, width, height, 18);
        softenArea(workspace.component, workspace.waterArea, width, height, 1, 5);
        subtract(workspace.water, workspace.waterArea, workspace.waterLine);
        boolean indoorMap = count(workspace.indoorFloor) >= 8;
        largeComponents(workspace.building, workspace.buildingFootprint, workspace.visited, workspace.queue, width,
                height, indoorMap ? 2 : 4);
        if (indoorMap) {
            fillSmallInteriorGaps(workspace.buildingFootprint, width, height);
            outlineWithSource(workspace.buildingFootprint, workspace.buildingZone, width, height);
            buildingCore(workspace.buildingFootprint, workspace.buildingCore, width, height, 4);
        } else {
            fillSingleCellHoles(workspace.buildingFootprint, width, height);
            fillEnclosedHoles(workspace.buildingFootprint, workspace.visited, workspace.queue, width, height,
                    Math.max(24, width * height / 18));
            copy(workspace.buildingFootprint, workspace.buildingZone);
            buildingCore(workspace.buildingFootprint, workspace.buildingCore, width, height, 6);
        }
        return new StyledMap(width, height, workspace.greenArea.clone(), workspace.farmlandArea.clone(),
                workspace.waterArea.clone(), workspace.waterLine.clone(), workspace.buildingZone.clone(),
                workspace.buildingCore.clone(), workspace.indoorFloor.clone());
    }

    private Workspace workspace(int width, int height) {
        int size = width * height;
        if (workspace == null || workspace.size != size) {
            workspace = new Workspace(size);
        }
        return workspace;
    }

    private void largeComponents(boolean[] source, boolean[] output, boolean[] visited, int[] queue, int width,
            int height, int minArea) {
        Arrays.fill(output, false);
        Arrays.fill(visited, false);
        for (int start = 0; start < source.length; start++) {
            if (!source[start] || visited[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            visited[start] = true;
            queue[tail++] = start;
            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int z = index / width;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (Math.abs(dx) + Math.abs(dz) != 1) {
                            continue;
                        }
                        int nx = x + dx;
                        int nz = z + dz;
                        if (nx < 0 || nx >= width || nz < 0 || nz >= height) {
                            continue;
                        }
                        int neighbor = index(width, nx, nz);
                        if (source[neighbor] && !visited[neighbor]) {
                            visited[neighbor] = true;
                            queue[tail++] = neighbor;
                        }
                    }
                }
            }
            if (tail >= minArea) {
                for (int i = 0; i < tail; i++) {
                    output[queue[i]] = true;
                }
            }
        }
    }

    private void softenArea(boolean[] source, boolean[] output, int width, int height, int radius, int threshold) {
        System.arraycopy(source, 0, output, 0, source.length);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                if (source[index(width, x, z)]) {
                    continue;
                }
                if (countInRadius(source, width, height, x, z, radius) >= threshold) {
                    output[index(width, x, z)] = true;
                }
            }
        }
    }

    private void outlineWithSource(boolean[] source, boolean[] output, int width, int height) {
        System.arraycopy(source, 0, output, 0, source.length);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = index(width, x, z);
                if (source[index]) {
                    continue;
                }
                if (countInRadius(source, width, height, x, z, 1) > 0) {
                    output[index] = true;
                }
            }
        }
    }

    private void buildingCore(boolean[] source, boolean[] output, int width, int height, int threshold) {
        Arrays.fill(output, false);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = index(width, x, z);
                if (!source[index]) {
                    continue;
                }
                if (countInRadius(source, width, height, x, z, 1) >= threshold) {
                    output[index] = true;
                }
            }
        }
    }

    private void copy(boolean[] source, boolean[] output) {
        System.arraycopy(source, 0, output, 0, source.length);
    }

    private int count(boolean[] mask) {
        int count = 0;
        for (boolean value : mask) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private void fillSmallInteriorGaps(boolean[] mask, int width, int height) {
        for (int z = 1; z < height - 1; z++) {
            for (int x = 1; x < width - 1; x++) {
                int index = index(width, x, z);
                if (mask[index]) {
                    continue;
                }
                int cardinal = 0;
                if (mask[index(width, x - 1, z)]) {
                    cardinal++;
                }
                if (mask[index(width, x + 1, z)]) {
                    cardinal++;
                }
                if (mask[index(width, x, z - 1)]) {
                    cardinal++;
                }
                if (mask[index(width, x, z + 1)]) {
                    cardinal++;
                }
                if (cardinal >= 3 || cardinal >= 2 && countInRadius(mask, width, height, x, z, 1) >= 5) {
                    mask[index] = true;
                }
            }
        }
    }

    private void fillSingleCellHoles(boolean[] mask, int width, int height) {
        for (int z = 1; z < height - 1; z++) {
            for (int x = 1; x < width - 1; x++) {
                int index = index(width, x, z);
                if (mask[index]) {
                    continue;
                }
                if (mask[index(width, x - 1, z)] && mask[index(width, x + 1, z)]
                        && mask[index(width, x, z - 1)] && mask[index(width, x, z + 1)]) {
                    mask[index] = true;
                }
            }
        }
    }

    private void fillEnclosedHoles(boolean[] mask, boolean[] visited, int[] queue, int width, int height,
            int maxArea) {
        Arrays.fill(visited, false);
        for (int z = 0; z < height; z++) {
            floodOpenArea(mask, visited, queue, width, height, 0, z);
            floodOpenArea(mask, visited, queue, width, height, width - 1, z);
        }
        for (int x = 0; x < width; x++) {
            floodOpenArea(mask, visited, queue, width, height, x, 0);
            floodOpenArea(mask, visited, queue, width, height, x, height - 1);
        }
        for (int start = 0; start < mask.length; start++) {
            if (mask[start] || visited[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            boolean touchesEdge = false;
            visited[start] = true;
            queue[tail++] = start;
            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int z = index / width;
                if (x == 0 || x == width - 1 || z == 0 || z == height - 1) {
                    touchesEdge = true;
                }
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (Math.abs(dx) + Math.abs(dz) != 1) {
                            continue;
                        }
                        int nx = x + dx;
                        int nz = z + dz;
                        if (nx < 0 || nx >= width || nz < 0 || nz >= height) {
                            continue;
                        }
                        int neighbor = index(width, nx, nz);
                        if (!mask[neighbor] && !visited[neighbor]) {
                            visited[neighbor] = true;
                            queue[tail++] = neighbor;
                        }
                    }
                }
            }
            if (!touchesEdge && tail <= maxArea && tail <= maxEnclosedHoleSpan(width, height)
                    && hasBuildingRing(mask, queue, tail, width, height)) {
                for (int i = 0; i < tail; i++) {
                    mask[queue[i]] = true;
                }
            }
        }
    }

    private int maxEnclosedHoleSpan(int width, int height) {
        int smallestSide = Math.min(width, height);
        return Math.max(9, smallestSide / 6);
    }

    private void floodOpenArea(boolean[] mask, boolean[] visited, int[] queue, int width, int height, int x, int z) {
        int start = index(width, x, z);
        if (mask[start] || visited[start]) {
            return;
        }
        int head = 0;
        int tail = 0;
        visited[start] = true;
        queue[tail++] = start;
        while (head < tail) {
            int index = queue[head++];
            int cx = index % width;
            int cz = index / width;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (Math.abs(dx) + Math.abs(dz) != 1) {
                        continue;
                    }
                    int nx = cx + dx;
                    int nz = cz + dz;
                    if (nx < 0 || nx >= width || nz < 0 || nz >= height) {
                        continue;
                    }
                    int neighbor = index(width, nx, nz);
                    if (!mask[neighbor] && !visited[neighbor]) {
                        visited[neighbor] = true;
                        queue[tail++] = neighbor;
                    }
                }
            }
        }
    }

    private boolean hasBuildingRing(boolean[] mask, int[] component, int length, int width, int height) {
        int adjacent = 0;
        int required = Math.max(4, Math.min(48, length / 2));
        for (int i = 0; i < length; i++) {
            int index = component[i];
            int x = index % width;
            int z = index / width;
            if (hasAdjacentBuilding(mask, width, height, x, z)) {
                adjacent++;
                if (adjacent >= required) {
                    return true;
                }
            }
        }
        return adjacent >= required;
    }

    private boolean hasAdjacentBuilding(boolean[] mask, int width, int height, int x, int z) {
        if (x > 0 && mask[index(width, x - 1, z)]) {
            return true;
        }
        if (x + 1 < width && mask[index(width, x + 1, z)]) {
            return true;
        }
        if (z > 0 && mask[index(width, x, z - 1)]) {
            return true;
        }
        return z + 1 < height && mask[index(width, x, z + 1)];
    }

    private void subtract(boolean[] source, boolean[] remove, boolean[] output) {
        for (int i = 0; i < source.length; i++) {
            output[i] = source[i] && !remove[i];
        }
    }

    private int countInRadius(boolean[] mask, int width, int height, int x, int z, int radius) {
        int count = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int nx = x + dx;
                int nz = z + dz;
                if (nx >= 0 && nx < width && nz >= 0 && nz < height && mask[index(width, nx, nz)]) {
                    count++;
                }
            }
        }
        return count;
    }

    private int index(int width, int x, int z) {
        return z * width + x;
    }

    public record StyledMap(int width, int height, boolean[] greenArea, boolean[] farmlandArea, boolean[] waterArea,
            boolean[] waterLine, boolean[] buildingZone, boolean[] buildingCore, boolean[] indoorFloor) {
    }

    private static final class Workspace {
        final int size;
        final boolean[] tree;
        final boolean[] farmland;
        final boolean[] indoorFloor;
        final boolean[] building;
        final boolean[] water;
        final boolean[] component;
        final boolean[] visited;
        final boolean[] greenArea;
        final boolean[] farmlandArea;
        final boolean[] waterArea;
        final boolean[] waterLine;
        final boolean[] buildingZone;
        final boolean[] buildingFootprint;
        final boolean[] buildingCore;
        final int[] queue;

        Workspace(int size) {
            this.size = size;
            this.tree = new boolean[size];
            this.farmland = new boolean[size];
            this.indoorFloor = new boolean[size];
            this.building = new boolean[size];
            this.water = new boolean[size];
            this.component = new boolean[size];
            this.visited = new boolean[size];
            this.greenArea = new boolean[size];
            this.farmlandArea = new boolean[size];
            this.waterArea = new boolean[size];
            this.waterLine = new boolean[size];
            this.buildingZone = new boolean[size];
            this.buildingFootprint = new boolean[size];
            this.buildingCore = new boolean[size];
            this.queue = new int[size];
        }

        void clearInputs() {
            Arrays.fill(tree, false);
            Arrays.fill(farmland, false);
            Arrays.fill(indoorFloor, false);
            Arrays.fill(building, false);
            Arrays.fill(water, false);
        }
    }
}
