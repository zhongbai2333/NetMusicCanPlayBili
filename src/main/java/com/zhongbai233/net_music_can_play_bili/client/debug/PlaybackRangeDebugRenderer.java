package com.zhongbai233.net_music_can_play_bili.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaDemandScheduler;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;
import com.zhongbai233.net_music_can_play_bili.media.audio.IndexedAudioEndpoint;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Toggleable world-space ranges and indexed playback lifecycle diagnostics. */
@EventBusSubscriber(modid = NetMusicCanPlayBili.MODID, value = Dist.CLIENT)
public final class PlaybackRangeDebugRenderer {
    private static final int NOMINAL_COLOR = 0xCC48FF70;
    private static final int RESOLVE_COLOR = 0xD8FFE052;
    private static final int NOTICE_COLOR = 0xB8FF914D;
    private static final int SYNC_COLOR = 0x9060C8FF;
    private static final int CONSOLE_ACTIVE_COLOR = 0xC8CB69FF;
    private static final int CONSOLE_IDLE_COLOR = 0x887A4A9E;
    private static final int DIRECT_ROUTE_COLOR = 0xD8FF66D8;
    private static final int MAX_WORLD_ENTRIES = 128;
    private static final int PANEL_MAX_WIDTH = 430;
    private static final int PANEL_MARGIN = 5;
    private static final int PANEL_PADDING = 7;
    private static final int CARD_HEIGHT = 72;
    private static final int PANEL_BACKGROUND = 0xD8111620;
    private static final int CARD_BACKGROUND = 0xCC1A2230;
    private static final int BAR_BACKGROUND = 0xFF303A49;
    private static final int TEXT_PRIMARY = 0xFFF2F7FF;
    private static final int TEXT_SECONDARY = 0xFFB8C7D9;
    private static final int STATE_METADATA = 0xFF5F8DFF;
    private static final int STATE_STARTING = 0xFFFFC857;
    private static final int STATE_PLAYING = 0xFF55D878;
    private static final int STATE_INACTIVE = 0xFF394454;
    private static volatile boolean enabled;

    private PlaybackRangeDebugRenderer() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static boolean setEnabled(boolean value) {
        enabled = value;
        return enabled;
    }

    public static boolean toggle() {
        return setEnabled(!enabled);
    }

    public static List<String> describe() {
        List<String> lines = new ArrayList<>();
        var indexed = ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots();
        var handheld = ClientMediaDemandScheduler.debugSnapshots();
        var endpoints = ClientAudioEndpointIndex.endpointSnapshot();
        var consoles = ControlConsoleRenderer.rangeDebugSnapshots();
        var consoleElements = ControlConsoleRenderer.elementRangeDebugSnapshots();
        lines.add("播放范围可视化=" + (enabled ? "开启" : "关闭") + " 来源=" + indexed.size()
                + " 端点=" + endpoints.size() + " 中控音频元素=" + consoleElements.size()
                + " 移动媒体=" + handheld.size() + " 中控硬范围=" + consoles.size());
        for (var source : indexed.stream().limit(12).toList()) {
            var timeline = ClientAudioOutputRegistry.getAudioTimeline(source.sourcePos());
            long bufferMillis = bufferLeadMillis(timeline.audibleMillis(), timeline.mainFedMillis());
            var demand = ClientAudioOutputRegistry.audioDemandDebug(
                    source.sourcePos(), source.sourceId(), source.sessionId().value());
            lines.add("source=" + shortId(source.sourceId().toString()) + " session="
                    + shortId(source.sessionId().value()) + " state=" + source.state()
                    + " demand=" + source.demandCount() + " resolving=" + source.preparingUrl()
                    + " route=" + describeRoute(demand)
                    + " volume=" + percent(source.volume()) + " audible=" + timeline.audibleMillis()
                    + "ms fed=" + timeline.mainFedMillis() + "ms buffer=" + bufferMillis
                    + "ms(" + bufferHealth(bufferMillis) + ") relays=" + timeline.relayStartedCount()
                    + "/" + timeline.relayRegisteredCount() + " outputSession="
                    + shortId(timeline.audioSessionId()));
        }
        for (var media : handheld.stream().limit(8).toList()) {
            lines.add("media=" + shortId(media.sourceId().toString()) + " session="
                    + shortId(media.sessionId().value()) + " state=" + media.state()
                    + " demand=" + media.demandCount() + " route="
                    + (media.headphoneRouted() ? "HEADPHONES" : "SPATIAL")
                    + " volume=" + percent(media.volume()));
        }
        return List.copyOf(lines);
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        if (!enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        PoseStack poseStack = new PoseStack();
        event.getSubmitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.linesTranslucent(),
                (pose, buffer) -> submitWorld(buffer, pose, cameraPos));
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null) {
            return;
        }
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        if (screenWidth <= PANEL_MARGIN * 2 + 80 || screenHeight <= PANEL_MARGIN * 2 + 40) {
            return;
        }
        int panelWidth = Math.min(PANEL_MAX_WIDTH, screenWidth - PANEL_MARGIN * 2);
        int innerWidth = panelWidth - PANEL_PADDING * 2;
        List<HudEntry> entries = hudEntries();
        int fixedHeight = 86;
        int maxCards = Math.max(0, Math.min(4,
                (screenHeight - PANEL_MARGIN * 2 - fixedHeight) / CARD_HEIGHT));
        int visibleCards = Math.min(maxCards, entries.size());
        boolean hasOverflow = entries.size() > visibleCards;
        int panelHeight = fixedHeight + visibleCards * CARD_HEIGHT + (hasOverflow ? 10 : 0);

