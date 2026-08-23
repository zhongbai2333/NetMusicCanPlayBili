package com.zhongbai233.net_music_can_play_bili.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoVisualSyncPolicy;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.ArrayList;
import java.util.List;

/** Independent right-side HUD and world overlay for video visibility and decoder admission. */
@EventBusSubscriber(modid = NetMusicCanPlayBili.MODID, value = Dist.CLIENT)
public final class VideoPlaybackDebugRenderer {
    private static final int PANEL_MAX_WIDTH = 430;
    private static final int PANEL_MARGIN = 5;
    private static final int PANEL_PADDING = 7;
    private static final int CARD_HEIGHT = 84;
    private static final int FULL_HEADER_HEIGHT = 58;
    private static final int PANEL_BACKGROUND = 0xD8111620;
    private static final int CARD_BACKGROUND = 0xCC1A2230;
    private static final int BAR_BACKGROUND = 0xFF303A49;
    private static final int TEXT_PRIMARY = 0xFFF2F7FF;
    private static final int TEXT_SECONDARY = 0xFFB8C7D9;
    private static final int VISIBLE_COLOR = 0xE055D878;
    private static final int PREDICTED_COLOR = 0xE0FFC857;
    private static final int DECODING_COLOR = 0xE060C8FF;
    private static final int FAILED_COLOR = 0xE0E55B64;
    private static final int SYNC_WARNING_COLOR = 0xE0FFC857;
    private static final int INACTIVE_COLOR = 0xB0394454;
    private static final int RANGE_COLOR = 0x5060C8FF;
    private static final int MAX_WORLD_SCREENS = 32;
    private static volatile PlaybackDebugMode mode = PlaybackDebugMode.OFF;

    private VideoPlaybackDebugRenderer() {
    }

    public static boolean enabled() {
        return mode.enabled();
    }

    public static boolean hudEnabled() {
        return mode.hudEnabled();
    }

    public static boolean rangeEnabled() {
        return mode.rangeEnabled();
    }

    public static PlaybackDebugMode mode() {
        return mode;
    }

    public static PlaybackDebugMode setMode(PlaybackDebugMode value) {
        mode = value != null ? value : PlaybackDebugMode.OFF;
        return mode;
    }

    public static boolean setEnabled(boolean value) {
        return setMode(value ? PlaybackDebugMode.BOTH : PlaybackDebugMode.OFF).enabled();
    }

    public static boolean toggle() {
        return setEnabled(!enabled());
    }

