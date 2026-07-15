package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadPerfLogger;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSampler;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapStyleProcessor;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
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
    private static final long MIN_BAKE_INTERVAL_NANOS = Long.getLong("ncpb.pad.map_min_bake_interval_ms", 500L)
            * 1_000_000L;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Identifier textureId;
    private final PadMapBakeScheduler bakeScheduler = new PadMapBakeScheduler(MIN_BAKE_INTERVAL_NANOS);
    private DynamicTexture texture;
    private final PadMapStyleProcessor styleProcessor = new PadMapStyleProcessor();
    private PadMapSnapshot bakedSnapshot;
    private PadMapStyleProcessor.StyledMap bakedStyled;
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
        return bakeScheduler.renderedSnapshotOr(fallback);
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
        PadMapBakeScheduler.BakeDecision decision = bakeScheduler.request(snapshot, System.nanoTime());
        if (!decision.shouldBake()) {
            return;
        }
        snapshot = decision.snapshot();
        try {
            ensureTexture();
            long started = System.nanoTime();
            bake(snapshot);
            texture.upload();
            long completed = System.nanoTime();
            PadPerfLogger.recordMapBake(completed - started);
            bakeScheduler.complete(snapshot, decision.signature(), completed);
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
        boolean[] repaint = prepareReuse(image, map, styled, bakedStyled, ox, oy, cellX, cellY);
        drawAreaMask(image, styled.farmlandArea(), repaint, styled.width(), styled.height(), ox, oy, cellX, cellY,
                0xFFE0D5B7);
        drawAreaMask(image, styled.greenArea(), repaint, styled.width(), styled.height(), ox, oy, cellX, cellY,
                0xFFBCD3B2);
        drawAreaMask(image, styled.waterArea(), repaint, styled.width(), styled.height(), ox, oy, cellX, cellY,
                0xFF9DBDCE);
        drawAreaMask(image, styled.indoorFloor(), repaint, styled.width(), styled.height(), ox, oy, cellX, cellY,
                0xFFE8DED0);
        drawAreaMask(image, styled.buildingZone(), repaint, styled.width(), styled.height(), ox, oy, cellX, cellY,
                0xFFD1CBC3);
        drawAreaMask(image, styled.buildingCore(), repaint, styled.width(), styled.height(), ox, oy, cellX, cellY,
                0xFFBEB7AE);
        drawLineMask(image, styled.waterLine(), repaint, styled.width(), styled.height(), ox, oy, cellX, cellY,
                0xFF86AFC4, 1);
        drawUnknownCells(image, map, repaint, ox, oy, cellX, cellY);
        vignette(image);
        bakedSnapshot = map;
        bakedStyled = styled;
    }

    private boolean[] prepareReuse(NativeImage image, PadMapSnapshot map, PadMapStyleProcessor.StyledMap styled,
            PadMapStyleProcessor.StyledMap oldStyled, int ox, int oy, float cellX, float cellY) {
        int size = map.width() * map.height();
        boolean[] repaint = new boolean[size];
        PadMapRenderReusePlan.Plan plan = PadMapRenderReusePlan.between(bakedSnapshot, map);
        if (!plan.reusable() || oldStyled == null) {
            java.util.Arrays.fill(repaint, true);
            fill(image, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, 0xFFE6E0D5);
            return repaint;
        }
        int shiftX = Math.round(plan.textureCellShiftX() * cellX) * Math.max(1, SCALE);
        int shiftY = Math.round(plan.textureCellShiftY() * cellY) * Math.max(1, SCALE);
        shiftPixels(image, shiftX, shiftY);
        for (int z = 0; z < map.height(); z++) {
            int oldZ = z - plan.textureCellShiftY();
            for (int x = 0; x < map.width(); x++) {
                int oldX = x - plan.textureCellShiftX();
                int index = z * map.width() + x;
                boolean reusable = oldX >= 0 && oldX < map.width() && oldZ >= 0 && oldZ < map.height();
                if (reusable) {
                    int oldIndex = oldZ * map.width() + oldX;
                    reusable = outsideVignette(oldX, oldZ, cellX, cellY)
                            && sameVisual(styled, index, oldStyled, oldIndex)
                            && map.tile(map.width() - 1 - x, z) == bakedSnapshot.tile(map.width() - 1 - oldX, oldZ);
                }
                repaint[index] = !reusable;
                if (!reusable) {
                    fillCellRun(image, ox, oy, cellX, cellY, x, x + 1, z, 0xFFE6E0D5);
                }
            }
        }
        return repaint;
    }

    private boolean outsideVignette(int x, int z, float cellX, float cellY) {
        int left = Math.round(x * cellX);
        int top = Math.round(z * cellY);
        int right = Math.round((x + 1) * cellX) + 1;
        int bottom = Math.round((z + 1) * cellY) + 1;
        return left >= 5 && top >= 5 && right <= WIDTH - 5 && bottom <= HEIGHT - 5;
    }

    private boolean sameVisual(PadMapStyleProcessor.StyledMap a, int ai, PadMapStyleProcessor.StyledMap b, int bi) {
        return a.greenArea()[ai] == b.greenArea()[bi] && a.farmlandArea()[ai] == b.farmlandArea()[bi]
                && a.waterArea()[ai] == b.waterArea()[bi] && a.waterLine()[ai] == b.waterLine()[bi]
                && a.buildingZone()[ai] == b.buildingZone()[bi] && a.buildingCore()[ai] == b.buildingCore()[bi]
                && a.indoorFloor()[ai] == b.indoorFloor()[bi];
    }

    private void shiftPixels(NativeImage image, int shiftX, int shiftY) {
        int[] source = image.getPixels();
        fill(image, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, 0xFFE6E0D5);
        for (int y = 0; y < TARGET_HEIGHT; y++) {
            int sy = y - shiftY;
            if (sy < 0 || sy >= TARGET_HEIGHT)
                continue;
            for (int x = 0; x < TARGET_WIDTH; x++) {
                int sx = x - shiftX;
                if (sx >= 0 && sx < TARGET_WIDTH)
                    image.setPixel(x, y, source[sy * TARGET_WIDTH + sx]);
            }
        }
    }

    private void drawUnknownCells(NativeImage image, PadMapSnapshot map, boolean[] repaint, int ox, int oy, float cellX,
            float cellY) {
        for (int z = 0; z < map.height(); z++) {
            for (int x = 0; x < map.width(); x++) {
                if (!repaint[z * map.width() + x] || map.tile(map.width() - 1 - x, z) != PadMapTileKind.UNKNOWN) {
                    continue;
                }
                int color = ((x + z) & 1) == 0 ? 0xFF91A3B1 : 0xFF7F919F;
                fillCellRun(image, ox, oy, cellX, cellY, x, x + 1, z, color);
            }
        }
    }

    private void drawAreaMask(NativeImage image, boolean[] mask, boolean[] repaint, int width, int height, int ox,
            int oy, float cellX,
            float cellY, int color) {
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int i = index(width, x, z);
                if (repaint[i] && mask[i]) {
                    fillCellRun(image, ox, oy, cellX, cellY, x, x + 1, z, color);
                }
            }
        }
    }

    private void drawLineMask(NativeImage image, boolean[] mask, boolean[] repaint, int width, int height, int ox,
            int oy, float cellX,
            float cellY, int color, int lineWidth) {
        int half = Math.max(0, lineWidth / 2);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                if (!repaint[index(width, x, z)] || !mask[index(width, x, z)]) {
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
            Minecraft.getInstance().getTextureManager().release(textureId);
            texture = null;
        }
    }

}