        var graphics = event.getGuiGraphics();
        var font = minecraft.font;
        int left = PANEL_MARGIN;
        int top = PANEL_MARGIN;
        graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL_BACKGROUND);
        graphics.fill(left, top, left + 3, top + panelHeight, 0xFF60C8FF);
        graphics.text(font, "播放范围调试", left + PANEL_PADDING, top + 6, TEXT_PRIMARY, false);

        DebugCounts counts = debugCounts();
        String summary = "来源 " + counts.sources() + "  端点 " + counts.endpoints()
                + "  中控元素 " + counts.consoleElements() + "  移动媒体 " + counts.handheld()
                + "  硬范围 " + counts.consoles();
        drawWrapped(graphics, font::width, font, summary, left + PANEL_PADDING, top + 18,
                innerWidth, TEXT_SECONDARY, 9, 2);

        int legendY = top + 39;
        legendY = drawLegend(graphics, font, left + PANEL_PADDING, legendY, innerWidth);
        drawLifecycleGuide(graphics, font, left + PANEL_PADDING, legendY + 3, innerWidth);

        int cardY = top + fixedHeight;
        for (int index = 0; index < visibleCards; index++) {
            drawEntryCard(graphics, font, entries.get(index), left + PANEL_PADDING, cardY,
                    innerWidth, index);
            cardY += CARD_HEIGHT;
        }
        if (hasOverflow) {
            String overflow = "还有 " + (entries.size() - visibleCards)
                    + " 项，使用 /ncpb playbackdebug status 查看详情";
            drawWrapped(graphics, font::width, font, overflow, left + PANEL_PADDING,
                    top + panelHeight - 9, innerWidth, TEXT_SECONDARY, 9, 1);
        }
    }

    private static DebugCounts debugCounts() {
        return new DebugCounts(
                ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots().size(),
                ClientAudioEndpointIndex.endpointSnapshot().size(),
                ControlConsoleRenderer.elementRangeDebugSnapshots().size(),
                ClientMediaDemandScheduler.debugSnapshots().size(),
                ControlConsoleRenderer.rangeDebugSnapshots().size());
    }

    private static List<HudEntry> hudEntries() {
        List<HudEntry> entries = new ArrayList<>();
        for (var source : ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots().stream()
                .limit(12).toList()) {
            var timeline = ClientAudioOutputRegistry.getAudioTimeline(source.sourcePos());
            long visualMillis = PlaybackClock.visualMillis(source.sourcePos());
            var demand = ClientAudioOutputRegistry.audioDemandDebug(
                    source.sourcePos(), source.sourceId(), source.sessionId().value());
            entries.add(new HudEntry("音源", shortId(source.sourceId().toString()),
                    shortId(source.sessionId().value()), source.state().name(), source.demandCount(),
                    source.preparingUrl(), describeRoute(demand), source.volume(), timeline.audibleMillis(),
                    timeline.mainFedMillis(), visualMillis, timeline.relayStartedCount(),
                    timeline.relayRegisteredCount(),
                    shortId(timeline.audioSessionId())));
        }
        for (var media : ClientMediaDemandScheduler.debugSnapshots().stream().limit(8).toList()) {
            entries.add(new HudEntry("移动", shortId(media.sourceId().toString()),
                    shortId(media.sessionId().value()), media.state().name(), media.demandCount(), false,
                    media.headphoneRouted() ? "耳机" : "空间声", media.volume(), -1L, -1L, -1L, 0, 0, "-"));
        }
        return List.copyOf(entries);
    }

    private static int drawLegend(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font, int x, int y, int width) {
        List<LegendItem> items = List.of(
                new LegendItem("标称", NOMINAL_COLOR),
                new LegendItem("淡出终点", RESOLVE_COLOR),
                new LegendItem("提示", NOTICE_COLOR),
                new LegendItem("同步", SYNC_COLOR),
                new LegendItem("中控 AABB", CONSOLE_ACTIVE_COLOR));
        int cursorX = x;
        int cursorY = y;
        for (LegendItem item : items) {
            int itemWidth = 9 + font.width(item.label()) + 8;
            if (cursorX > x && cursorX + itemWidth > x + width) {
                cursorX = x;
                cursorY += 10;
            }
            graphics.fill(cursorX, cursorY + 2, cursorX + 6, cursorY + 8, item.color());
            graphics.text(font, item.label(), cursorX + 9, cursorY, TEXT_SECONDARY, false);
            cursorX += itemWidth;
        }
        return cursorY + 9;
    }

    private static void drawLifecycleGuide(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font, int x, int y, int width) {
        graphics.text(font, "重新进入", x, y, TEXT_SECONDARY, false);
        int barX = x + 48;
        int barWidth = Math.max(90, width - 48);
        drawLifecycleSegments(graphics, font, barX, y, barWidth, 3);
    }

    private static void drawEntryCard(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font, HudEntry entry, int x, int y, int width, int index) {
        int background = index % 2 == 0 ? CARD_BACKGROUND : 0xCC18202C;
        graphics.fill(x, y + 2, x + width, y + CARD_HEIGHT - 2, background);
        graphics.fill(x, y + 2, x + 3, y + CARD_HEIGHT - 2, stateColor(entry.state()));

        String title = entry.kind() + " " + entry.source() + "  会话 " + entry.session()
                + "  " + entry.route();
        drawWrapped(graphics, font::width, font, title, x + 7, y + 6,
                width - 14, TEXT_PRIMARY, 9, 2);

        int stateLabelWidth = 58;
        graphics.text(font, entry.state(), x + 7, y + 26, stateColor(entry.state()), false);
        drawLifecycleSegments(graphics, font, x + 7 + stateLabelWidth, y + 26,
                width - 14 - stateLabelWidth, lifecycleStage(entry.state()));

        int meterGap = 8;
        int meterWidth = (width - 14 - meterGap) / 2;
        drawVolumeMeter(graphics, font, x + 7, y + 38, meterWidth, entry.volume());
        drawSyncMeter(graphics, font, x + 7 + meterWidth + meterGap, y + 38, meterWidth,
                entry.audibleMillis(), entry.visualMillis());

        long syncDrift = validTimeline(entry.audibleMillis(), entry.visualMillis())
                ? entry.audibleMillis() - entry.visualMillis() : 0L;
        long bufferMillis = bufferLeadMillis(entry.audibleMillis(), entry.fedMillis());
        String details = "需求 " + entry.demands() + (entry.resolving() ? " · 解析中" : "")
                + " · relay " + entry.relayStarted() + "/" + entry.relayRegistered()
                + (validTimeline(entry.audibleMillis(), entry.visualMillis())
                        ? " · 播放差 " + signedMillis(syncDrift) : "")
                + (bufferMillis >= 0L
                        ? " · 缓冲 " + bufferMillis + "ms " + bufferHealth(bufferMillis) : "")
                + " · 输出会话 " + entry.outputSession();
        drawWrapped(graphics, font::width, font, details, x + 7, y + 51,
                width - 14, TEXT_SECONDARY, 9, 2);
    }

    private static void drawLifecycleSegments(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font, int x, int y, int width, int activeStage) {
        String[] labels = { "META", "START", "PLAY" };
        int[] colors = { STATE_METADATA, STATE_STARTING, STATE_PLAYING };
        int segmentWidth = Math.max(1, (width - 4) / 3);
        for (int index = 0; index < 3; index++) {
            int segmentX = x + index * (segmentWidth + 2);
            int color = index < activeStage ? colors[index] : STATE_INACTIVE;
            graphics.fill(segmentX, y + 1, segmentX + segmentWidth, y + 9, color);
            int labelWidth = font.width(labels[index]);
            if (labelWidth + 4 <= segmentWidth) {
                graphics.text(font, labels[index], segmentX + (segmentWidth - labelWidth) / 2,
                        y + 1, 0xFF10151D, false);
            }
        }
    }

    private static void drawVolumeMeter(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font, int x, int y, int width, float volume) {
        String label = "音量 " + percent(volume);
        graphics.text(font, label, x, y, TEXT_SECONDARY, false);
        int barX = x + Math.min(58, font.width(label) + 5);
        int barWidth = Math.max(12, width - (barX - x));
        graphics.fill(barX, y + 2, barX + barWidth, y + 8, BAR_BACKGROUND);
        int normalWidth = Math.round(barWidth * Math.min(0.5F, volumeBarProgress(volume)));
        int boostWidth = Math.round(barWidth * Math.max(0.0F, volumeBarProgress(volume) - 0.5F));
        graphics.fill(barX, y + 2, barX + normalWidth, y + 8, STATE_PLAYING);
        if (boostWidth > 0) {
            graphics.fill(barX + normalWidth, y + 2,
                    Math.min(barX + barWidth, barX + normalWidth + boostWidth), y + 8, NOTICE_COLOR);
        }
        graphics.fill(barX + barWidth / 2, y + 1, barX + barWidth / 2 + 1, y + 9, 0xCCFFFFFF);
    }

    private static void drawSyncMeter(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font, int x, int y, int width, long audibleMillis, long visualMillis) {
        String label = !validTimeline(audibleMillis, visualMillis) ? "播放 -" : "播放同步";
        graphics.text(font, label, x, y, TEXT_SECONDARY, false);
        int barX = x + Math.min(58, font.width(label) + 5);
        int barWidth = Math.max(12, width - (barX - x));
        float progress = syncBarProgress(audibleMillis, visualMillis);
        long drift = validTimeline(audibleMillis, visualMillis)
                ? Math.abs(audibleMillis - visualMillis) : Long.MAX_VALUE;
        int color = drift <= 250L ? STATE_PLAYING
                : drift <= 500L ? SYNC_COLOR
                : drift < 2_000L ? RESOLVE_COLOR : 0xFFE55B64;
        graphics.fill(barX, y + 2, barX + barWidth, y + 8, BAR_BACKGROUND);
        graphics.fill(barX, y + 2, barX + Math.round(barWidth * progress), y + 8, color);
    }

    private static void drawWrapped(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            ToIntFunction<String> widthFunction, net.minecraft.client.gui.Font font, String value,
            int x, int y, int maxWidth, int color, int lineHeight, int maxLines) {
        List<String> wrapped = wrapText(value, maxWidth, widthFunction);
        for (int index = 0; index < Math.min(maxLines, wrapped.size()); index++) {
            String line = wrapped.get(index);
            if (index == maxLines - 1 && wrapped.size() > maxLines) {
                line = ellipsize(line, maxWidth, widthFunction);
            }
            graphics.text(font, line, x, y + index * lineHeight, color, false);
        }
    }

    static List<String> wrapText(String value, int maxWidth, ToIntFunction<String> widthFunction) {
        if (value == null || value.isBlank() || maxWidth <= 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        String remaining = value.strip();
        while (!remaining.isEmpty()) {
            if (widthFunction.applyAsInt(remaining) <= maxWidth) {
                result.add(remaining);
                break;
            }
            int fit = 1;
            while (fit < remaining.length()
                    && widthFunction.applyAsInt(remaining.substring(0, fit + 1)) <= maxWidth) {
                fit++;
            }
            int preferred = preferredBreak(remaining, fit);
            int end = preferred > 0 ? preferred : fit;
            String line = remaining.substring(0, Math.max(1, end)).stripTrailing();
            result.add(line);
            remaining = remaining.substring(Math.max(1, end)).stripLeading();
        }
        return List.copyOf(result);
    }

    private static int preferredBreak(String value, int fit) {
        for (int index = Math.min(fit, value.length() - 1); index > 0; index--) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || character == '|' || character == '·'
                    || character == ',' || character == ';') {
                return index + (Character.isWhitespace(character) ? 0 : 1);
            }
        }
        return -1;
    }

    private static String ellipsize(String value, int maxWidth, ToIntFunction<String> widthFunction) {
        String ellipsis = "…";
        String result = value;
        while (!result.isEmpty() && widthFunction.applyAsInt(result + ellipsis) > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result.stripTrailing() + ellipsis;
    }

    static int lifecycleStage(String state) {
        if (state == null) return 0;
        return switch (state) {
            case "PLAYING" -> 3;
            case "STARTING", "PREPARING", "BUFFERING", "RECOVERING" -> 2;
            case "METADATA" -> 1;
            default -> 0;
        };
    }

    static float volumeBarProgress(float volume) {
        if (!Float.isFinite(volume)) return 0.0F;
        return Math.max(0.0F, Math.min(1.0F, volume / 2.0F));
    }

    static float syncBarProgress(long audibleMillis, long visualMillis) {
        if (audibleMillis < 0L || visualMillis < 0L) return 0.0F;
        long drift = Math.abs(visualMillis - audibleMillis);
        return Math.max(0.0F, 1.0F - Math.min(1.0F, drift / 2_000.0F));
    }

    static long bufferLeadMillis(long audibleMillis, long fedMillis) {
        if (!validTimeline(audibleMillis, fedMillis)) return -1L;
        return Math.max(0L, fedMillis - audibleMillis);
    }

    static String bufferHealth(long bufferMillis) {
        if (bufferMillis < 0L) return "未知";
        if (bufferMillis < 200L) return "余量低";
        if (bufferMillis <= 1_500L) return "正常";
        if (bufferMillis <= 2_000L) return "偏高";
        return "过深";
    }

    private static boolean validTimeline(long firstMillis, long secondMillis) {
        return firstMillis >= 0L && secondMillis >= 0L;
    }

    private static int stateColor(String state) {
        return switch (lifecycleStage(state)) {
            case 3 -> STATE_PLAYING;
            case 2 -> STATE_STARTING;
            case 1 -> STATE_METADATA;
            default -> TEXT_SECONDARY;
        };
    }

    private static String signedMillis(long value) {
        return (value > 0L ? "+" : "") + value + "ms";
    }


    private static void submitWorld(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos) {
        int count = 0;
        for (var source : ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots()) {
            if (count++ >= MAX_WORLD_ENTRIES) {
                break;
            }
            Vec3 center = source.sourcePos().getCenter();
            drawProfile(buffer, pose, cameraPos, center,
                    AudioPlaybackRange.profile(AudioPlaybackRange.DEFAULT_DISTANCE, source.volume(), source.volume()));
            ellipsoid(buffer, pose, cameraPos, center, AudioPlaybackRange.SYNC_DISTANCE_BLOCKS,
                    AudioPlaybackRange.SYNC_DISTANCE_BLOCKS, AudioPlaybackRange.SYNC_DISTANCE_BLOCKS,
                    SYNC_COLOR, 1.0F);
        }
        for (IndexedAudioEndpoint endpoint : ClientAudioEndpointIndex.endpointSnapshot()) {
            if (count++ >= MAX_WORLD_ENTRIES) {
                break;
            }
            drawProfile(buffer, pose, cameraPos, new Vec3(endpoint.x(), endpoint.y(), endpoint.z()),
                    AudioPlaybackRange.profile(endpoint.configuredDistance(), endpoint.rangeScale(),
                            endpoint.outputGain()));
        }
        for (var media : ClientMediaDemandScheduler.debugSnapshots()) {
            if (count++ >= MAX_WORLD_ENTRIES) {
                break;
            }
            if (media.headphoneRouted()) {
                cross(buffer, pose, cameraPos, media.position(), 1.0F, DIRECT_ROUTE_COLOR, 2.2F);
            } else {
                drawProfile(buffer, pose, cameraPos, media.position(),
                        AudioPlaybackRange.profile(AudioPlaybackRange.DEFAULT_DISTANCE,
                                media.volume(), media.volume()));
            }
        }
        for (var element : ControlConsoleRenderer.elementRangeDebugSnapshots()) {
            if (count++ >= MAX_WORLD_ENTRIES) {
                break;
            }
            drawProfile(buffer, pose, cameraPos, element.center(),
                    AudioPlaybackRange.profile(element.configuredDistance(), element.volume(), element.volume()));
        }
        // The purple AABB is the console-level hard gate. A console audio element is
        // effective only where its own sphere above overlaps this gate.
        for (var console : ControlConsoleRenderer.rangeDebugSnapshots()) {
            if (count++ >= MAX_WORLD_ENTRIES) {
                break;
            }
            box(buffer, pose, cameraPos, console.consolePos().getCenter(), console.radiusX(),
                    console.radiusY(), console.radiusZ(),
                    console.active() ? CONSOLE_ACTIVE_COLOR : CONSOLE_IDLE_COLOR, 1.8F);
        }
    }

    private static void drawProfile(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos, Vec3 center,
            AudioPlaybackRange.Profile profile) {
        ellipsoid(buffer, pose, cameraPos, center, profile.nominalDistance(), profile.nominalDistance(),
                profile.nominalDistance(), NOMINAL_COLOR, 1.8F);
        ellipsoid(buffer, pose, cameraPos, center, profile.fadeEndDistance(), profile.fadeEndDistance(),
                profile.fadeEndDistance(), RESOLVE_COLOR, 1.5F);
        ellipsoid(buffer, pose, cameraPos, center, profile.noticeDistance(), profile.noticeDistance(),
                profile.noticeDistance(), NOTICE_COLOR, 1.0F);
        cross(buffer, pose, cameraPos, center, 0.65F, NOMINAL_COLOR, 2.5F);
    }

    private static void ellipsoid(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos, Vec3 center,
            double radiusX, double radiusY, double radiusZ, int color, float width) {
        if (!(radiusX > 0.0D && radiusY > 0.0D && radiusZ > 0.0D)) {
            return;
        }
        ring(buffer, pose, cameraPos, center, radiusX, radiusY, radiusZ, 0, color, width);
        ring(buffer, pose, cameraPos, center, radiusX, radiusY, radiusZ, 1, color, width);
        ring(buffer, pose, cameraPos, center, radiusX, radiusY, radiusZ, 2, color, width);
    }

    private static void ring(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos, Vec3 center,
            double radiusX, double radiusY, double radiusZ, int plane, int color, float width) {
        final int segments = 48;
        for (int index = 0; index < segments; index++) {
            double first = Math.PI * 2.0D * index / segments;
            double second = Math.PI * 2.0D * (index + 1) / segments;
            Vec3 a = ringPoint(center, radiusX, radiusY, radiusZ, plane, first);
            Vec3 b = ringPoint(center, radiusX, radiusY, radiusZ, plane, second);
            line(buffer, pose, cameraPos, a, b, color, width);
        }
    }

    private static void box(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos, Vec3 center,
            double halfX, double halfY, double halfZ, int color, float width) {
        if (!(halfX > 0.0D && halfY > 0.0D && halfZ > 0.0D)) {
            return;
        }
        for (int ySign : new int[] { -1, 1 }) {
            for (int zSign : new int[] { -1, 1 }) {
                line(buffer, pose, cameraPos, center.add(-halfX, ySign * halfY, zSign * halfZ),
                        center.add(halfX, ySign * halfY, zSign * halfZ), color, width);
            }
        }
        for (int xSign : new int[] { -1, 1 }) {
            for (int zSign : new int[] { -1, 1 }) {
                line(buffer, pose, cameraPos, center.add(xSign * halfX, -halfY, zSign * halfZ),
                        center.add(xSign * halfX, halfY, zSign * halfZ), color, width);
            }
            for (int ySign : new int[] { -1, 1 }) {
                line(buffer, pose, cameraPos, center.add(xSign * halfX, ySign * halfY, -halfZ),
                        center.add(xSign * halfX, ySign * halfY, halfZ), color, width);
            }
        }
    }

    private static Vec3 ringPoint(Vec3 center, double radiusX, double radiusY, double radiusZ,
            int plane, double angle) {
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        return switch (plane) {
            case 0 -> center.add(radiusX * cosine, radiusY * sine, 0.0D);
            case 1 -> center.add(radiusX * cosine, 0.0D, radiusZ * sine);
            default -> center.add(0.0D, radiusY * cosine, radiusZ * sine);
        };
    }

    private static void cross(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos, Vec3 center,
            float radius, int color, float width) {
        line(buffer, pose, cameraPos, center.add(-radius, 0, 0), center.add(radius, 0, 0), color, width);
        line(buffer, pose, cameraPos, center.add(0, -radius, 0), center.add(0, radius, 0), color, width);
        line(buffer, pose, cameraPos, center.add(0, 0, -radius), center.add(0, 0, radius), color, width);
    }

    private static void line(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos, Vec3 from, Vec3 to,
            int color, float width) {
        float x1 = (float) (from.x - cameraPos.x);
        float y1 = (float) (from.y - cameraPos.y);
        float z1 = (float) (from.z - cameraPos.z);
        float x2 = (float) (to.x - cameraPos.x);
        float y2 = (float) (to.y - cameraPos.y);
        float z2 = (float) (to.z - cameraPos.z);
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float inverse = 1.0F / Math.max(1.0e-6F, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        buffer.addVertex(pose, x1, y1, z1).setColor(color)
                .setNormal(pose, dx * inverse, dy * inverse, dz * inverse).setLineWidth(width);
        buffer.addVertex(pose, x2, y2, z2).setColor(color)
                .setNormal(pose, dx * inverse, dy * inverse, dz * inverse).setLineWidth(width);
    }

    private static String shortId(String value) {
        if (value == null) {
            return "-";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String describeRoute(ClientAudioOutputRegistry.AudioDemandDebug demand) {
        if (demand.headphone()) {
            return "耳机";
        }
        if (!demand.mainSuppressed()) {
            return "唱片机本体";
        }
        return "外设独占(本体静音,中控=" + (demand.consoleRoute() ? "是" : "否")
                + ",输出=" + demand.matchingRelays() + ")";
    }

    private static String percent(float value) {
        return Math.round(value * 100.0F) + "%";
    }

    private record LegendItem(String label, int color) {
    }

    private record DebugCounts(int sources, int endpoints, int consoleElements, int handheld, int consoles) {
    }

    private record HudEntry(String kind, String source, String session, String state, int demands,
            boolean resolving, String route, float volume, long audibleMillis, long fedMillis,
            long visualMillis, int relayStarted, int relayRegistered, String outputSession) {
    }
}