    public static List<String> describe() {
        List<String> result = new ArrayList<>();
        var resources = VideoBillboardPreview.resourceDiagnostics();
        var snapshots = VideoBillboardPreview.videoDebugSnapshots();
        int screens = snapshots.stream().mapToInt(snapshot -> snapshot.projectors().size()).sum();
        result.add("视频调试模式=" + mode.name()
                + " 会话=" + snapshots.size() + " 屏幕=" + screens
                + " 解码=" + resources.runningInstances() + " 待解析=" + resources.pendingLoading());
        for (var snapshot : snapshots.stream().limit(12).toList()) {
            long visible = snapshot.projectors().stream()
                    .filter(projector -> projector.submittedByFrustum()).count();
            long predicted = snapshot.projectors().stream()
                    .filter(projector -> projector.predictedVisible()).count();
            result.add("video=" + shortId(snapshot.sessionId()) + " state=" + state(snapshot)
                    + " visible=" + visible + "/" + snapshot.projectors().size()
                    + " predicted=" + predicted + " admission=" + snapshot.decodeAdmission()
                    + " prewarm=" + snapshot.prewarm() + " paused=" + snapshot.offscreenPaused()
                    + " sync=" + VideoVisualSyncPolicy.debugStatus(snapshot.syncActive())
                    + " frame=" + snapshot.hasFrame() + " expected=" + formatMillis(snapshot.expectedMediaMillis())
                    + " video=" + formatMillis(snapshot.mediaMillis())
                    + " queued=" + formatMillis(snapshot.queuedMediaMillis())
                    + " drift=" + syncDelta(snapshot));
        }
        return List.copyOf(result);
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        if (!rangeEnabled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        event.getSubmitNodeCollector().submitCustomGeometry(new PoseStack(), RenderTypes.linesTranslucent(),
                (pose, buffer) -> submitWorld(buffer, pose, cameraPos));
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!hudEnabled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null) return;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        boolean dualPanels = PlaybackRangeDebugRenderer.hudEnabled();
        if (screenHeight < 60) return;
        List<VideoBillboardPreview.VideoDebugSnapshot> snapshots = VideoBillboardPreview.videoDebugSnapshots();
        int visibleCards = Math.min(4, snapshots.size());
        boolean hasOverflow = snapshots.size() > visibleCards;
        int panelHeight = FULL_HEADER_HEIGHT + visibleCards * CARD_HEIGHT + (hasOverflow ? 10 : 0);
        DebugHudLayout.Plan layout = DebugHudLayout.plan(screenWidth, screenHeight, dualPanels,
                PANEL_MAX_WIDTH, panelHeight, PANEL_MARGIN);
        if (!layout.visible()) return;
        var graphics = event.getGuiGraphics();
        var font = minecraft.font;
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(screenWidth - PANEL_MARGIN - layout.renderedWidth(), PANEL_MARGIN);
        pose.scale(layout.scale(), layout.scale());
        int left = 0;
        int top = 0;
        int innerWidth = PANEL_MAX_WIDTH - PANEL_PADDING * 2;
        graphics.fill(left, top, left + PANEL_MAX_WIDTH, top + panelHeight, PANEL_BACKGROUND);
        graphics.fill(left + PANEL_MAX_WIDTH - 3, top, left + PANEL_MAX_WIDTH,
                top + panelHeight, DECODING_COLOR);
        graphics.text(font, "视频视锥 / 解码调试", left + PANEL_PADDING, top + 6, TEXT_PRIMARY, false);

        var resources = VideoBillboardPreview.resourceDiagnostics();
        int screens = snapshots.stream().mapToInt(snapshot -> snapshot.projectors().size()).sum();
        String summary = "会话 " + snapshots.size() + "  屏幕 " + screens + "  解码 "
                + resources.runningInstances() + "  待解析 " + resources.pendingLoading();
        drawWrapped(graphics, font, summary, left + PANEL_PADDING, top + 18, innerWidth, TEXT_SECONDARY, 1);
        drawLegend(graphics, font, left + PANEL_PADDING, top + 31, innerWidth);

        int y = top + FULL_HEADER_HEIGHT;
        for (int index = 0; index < visibleCards; index++) {
            drawCard(graphics, font, snapshots.get(index), left + PANEL_PADDING, y, innerWidth, index);
            y += CARD_HEIGHT;
        }
        if (hasOverflow) {
            drawWrapped(graphics, font, "还有 " + (snapshots.size() - visibleCards)
                    + " 个会话，使用 video dump 查看", left + PANEL_PADDING,
                    top + panelHeight - 9, innerWidth, TEXT_SECONDARY, 1);
        }
        pose.popMatrix();
    }

    private static void drawLegend(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font, int x, int y, int width) {
        String[] labels = { "当前可见", "趋势预热", "解码", "异常" };
        int[] colors = { VISIBLE_COLOR, PREDICTED_COLOR, DECODING_COLOR, FAILED_COLOR };
        int cursor = x;
        for (int index = 0; index < labels.length; index++) {
            int itemWidth = 9 + font.width(labels[index]) + 7;
            if (cursor + itemWidth > x + width) break;
            graphics.fill(cursor, y + 2, cursor + 6, y + 8, colors[index]);
            graphics.text(font, labels[index], cursor + 9, y, TEXT_SECONDARY, false);
            cursor += itemWidth;
        }
    }

