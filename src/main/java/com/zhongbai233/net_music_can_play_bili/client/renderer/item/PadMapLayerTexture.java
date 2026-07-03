package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadPerfLogger;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSampler;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapStyleProcessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Pad 地图底图缓存纹理。
 * <p>
 * 地图数据变化时烘焙为动态纹理；渲染帧只提交缓存纹理，避免每帧重绘地图底图。
 */
final class PadMapLayerTexture implements AutoCloseable {
    static final int CELL_PIXELS = Integer.getInteger("ncpb.pad.map_cell_pixels", 1);
    static final int WIDTH = PadMapSampler.DEFAULT_WIDTH * CELL_PIXELS;
    static final int HEIGHT = PadMapSampler.DEFAULT_HEIGHT * CELL_PIXELS;
    static final int VIEW_WIDTH = PadMapSampler.DEFAULT_VIEW_WIDTH * CELL_PIXELS;
    static final int VIEW_HEIGHT = PadMapSampler.DEFAULT_VIEW_HEIGHT * CELL_PIXELS;
    private static final int SCALE = Integer.getInteger("ncpb.pad.map_layer_scale", 1);
    private static final int TARGET_WIDTH = WIDTH * Math.max(1, SCALE);
    private static final int TARGET_HEIGHT = HEIGHT * Math.max(1, SCALE);
    private static final long MIN_BAKE_INTERVAL_NANOS = Long.getLong("ncpb.pad.map_min_bake_interval_ms", 250L)
            * 1_000_000L;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Identifier textureId;
    private DynamicTexture texture;
    private PadMapSnapshot lastSnapshot;
    private PadMapSnapshot pendingSnapshot;
    private final PadMapStyleProcessor styleProcessor = new PadMapStyleProcessor();
    private long lastSnapshotSignature;
    private long pendingSnapshotSignature;
    private long lastLayoutSignature;
    private long lastBakeNanos;
    private boolean failed;

    PadMapLayerTexture(String key) {
        this.textureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/pad_map_layer_" + key);
    }

    Identifier textureId(PadMapSnapshot snapshot) {
        request(snapshot);
        return textureId;
    }

    void tick(PadMapSnapshot snapshot) {
        renderIfNeeded(snapshot);
    }

    PadMapSnapshot renderedSnapshotOr(PadMapSnapshot fallback) {
        if (lastSnapshot != null && fallback != null && fallback.contentSignature() == lastSnapshotSignature) {
            return lastSnapshot;
        }
        return fallback;
    }

    private void request(PadMapSnapshot snapshot) {
        if (failed || snapshot == null) {
            return;
        }
        ensureTexture();
    }

    private void renderIfNeeded(PadMapSnapshot snapshot) {
        if (failed || snapshot == null) {
            return;
        }
        long signature = snapshot.contentSignature();
        if (snapshot == lastSnapshot || signature == lastSnapshotSignature) {
            return;
        }
        long layoutSignature = snapshot.layoutSignature();
        boolean layoutChanged = layoutSignature != lastLayoutSignature;
        pendingSnapshot = snapshot;
        pendingSnapshotSignature = signature;
        long now = System.nanoTime();
        if (!layoutChanged && lastSnapshot != null && now - lastBakeNanos < MIN_BAKE_INTERVAL_NANOS) {
            return;
        }
        snapshot = pendingSnapshot;
        signature = pendingSnapshotSignature;
        try {
            ensureTexture();
            long started = System.nanoTime();
            bake(snapshot);
            texture.upload();
            PadPerfLogger.recordMapBake(System.nanoTime() - started);
            lastSnapshot = snapshot;
            lastSnapshotSignature = signature;
            lastLayoutSignature = snapshot.layoutSignature();
            pendingSnapshot = null;
            pendingSnapshotSignature = 0L;
            lastBakeNanos = System.nanoTime();
        } catch (RuntimeException ex) {
            failed = true;
            LOGGER.warn("Pad 地图纹理烘焙失败: {}", textureId, ex);
        }
    }

    private void ensureTexture() {
        if (texture != null) {
            return;
        }
        texture = new DynamicTexture(textureId::toString, TARGET_WIDTH, TARGET_HEIGHT, false);
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
    }