    private static void drawCard(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font, VideoBillboardPreview.VideoDebugSnapshot snapshot,
            int x, int y, int width, int index) {
        graphics.fill(x, y + 2, x + width, y + CARD_HEIGHT - 2,
                index % 2 == 0 ? CARD_BACKGROUND : 0xCC18202C);
        int stateColor = snapshot.failed() ? FAILED_COLOR
                : !snapshot.syncActive() ? INACTIVE_COLOR
                : snapshot.hasFrame() ? VISIBLE_COLOR
                : snapshot.decodeAdmission() ? DECODING_COLOR : INACTIVE_COLOR;
        graphics.fill(x, y + 2, x + 3, y + CARD_HEIGHT - 2, stateColor);
        String projector = snapshot.projectors().isEmpty() ? "虚拟屏幕"
                : pos(snapshot.projectors().get(0).projectorPos())
                    + (snapshot.projectors().size() > 1 ? " +" + (snapshot.projectors().size() - 1) : "");
        String title = shortId(snapshot.sessionId()) + "  " + projector + "  "
                + snapshot.width() + "x" + snapshot.height();
        graphics.text(font, title, x + 7, y + 6, TEXT_PRIMARY, false);

        boolean visible = snapshot.projectors().stream()
                .anyMatch(projectorSnapshot -> projectorSnapshot.submittedByFrustum());
        boolean predicted = snapshot.projectors().stream()
                .anyMatch(projectorSnapshot -> projectorSnapshot.predictedVisible());
        boolean[] stages = { visible, predicted, snapshot.decodeAdmission(), snapshot.hasFrame() };
        String[] labels = { "视锥", "趋势", "解码", "帧" };
        int[] colors = { VISIBLE_COLOR, PREDICTED_COLOR, DECODING_COLOR, VISIBLE_COLOR };
        int segmentWidth = Math.max(1, (width - 14 - 6) / 4);
        for (int stage = 0; stage < 4; stage++) {
            int sx = x + 7 + stage * (segmentWidth + 2);
            graphics.fill(sx, y + 19, sx + segmentWidth, y + 29,
                    stages[stage] ? colors[stage] : INACTIVE_COLOR);
            if (font.width(labels[stage]) + 4 <= segmentWidth) {
                graphics.text(font, labels[stage], sx + 2, y + 20, 0xFF10151D, false);
            }
        }
        drawSyncMeter(graphics, font, snapshot, x + 7, y + 34, width - 14);
        String detail = state(snapshot) + " · admission=" + onOff(snapshot.decodeAdmission())
                + " · prewarm=" + onOff(snapshot.prewarm())
                + " · pause=" + onOff(snapshot.offscreenPaused());
        drawWrapped(graphics, font, detail, x + 7, y + 48, width - 14, TEXT_SECONDARY, 1);
        String timing = "目标 " + formatMillis(snapshot.expectedMediaMillis())
                + " · 画面 " + formatMillis(snapshot.mediaMillis())
                + " · 队列 " + formatMillis(snapshot.queuedMediaMillis())
                + " · visual " + formatMillis(snapshot.visualMillis())
                + " · pacing " + formatMillis(snapshot.pacingMillis())
                + " · " + snapshot.fps() + "fps " + snapshot.backend()
                + " · gen " + snapshot.generation() + " " + snapshot.restartState();
        drawWrapped(graphics, font, timing, x + 7, y + 60, width - 14, TEXT_SECONDARY, 2);
    }