    private void bake(PadMapSnapshot map) {
        NativeImage image = texture.getPixels();
        if (image == null || image.isClosed()) {
            return;
        }
        fill(image, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, 0xFFE6E0D5);
        int width = map.width();
        int height = map.height();
        float cell = Math.max(1.0F, Math.min(WIDTH / (float) width, HEIGHT / (float) height));
        int drawW = Math.round(width * cell);
        int drawH = Math.round(height * cell);
        int ox = (WIDTH - drawW) / 2;
        int oy = (HEIGHT - drawH) / 2;
        float cellX = cell;
        float cellY = cell;
        PadMapStyleProcessor.StyledMap styled = styleProcessor.style(map);
        drawAreaMask(image, styled.farmlandArea(), styled.width(), styled.height(), ox, oy, cellX, cellY, 0xFFE0D5B7);
        drawAreaMask(image, styled.greenArea(), styled.width(), styled.height(), ox, oy, cellX, cellY, 0xFFBCD3B2);
        drawAreaMask(image, styled.waterArea(), styled.width(), styled.height(), ox, oy, cellX, cellY, 0xFF9DBDCE);
        drawAreaMask(image, styled.indoorFloor(), styled.width(), styled.height(), ox, oy, cellX, cellY, 0xFFE8DED0);
        drawAreaMask(image, styled.buildingZone(), styled.width(), styled.height(), ox, oy, cellX, cellY, 0xFFD1CBC3);
        drawAreaMask(image, styled.buildingCore(), styled.width(), styled.height(), ox, oy, cellX, cellY,
                0xFFBEB7AE);
        drawLineMask(image, styled.waterLine(), styled.width(), styled.height(), ox, oy, cellX, cellY, 0xFF86AFC4, 1);
        vignette(image);
    }

    private void drawAreaMask(NativeImage image, boolean[] mask, int width, int height, int ox, int oy, float cellX,
            float cellY, int color) {
        for (int z = 0; z < height; z++) {
            int runStart = 0;
            for (int x = 0; x <= width; x++) {
                boolean filled = x < width && mask[index(width, x, z)];
                if (x == 0) {
                    runStart = 0;
                    continue;
                }
                boolean previousFilled = mask[index(width, x - 1, z)];
                if (filled == previousFilled) {
                    continue;
                }
                if (previousFilled) {
                    fillCellRun(image, ox, oy, cellX, cellY, runStart, x, z, color);
                }
                runStart = x;
            }
        }
    }

    private void drawLineMask(NativeImage image, boolean[] mask, int width, int height, int ox, int oy, float cellX,
            float cellY, int color, int lineWidth) {
        int half = Math.max(0, lineWidth / 2);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                if (!mask[index(width, x, z)]) {
                    continue;
                }
                int cx = ox + Math.round((x + 0.5F) * cellX);
                int cy = oy + Math.round((z + 0.5F) * cellY);
                fillLogical(image, cx - half, cy - half, lineWidth, lineWidth, color);
                if (x + 1 < width && mask[index(width, x + 1, z)]) {
                    int nx = ox + Math.round((x + 1.5F) * cellX);
                    fillLogical(image, Math.min(cx, nx), cy - half, Math.abs(nx - cx) + lineWidth, lineWidth, color);
                }
                if (z + 1 < height && mask[index(width, x, z + 1)]) {
                    int ny = oy + Math.round((z + 1.5F) * cellY);
                    fillLogical(image, cx - half, Math.min(cy, ny), lineWidth, Math.abs(ny - cy) + lineWidth, color);
                }
            }
        }
    }

    private int index(int width, int x, int z) {
        return z * width + x;
    }

    private void fillCellRun(NativeImage image, int ox, int oy, float cellX, float cellY, int startX, int endX, int z,
            int color) {
        int left = ox + Math.round(startX * cellX);
        int top = oy + Math.round(z * cellY);
        int right = ox + Math.round(endX * cellX) + 1;
        int bottom = oy + Math.round((z + 1) * cellY) + 1;
        fillLogical(image, left, top, right - left, bottom - top, color);
    }

    private void vignette(NativeImage image) {
        fillLogical(image, 0, 0, WIDTH, 1, 0xFFEDE8E1);
        fillLogical(image, 0, HEIGHT - 1, WIDTH, 1, 0xFFC9C4BC);
        fillLogical(image, 0, 0, WIDTH, 5, 0x18FFFFFF);
        fillLogical(image, 0, HEIGHT - 5, WIDTH, 5, 0x11000000);
        fillLogical(image, 0, 0, 5, HEIGHT, 0x0F000000);
        fillLogical(image, WIDTH - 5, 0, 5, HEIGHT, 0x0F000000);
    }

    private void fillLogical(NativeImage image, int x, int y, int w, int h, int color) {
        int scale = Math.max(1, SCALE);
        fill(image, x * scale, y * scale, w * scale, h * scale, color);
    }

    private void fill(NativeImage image, int x, int y, int w, int h, int color) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(TARGET_WIDTH, x + Math.max(0, w));
        int bottom = Math.min(TARGET_HEIGHT, y + Math.max(0, h));
        if (right <= left || bottom <= top) {
            return;
        }
        image.fillRect(left, top, right - left, bottom - top, color);
    }

    @Override
    public void close() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }

}