    private static void drawSyncMeter(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font, VideoBillboardPreview.VideoDebugSnapshot snapshot,
            int x, int y, int width) {
        long expected = snapshot.expectedMediaMillis();
        if (!snapshot.syncActive()) {
            graphics.fill(x, y, x + width, y + 10, BAR_BACKGROUND);
            graphics.text(font, "视频同步 离屏暂停", x + 3, y + 1, TEXT_SECONDARY, false);
            return;
        }

        long video = snapshot.mediaMillis();
        float health = PlaybackRangeDebugRenderer.syncBarProgress(video, expected);
        long drift = validMillis(video, expected) ? video - expected : Long.MAX_VALUE;
        int color = drift == Long.MAX_VALUE ? INACTIVE_COLOR
                : Math.abs(drift) <= 250L ? VISIBLE_COLOR
                : Math.abs(drift) <= 1_000L ? SYNC_WARNING_COLOR : FAILED_COLOR;
        graphics.fill(x, y, x + width, y + 10, BAR_BACKGROUND);
        graphics.fill(x, y, x + Math.round(width * health), y + 10, color);
        String label = "视频同步 " + signedDelta(video, expected)
                + " · 队列 " + signedDelta(snapshot.queuedMediaMillis(), expected);
        graphics.text(font, label, x + 3, y + 1, TEXT_PRIMARY, false);
    }

    private static void drawWrapped(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font, String value, int x, int y, int width, int color, int lines) {
        List<String> wrapped = PlaybackRangeDebugRenderer.wrapText(value, width, font::width);
        for (int index = 0; index < Math.min(lines, wrapped.size()); index++) {
            graphics.text(font, wrapped.get(index), x, y + index * 9, color, false);
        }
    }

    private static void submitWorld(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos) {
        int count = 0;
        for (var snapshot : VideoBillboardPreview.videoDebugSnapshots()) {
            double aspect = snapshot.width() > 0 && snapshot.height() > 0
                    ? snapshot.width() / (double) snapshot.height() : 16.0D / 9.0D;
            for (var projectorSnapshot : snapshot.projectors()) {
                if (count++ >= MAX_WORLD_SCREENS) return;
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.level == null) continue;
                int color = snapshot.failed() ? FAILED_COLOR
                        : projectorSnapshot.submittedByFrustum() ? VISIBLE_COLOR
                        : projectorSnapshot.predictedVisible() ? PREDICTED_COLOR
                        : snapshot.decodeAdmission() ? DECODING_COLOR : INACTIVE_COLOR;
                if (minecraft.level.getBlockEntity(projectorSnapshot.projectorPos())
                        instanceof VideoProjectorBlockEntity projector) {
                    drawScreen(buffer, pose, cameraPos, List.of(screenCorners(projector, aspect)), color);
                    continue;
                }
                for (var element : ControlConsoleRenderer.videoElementRangeDebugSnapshots()) {
                    if (element.consolePos().equals(projectorSnapshot.projectorPos())) {
                        drawScreen(buffer, pose, cameraPos, element.corners(), color);
                    }
                }
            }
        }
    }

    private static void drawScreen(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos,
            List<Vec3> corners, int color) {
        if (corners == null || corners.size() != 4) return;
        for (int edge = 0; edge < 4; edge++) {
            line(buffer, pose, cameraPos, corners.get(edge), corners.get((edge + 1) % 4), color, 2.6F);
        }
        Vec3 center = corners.get(0).add(corners.get(2)).scale(0.5D);
        cross(buffer, pose, cameraPos, center, 0.35D, color, 2.2F);
        horizontalRing(buffer, pose, cameraPos, center,
                VideoBillboardPreview.maxRenderDistance(), RANGE_COLOR, 1.0F);
    }

    private static Vec3[] screenCorners(VideoProjectorBlockEntity projector, double aspect) {
        BlockPos pos = projector.getBlockPos();
        Vec3 center = new Vec3(pos.getX() + 0.5D + projector.getProjectionDistanceX(),
                pos.getY() + projector.getProjectionHeight(),
                pos.getZ() + 0.5D + projector.getProjectionDistanceZ());
        double halfHeight = 1.35D * Math.abs(projector.getProjectionScale()) * 0.5D;
        double halfWidth = halfHeight * Math.max(0.125D, Math.min(8.0D, aspect));
        double yaw = Math.toRadians(projector.getProjectionYaw());
        double pitch = Math.toRadians(projector.getProjectionPitch());
        Vec3 right = new Vec3(Math.cos(yaw) * halfWidth, 0.0D, Math.sin(yaw) * halfWidth);
        Vec3 up = new Vec3(-Math.sin(yaw) * Math.sin(pitch) * halfHeight,
                Math.cos(pitch) * halfHeight, Math.cos(yaw) * Math.sin(pitch) * halfHeight);
        return new Vec3[] { center.subtract(right).add(up), center.subtract(right).subtract(up),
                center.add(right).subtract(up), center.add(right).add(up) };
    }

    private static void horizontalRing(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos,
            Vec3 center, double radius, int color, float width) {
        if (!(radius > 0.0D)) return;
        for (int index = 0; index < 24; index++) {
            double a = Math.PI * 2.0D * index / 24.0D;
            double b = Math.PI * 2.0D * (index + 1) / 24.0D;
            line(buffer, pose, cameraPos, center.add(Math.cos(a) * radius, 0, Math.sin(a) * radius),
                    center.add(Math.cos(b) * radius, 0, Math.sin(b) * radius), color, width);
        }
    }

    private static void cross(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos,
            Vec3 center, double radius, int color, float width) {
        line(buffer, pose, cameraPos, center.add(-radius, 0, 0), center.add(radius, 0, 0), color, width);
        line(buffer, pose, cameraPos, center.add(0, -radius, 0), center.add(0, radius, 0), color, width);
        line(buffer, pose, cameraPos, center.add(0, 0, -radius), center.add(0, 0, radius), color, width);
    }

    private static void line(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos,
            Vec3 from, Vec3 to, int color, float width) {
        float x1 = (float) (from.x - cameraPos.x), y1 = (float) (from.y - cameraPos.y);
        float z1 = (float) (from.z - cameraPos.z), x2 = (float) (to.x - cameraPos.x);
        float y2 = (float) (to.y - cameraPos.y), z2 = (float) (to.z - cameraPos.z);
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float inverse = 1.0F / Math.max(1.0e-6F, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        buffer.addVertex(pose, x1, y1, z1).setColor(color)
                .setNormal(pose, dx * inverse, dy * inverse, dz * inverse).setLineWidth(width);
        buffer.addVertex(pose, x2, y2, z2).setColor(color)
                .setNormal(pose, dx * inverse, dy * inverse, dz * inverse).setLineWidth(width);
    }

    private static String state(VideoBillboardPreview.VideoDebugSnapshot snapshot) {
        if (snapshot.failed()) return "FAILED";
        if (!snapshot.running()) return "STOPPED";
        if (snapshot.offscreenPaused()) return "OFFSCREEN_PAUSED";
        if (!snapshot.decodeAdmission()) return "WAIT_VISIBLE";
        if (!snapshot.syncActive()) return "OFFSCREEN_IDLE";
        if (!snapshot.hasFrame()) return "DECODING";
        return "PLAYING";
    }

    private static String pos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private static boolean validMillis(long first, long second) {
        return first >= 0L && second >= 0L;
    }
    private static String syncDelta(VideoBillboardPreview.VideoDebugSnapshot snapshot) {
        return snapshot.syncActive()
                ? signedDelta(snapshot.mediaMillis(), snapshot.expectedMediaMillis()) : "SUSPENDED";
    }


    private static String formatMillis(long value) {
        return value >= 0L ? value + "ms" : "-";
    }

    static String signedDelta(long actual, long expected) {
        if (!validMillis(actual, expected)) {
            return "-";
        }
        long delta = actual - expected;
        return (delta >= 0L ? "+" : "") + delta + "ms";
    }

    private static String shortId(String value) {
        if (value == null) return "-";
        return value.length() <= 12 ? value : value.substring(0, 12);
    }
}
