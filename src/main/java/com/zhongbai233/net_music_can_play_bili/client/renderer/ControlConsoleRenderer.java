package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.LiveStreamerVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock;
import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.core.media.TimedTextResolver;
import com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleVideoStatePolicy;
import com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleExitFade;
import com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleExitPolicy;
import com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleRangeGate;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** 中控台运行时渲染器：屏幕元素复用绑定源的同一视频 session。 */
public final class ControlConsoleRenderer
        implements BlockEntityRenderer<ControlConsoleBlockEntity, ControlConsoleRenderer.State> {
    private static final Logger LOGGER = LogUtils.getLogger();
        private static final float TEXT_SCALE = com.zhongbai233.net_music_can_play_bili.editor.core.media
            .SubtitleLayout.WORLD_TEXT_SCALE;
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final Map<BlockPos, Set<BlockPos>> CONSOLE_AUDIO_KEYS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, BlockPos> CONSOLE_AUDIO_SOURCES = new ConcurrentHashMap<>();
    private static final Map<BlockPos, ConsumerState> CONSUMERS = new ConcurrentHashMap<>();
    private static final long CONSUMER_LEASE_MILLIS = 3_000L;
    private static final long CONSUMER_RENEW_MILLIS = 1_000L;
        private static final long VIDEO_HEALTH_CHECK_MILLIS = Long.getLong(
            "ncpb.control_console.video_health_check_ms", 1_000L);
    private final Font font;

    public ControlConsoleRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ControlConsoleBlockEntity console, State state, float partialTick,
            Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(console, state, partialTick, cameraPos, crumblingOverlay);
        state.consolePos = console.getBlockPos().immutable();
        state.elements = console.document().elements();
        state.sourcePos = sourcePos(console.document(), console.getLevel());
        state.sourceKind = console.document().sourceKind();
        if (state.sourcePos != null && state.sourceKind == ControlConsoleDocument.SourceKind.TURNTABLE) {
            ClientLinkRegistry.link(state.consolePos, state.sourcePos);
        } else {
            ClientLinkRegistry.unlink(state.consolePos);
        }
        registerConsumer(console);
        reconcileConsumer(state.consolePos);
        state.consumerActive = isConsumerActive(state.consolePos);
        state.irisCompatibilityMode = IrisShaderpackCompat.isShaderPackInUse();
        state.exitGain = state.irisCompatibilityMode ? 1.0F : consumerExitGain(state.consolePos);
        state.sourcePlaying = false;
        state.videoExpected = false;
        SourceSnapshot sourceSnapshot = sourceSnapshot(console.document(), console.getLevel(), state.sourcePos);
        state.sourcePlaying = sourceSnapshot.playing();
        state.videoExpected = sourceSnapshot.videoExpected();
        VideoBillboardPreview.ControlConsoleVideoSnapshot video = state.consumerActive
            ? VideoBillboardPreview.currentControlConsoleVideo(state.consolePos, state.sourcePlaying,
                state.videoExpected)
            : null;
        state.videoState = video != null ? video.state() : ControlConsoleVideoStatePolicy.State.IDLE;
        state.frame = video != null ? video.frame() : VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        state.sessionId = video != null ? video.sessionId() : null;
        state.currentLyric = "";
        state.translatedLyric = "";
        state.lyrics = null;
        state.transLyrics = null;
        state.lyricTick = -1;
        state.lyricVisualTick = -1.0F;
        if (sourceSnapshot.lyric() != null) {
                var lyric = sourceSnapshot.lyric();
                int lyricTick = sourceSnapshot.lyricTick();
                state.currentLyric = lineAt(lyric.getLyrics(), lyricTick);
                state.translatedLyric = lineAt(lyric.getTransLyrics(), lyricTick);
                state.lyrics = lyric.getLyrics();
                state.transLyrics = lyric.getTransLyrics();
                state.lyricTick = lyricTick;
                state.lyricVisualTick = sourceSnapshot.lyricVisualTick();
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState cameraState) {
        if (!state.consumerActive || state.consolePos == null || state.elements == null || state.elements.isEmpty()
                || state.frame == null) {
            return;
        }
        if ((state.videoState == ControlConsoleVideoStatePolicy.State.ACTIVE
            || state.videoState == ControlConsoleVideoStatePolicy.State.BUFFERING)
            && state.sessionId != null) {
            VideoBillboardPreview.markProjectorSubmittedByBer(state.sessionId, state.consolePos);
        }
        for (ControlConsoleElement element : state.elements) {
            if (!element.enabled()) {
                continue;
            }
            float halfHeight = element.height() * 0.5F;
            float halfWidth = halfHeight * element.aspect();
            poseStack.pushPose();
            poseStack.translate(0.5D + element.offsetX(), 1.55D + element.offsetY(),
                    0.5D + element.distance());
            poseStack.mulPose(new org.joml.Quaternionf().rotateYXZ(
                    (float) Math.toRadians(element.yaw()), (float) Math.toRadians(element.pitch()),
                    (float) Math.toRadians(element.roll())));
            Matrix4f pose = new Matrix4f(poseStack.last().pose());
            if (element.type() == ControlConsoleElement.Type.SCREEN) {
                if (state.videoState == ControlConsoleVideoStatePolicy.State.ACTIVE && state.sessionId != null) {
                    VideoBillboardPreview.captureProjectorImmediatePose(state.sessionId, state.consolePos, pose,
                            halfHeight, state.exitGain);
                }
                VideoBillboardPreview.submitProjectorFrameOnPose(collector, poseStack, state.frame, halfWidth,
                    halfHeight, VideoBillboardPreview.cameraRelativeBackOffset(pose,
                        state.frame.rgbaDepthOffset()), state.exitGain);
            } else if (element.type() == ControlConsoleElement.Type.SUBTITLE) {
                submitSubtitle(state, element, poseStack, collector);
            }
            poseStack.popPose();
        }
    }

    private void submitSubtitle(State state, ControlConsoleElement element, PoseStack poseStack,
            SubmitNodeCollector collector) {
        String mode = element.contentMode();
        boolean scrolling = "SCROLL_MAIN".equals(mode) || "SCROLL_TRANSLATION".equals(mode);
        if (scrolling && state.lyricTick >= 0) {
            Int2ObjectSortedMap<String> track = "SCROLL_TRANSLATION".equals(mode)
                    ? state.transLyrics : state.lyrics;
            if (track == null || track.isEmpty()) {
                track = state.lyrics;
            }
            TimedTextResolver.Window window = TimedTextResolver.window(track, state.lyricTick, 2, 2,
                state.lyricVisualTick >= 0.0F ? state.lyricVisualTick : state.lyricTick);
            submitScrollingSubtitle(window, element, poseStack, collector, state.exitGain);
            return;
        }
        String current = element.followLyrics() ? state.currentLyric : element.text();
        if ((current == null || current.isBlank()) && element.followLyrics()) {
            current = element.text();
        }
        String translated = element.followLyrics() && element.showTranslation() ? state.translatedLyric : "";
        if ((current == null || current.isBlank()) && (translated == null || translated.isBlank())) {
            return;
        }
        submitSubtitleFace(current, translated, element, poseStack, collector, false, state.exitGain);
        submitSubtitleFace(current, translated, element, poseStack, collector, true, state.exitGain);
    }

    private void submitScrollingSubtitle(TimedTextResolver.Window window, ControlConsoleElement element,
            PoseStack poseStack, SubmitNodeCollector collector, float opacity) {
        submitScrollingSubtitleFace(window, element, poseStack, collector, opacity, false);
        submitScrollingSubtitleFace(window, element, poseStack, collector, opacity, true);
    }

    private void submitScrollingSubtitleFace(TimedTextResolver.Window window, ControlConsoleElement element,
            PoseStack poseStack, SubmitNodeCollector collector, float opacity, boolean backFace) {
        poseStack.pushPose();
        if (backFace) {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        }
        // 与固定字幕共享同一个字体像素到世界坐标的基础比例；缺少此层会放大约 40 倍。
        poseStack.scale(-TEXT_SCALE, -TEXT_SCALE, -TEXT_SCALE);
        submitScrollingSubtitleLines(window, element, poseStack, collector, opacity);
        poseStack.popPose();
    }

    private void submitScrollingSubtitleLines(TimedTextResolver.Window window, ControlConsoleElement element,
            PoseStack poseStack, SubmitNodeCollector collector, float opacity) {
        List<String> lines = new java.util.ArrayList<>(window.past());
        int center = lines.size();
        lines.add(window.current());
        lines.addAll(window.future());
        for (int i = 0; i < lines.size(); i++) {
            float distance = (i - center) + (1.0F - window.progress());
                float size = com.zhongbai233.net_music_can_play_bili.editor.core.media.SubtitleLayout
                    .scrollLineScale(distance);
                float eased = (1.0F - size) / 0.44F;
                int baseColor = "SCROLL_TRANSLATION".equals(element.contentMode())
                    ? element.translationColor() : element.color();
                int color = multiplyAlpha(lerpColor(baseColor, 0x40FFFFFF, eased), opacity);
                submitSubtitleText(poseStack, collector, Component.literal(lines.get(i)), distance * 14.0F,
                    color, element.textScale() * size, opacity, element);
        }
    }

    private void submitSubtitleText(PoseStack poseStack, SubmitNodeCollector collector, Component text,
                float y, int color, float scale, float opacity, ControlConsoleElement element) {
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
            submitTextLines(poseStack, collector, text, y / Math.max(0.01F, scale), color,
                element.backgroundColor(), opacity, element);
        poseStack.popPose();
    }

    private static int lerpColor(int first, int second, float amount) {
        int a = (int) ((first >>> 24) + ((second >>> 24) - (first >>> 24)) * amount);
        int r = (int) (((first >> 16) & 0xFF) + (((second >> 16) & 0xFF) - ((first >> 16) & 0xFF)) * amount);
        int g = (int) (((first >> 8) & 0xFF) + (((second >> 8) & 0xFF) - ((first >> 8) & 0xFF)) * amount);
        int b = (int) ((first & 0xFF) + ((second & 0xFF) - (first & 0xFF)) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int multiplyAlpha(int color, float opacity) {
        return com.zhongbai233.net_music_can_play_bili.editor.core.media.SubtitleLayout
            .multiplyAlpha(color, opacity);
    }

    private void submitSubtitleFace(String current, String translated, ControlConsoleElement element,
            PoseStack poseStack, SubmitNodeCollector collector, boolean backFace, float opacity) {
        poseStack.pushPose();
        if (backFace) {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        }
        poseStack.scale(-TEXT_SCALE, -TEXT_SCALE, -TEXT_SCALE);
        if (current != null && !current.isBlank()) {
            submitStyledText(poseStack, collector, Component.literal(current), 0.0F, element.color(),
                element.textScale(), opacity, element);
        }
        if (translated != null && !translated.isBlank()) {
            submitStyledText(poseStack, collector, Component.literal(translated), -12.0F,
                element.translationColor(), element.textScale(), opacity, element);
        }
        poseStack.popPose();
    }

    private void submitStyledText(PoseStack poseStack, SubmitNodeCollector collector, Component text,
            float y, int color, float textScale, float opacity, ControlConsoleElement element) {
        poseStack.pushPose();
        poseStack.scale(textScale, textScale, textScale);
        submitTextLines(poseStack, collector, text, y, color, element.backgroundColor(), opacity, element);
        poseStack.popPose();
    }

    private void submitTextLines(PoseStack poseStack, SubmitNodeCollector collector, Component text,
            float y, int color, int backgroundColor, float opacity, ControlConsoleElement element) {
        int splitWidth = com.zhongbai233.net_music_can_play_bili.editor.core.media.SubtitleLayout
                .splitWidth(element.maxWidth(), element.wrap());
        List<FormattedCharSequence> lines = splitWidth == Integer.MAX_VALUE
                ? List.of(text.getVisualOrderText()) : font.split(text, splitWidth);
        int lineColor = multiplyAlpha(color, opacity);
        int background = multiplyAlpha(backgroundColor, opacity);
        float lineY = y;
        for (FormattedCharSequence visual : lines) {
            int width = font.width(visual);
            float x = com.zhongbai233.net_music_can_play_bili.editor.core.media.SubtitleLayout
                    .x(element.alignment(), width);
            collector.submitText(poseStack, x, lineY, visual, false, Font.DisplayMode.NORMAL,
                    FULL_BRIGHT, lineColor, background, 0);
            lineY += font.lineHeight;
        }
    }

    @Override
    public AABB getRenderBoundingBox(ControlConsoleBlockEntity console) {
        ControlConsoleDocument document = console.document();
        BlockPos pos = console.getBlockPos();
        // Keep the BER alive through the range fade band. Without this margin,
        // render culling stops submitting the screen exactly when the exit fade
        // needs to be visible, so both video and subtitles disappear abruptly.
        double renderRangeX = document.hardRangeX() + ControlConsoleRangeGate.FADE_BAND;
        double renderRangeY = document.hardRangeY() + ControlConsoleRangeGate.FADE_BAND;
        double renderRangeZ = document.hardRangeZ() + ControlConsoleRangeGate.FADE_BAND;
        double centerX = pos.getX() + 0.5D;
        double centerY = pos.getY() + 0.5D;
        double centerZ = pos.getZ() + 0.5D;
        double minX = saturatedSubtract(centerX, renderRangeX);
        double minY = saturatedSubtract(centerY, renderRangeY);
        double minZ = saturatedSubtract(centerZ, renderRangeZ);
        double maxX = saturatedAdd(centerX, renderRangeX);
        double maxY = saturatedAdd(centerY, renderRangeY);
        double maxZ = saturatedAdd(centerZ, renderRangeZ);
        for (ControlConsoleElement element : document.elements()) {
            double radius = Math.hypot(element.height() * element.aspect() * 0.5D,
                    element.height() * 0.5D);
            double elementX = centerX + element.offsetX();
            double elementY = pos.getY() + 1.55D + element.offsetY();
            double elementZ = centerZ + element.distance();
            minX = Math.min(minX, elementX - radius);
            minY = Math.min(minY, elementY - radius);
            minZ = Math.min(minZ, elementZ - radius);
            maxX = Math.max(maxX, elementX + radius);
            maxY = Math.max(maxY, elementY + radius);
            maxZ = Math.max(maxZ, elementZ + radius);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean shouldRender(ControlConsoleBlockEntity console, Vec3 cameraPos) {
        return true;
    }

    private static double saturatedAdd(double value, double amount) {
        double result = value + amount;
        return Double.isFinite(result) ? result : Double.MAX_VALUE;
    }

    private static double saturatedSubtract(double value, double amount) {
        double result = value - amount;
        return Double.isFinite(result) ? result : -Double.MAX_VALUE;
    }

    private static BlockPos sourcePos(ControlConsoleDocument document, net.minecraft.world.level.Level level) {
        return document.hasSourceBinding() && level != null
                && document.sourceDimension().equals(level.dimension().identifier().toString())
                ? new BlockPos(document.sourceX(), document.sourceY(), document.sourceZ())
                : null;
    }

    private static SourceSnapshot sourceSnapshot(ControlConsoleDocument document,
            net.minecraft.world.level.Level level, BlockPos sourcePos) {
        if (level == null || sourcePos == null || document.sourceKind() == null) {
            return SourceSnapshot.EMPTY;
        }
        var source = level.getBlockEntity(sourcePos);
        if (document.sourceKind() == ControlConsoleDocument.SourceKind.LIVE_STREAMER
                && source instanceof LiveStreamerBlockEntity live) {
            return new SourceSnapshot(live.isPlaying(), live.isPlaying(), null, -1, -1.0F);
        }
        if (document.sourceKind() == ControlConsoleDocument.SourceKind.TURNTABLE
                && source instanceof ModernTurntableBlockEntity turntable) {
            int lyricTick = PlaybackClock.mediaTick(sourcePos);
            if (lyricTick < 0) lyricTick = turntable.getClientLyricTick();
            long visualMillis = PlaybackClock.visualMillis(sourcePos);
            float lyricVisualTick = visualMillis >= 0L ? visualMillis / 50.0F : lyricTick;
            return new SourceSnapshot(turntable.isPlaying(),
                    BiliVideoStreamResolver.selectionOrNull(turntable.getRawUrl()) != null,
                    turntable.getClientLyricRecord(), lyricTick, lyricVisualTick);
        }
        return SourceSnapshot.EMPTY;
    }

    public static void unregisterAudioForConsole(BlockPos consolePos) {
        Set<BlockPos> keys = CONSOLE_AUDIO_KEYS.remove(consolePos);
        CONSOLE_AUDIO_SOURCES.remove(consolePos);
        if (keys != null) {
            keys.forEach(ClientAudioOutputRegistry::unregisterConsoleRelay);
        }
    }

    public static void unregisterConsumer(BlockPos consolePos) {
        if (consolePos == null) {
            return;
        }
        ClientLinkRegistry.unlink(consolePos);
        releaseConsumer(consolePos);
        CONSUMERS.remove(consolePos);
    }

    public static void notifyConsoleRemoved(BlockPos consolePos) {
        if (consolePos != null) {
            ClientLinkRegistry.unlink(consolePos);
        }
        ConsumerState runtime = consolePos != null ? CONSUMERS.get(consolePos) : null;
        Minecraft minecraft = Minecraft.getInstance();
        if (runtime != null && runtime.active && minecraft.level != null && minecraft.player != null
                && runtime.level == minecraft.level) {
            beginBurstExit(consolePos, runtime);
            return;
        }
        unregisterConsumer(consolePos);
    }

    public static void tickConsumers() {
        for (BlockPos consolePos : List.copyOf(CONSUMERS.keySet())) {
            reconcileConsumer(consolePos);
        }
    }

    public static void updateConsumerFades() {
        for (Map.Entry<BlockPos, ConsumerState> entry : List.copyOf(CONSUMERS.entrySet())) {
            if (entry.getValue().fadingOut) {
                tickBurstExit(entry.getKey(), entry.getValue());
            }
        }
    }

    public static void clearConsumers() {
        List.copyOf(CONSUMERS.keySet()).forEach(ControlConsoleRenderer::deactivateConsumer);
        CONSUMERS.clear();
    }

    public static void acceptConsumerLeaseResult(
            com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConsumerLeaseResultPacket result) {
        ConsumerState runtime = CONSUMERS.get(result.pos());
        if (runtime == null) {
            return;
        }
        if (result.consumerGeneration() != runtime.consumerGeneration) {
            if (result.status() == com.zhongbai233.net_music_can_play_bili.network
                    .ControlConsoleConsumerLeaseResultPacket.Status.GRANTED && result.leaseId() != null) {
                ClientPacketDistributor.sendToServer(new com.zhongbai233.net_music_can_play_bili.network
                        .ControlConsoleConsumerLeasePacket(result.pos(),
                                com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConsumerLeasePacket.Action.RELEASE,
                                result.leaseId(), result.consumerGeneration()));
            }
            return;
        }
        LOGGER.trace("中控台消费租约响应: console={}, status={}, leasePresent={}, active={}, fadingOut={}",
            result.pos(), result.status(), result.leaseId() != null, runtime.active, runtime.fadingOut);
        if (result.status() == com.zhongbai233.net_music_can_play_bili.network
                .ControlConsoleConsumerLeaseResultPacket.Status.GRANTED) {
            if (runtime.fadingOut) {
                runtime.leaseId = result.leaseId();
                releaseConsumerLease(result.pos(), runtime);
                return;
            }
            runtime.leaseId = result.leaseId();
            runtime.leaseExpiresAtMillis = System.currentTimeMillis() + CONSUMER_LEASE_MILLIS;
            return;
        }
        runtime.leaseId = null;
        runtime.leaseExpiresAtMillis = 0L;
        if (runtime.active) {
            beginBurstExit(result.pos(), runtime);
        } else if (!runtime.fadingOut) {
            deactivateConsumer(result.pos());
        }
    }

    /**
     * 从客户端方块实体加载生命周期注册 hardRange 消费者；渲染提取再次调用仅作为幂等兜底。
     * 业务识别不得依赖 BER 可见性，否则大 hardRange 会被渲染距离静默截断。
     */
    public static void registerConsumer(ControlConsoleBlockEntity console) {
        if (console.getLevel() != null) {
            CONSUMERS.compute(console.getBlockPos().immutable(), (pos, existing) -> existing != null
                    && existing.level == console.getLevel() ? existing : new ConsumerState(console.getLevel()));
        }
    }

    private static boolean isConsumerActive(BlockPos consolePos) {
        ConsumerState state = CONSUMERS.get(consolePos);
        return state != null && (state.active || state.fadingOut);
    }

    private static float consumerExitGain(BlockPos consolePos) {
        ConsumerState state = CONSUMERS.get(consolePos);
        if (state == null) {
            return 1.0F;
        }
        long now = System.nanoTime();
        if (state.fadingOut) {
            return state.fadeBaseGain * ControlConsoleExitFade.gain(state.fadeStartedNanos, now);
        }
        float envelope = state.entering
                ? com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleEntryFade.gain(
                        state.entryStartedNanos, now)
                : 1.0F;
        return com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleConsumerGain
            .combine(state.lastRangeGain, envelope);
    }

    private static void reconcileConsumer(BlockPos consolePos) {
        ConsumerState runtime = CONSUMERS.get(consolePos);
        Minecraft minecraft = Minecraft.getInstance();
        if (runtime == null || minecraft.level == null || minecraft.player == null) {
            if (runtime != null) {
                CONSUMERS.remove(consolePos, runtime);
                releaseConsumerLease(consolePos, runtime);
                deactivateConsumer(consolePos);
            }
            return;
        }
        if (runtime.fadingOut) {
            tickBurstExit(consolePos, runtime);
            return;
        }
        if (runtime.level != minecraft.level
            || !minecraft.level.hasChunk(Math.floorDiv(consolePos.getX(), 16),
                Math.floorDiv(consolePos.getZ(), 16))
                || !(minecraft.level.getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console)) {
            if (runtime.active) {
                beginBurstExit(consolePos, runtime);
                tickBurstExit(consolePos, runtime);
            } else {
                CONSUMERS.remove(consolePos, runtime);
                releaseConsumerLease(consolePos, runtime);
                deactivateConsumer(consolePos);
            }
            return;
        }
        ControlConsoleDocument document = console.document();
        BlockPos source = sourcePos(document, minecraft.level);
        double centerX = consolePos.getX() + 0.5D;
        double centerY = consolePos.getY() + 0.5D;
        double centerZ = consolePos.getZ() + 0.5D;
        double playerX = minecraft.player.getX();
        double playerY = minecraft.player.getY();
        double playerZ = minecraft.player.getZ();
        boolean positionDiscontinuous = ControlConsoleExitPolicy.positionDiscontinuous(runtime.playerX,
            runtime.playerY, runtime.playerZ, playerX, playerY, playerZ);
        var result = com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleRangeGate.evaluate(
            runtime.active, playerX - centerX, playerY - centerY,
            playerZ - centerZ, document.hardRangeX(), document.hardRangeY(),
                document.hardRangeZ());
        if (!result.active() || source == null) {
            logRangeTransition(consolePos, runtime, !result.active() ? "OUTSIDE" : "NO_SOURCE",
                    playerX - centerX, playerY - centerY, playerZ - centerZ,
                    document, result.gain());
            releaseConsumerLease(consolePos, runtime);
            boolean rangeChanged = runtime.active && (runtime.halfX != document.hardRangeX()
                    || runtime.halfY != document.hardRangeY() || runtime.halfZ != document.hardRangeZ());
                    boolean abrupt = ControlConsoleExitPolicy.shouldFade(runtime.active, source != null, rangeChanged,
                        positionDiscontinuous, runtime.lastRangeGain);
            if (abrupt) {
                beginBurstExit(consolePos, runtime);
                tickBurstExit(consolePos, runtime);
            } else {
                deactivateConsumer(consolePos);
            }
            runtime.sourcePos = source;
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= runtime.nextLeaseRequestMillis) {
            ClientPacketDistributor.sendToServer(new com.zhongbai233.net_music_can_play_bili.network
                    .ControlConsoleConsumerLeasePacket(consolePos,
                    com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConsumerLeasePacket.Action
                            .ACQUIRE_OR_RENEW,
                    runtime.leaseId, runtime.consumerGeneration));
            runtime.nextLeaseRequestMillis = now + CONSUMER_RENEW_MILLIS;
        }
        if (runtime.leaseId == null || runtime.leaseExpiresAtMillis <= now) {
            logRangeTransition(consolePos, runtime, "WAITING_LEASE",
                    playerX - centerX, playerY - centerY, playerZ - centerZ,
                    document, result.gain());
            runtime.leaseId = null;
            runtime.leaseExpiresAtMillis = 0L;
            if (runtime.active) {
                beginBurstExit(consolePos, runtime);
                tickBurstExit(consolePos, runtime);
            } else {
                deactivateConsumer(consolePos);
            }
            return;
        }
        if (runtime.sourcePos != null && (!runtime.sourcePos.equals(source)
            || runtime.sourceKind != document.sourceKind())) {
            beginBurstExit(consolePos, runtime);
            tickBurstExit(consolePos, runtime);
            return;
        }
        boolean activating = !runtime.active;
        if (activating) {
            runtime.entering = true;
            runtime.entryStartedNanos = System.nanoTime();
        }
        runtime.active = true;
        logRangeTransition(consolePos, runtime, "ACTIVE",
            playerX - centerX, playerY - centerY, playerZ - centerZ,
            document, result.gain());
        float entryGain = runtime.entering
            ? com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleEntryFade.gain(
                runtime.entryStartedNanos, System.nanoTime())
            : 1.0F;
        if (entryGain >= 1.0F) {
            runtime.entering = false;
            runtime.entryStartedNanos = 0L;
        }
        runtime.lastRangeGain = result.gain();
        runtime.halfX = document.hardRangeX();
        runtime.halfY = document.hardRangeY();
        runtime.halfZ = document.hardRangeZ();
        runtime.playerX = playerX;
        runtime.playerY = playerY;
        runtime.playerZ = playerZ;
        runtime.sourcePos = source;
        runtime.sourceKind = document.sourceKind();
        int videoQualityCeiling = document.elements().stream()
            .filter(element -> element.enabled() && element.type() == ControlConsoleElement.Type.SCREEN)
            .mapToInt(element -> com.zhongbai233.net_music_can_play_bili.editor.core.media
                .ControlConsoleMediaSettings.videoQualityCeiling(element.channelIndex()))
            .max()
            .orElse(com.zhongbai233.net_music_can_play_bili.editor.core.media
                .ControlConsoleMediaSettings.videoQualityCeiling(0));
        if (document.sourceKind() == ControlConsoleDocument.SourceKind.LIVE_STREAMER) {
            LiveStreamerVideoClient.registerControlConsoleConsumer(source, consolePos, videoQualityCeiling);
        } else {
            ModernTurntableVideoClient.registerControlConsoleConsumer(source, consolePos, videoQualityCeiling);
            boolean healthCheckDue = now >= runtime.nextVideoHealthCheckMillis;
            if ((activating || healthCheckDue)
                    && minecraft.level.getBlockEntity(source) instanceof ModernTurntableBlockEntity turntable) {
                // 激活边沿立即恢复；稳态低频确认使首次恢复碰到 pending/decoder 竞态时仍能自愈。
                ModernTurntableVideoClient.syncFromTurntableIfPossible(turntable);
                runtime.nextVideoHealthCheckMillis = now + Math.max(100L, VIDEO_HEALTH_CHECK_MILLIS);
            }
        }
        registerAudioForConsole(consolePos, source, document.elements(), result.gain() * entryGain);
    }

    private static void logRangeTransition(BlockPos consolePos, ConsumerState runtime, String status,
            double relativeX, double relativeY, double relativeZ,
            ControlConsoleDocument document, float gain) {
        if (status.equals(runtime.rangeDiagnosticStatus)) {
            return;
        }
        runtime.rangeDiagnosticStatus = status;
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        LOGGER.debug("中控台 hardRange 识别: status={}, console={}, relative=({},{},{}), half=({},{},{}), gain={}",
                status, consolePos,
                formatRange(relativeX), formatRange(relativeY), formatRange(relativeZ),
                formatRange(document.hardRangeX()), formatRange(document.hardRangeY()),
                formatRange(document.hardRangeZ()), formatRange(gain));
    }

    private static String formatRange(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static void deactivateConsumer(BlockPos consolePos) {
        ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
        LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
        VideoBillboardPreview.detachControlConsoleConsumer(consolePos);
        unregisterAudioForConsole(consolePos);
        ConsumerState state = CONSUMERS.get(consolePos);
        if (state != null) {
            state.active = false;
            state.fadingOut = false;
            state.entering = false;
            state.entryStartedNanos = 0L;
            state.sourcePos = null;
            state.sourceKind = null;
            state.fadeStartedNanos = 0L;
            state.nextVideoHealthCheckMillis = 0L;
        }
    }

    private static void beginBurstExit(BlockPos consolePos, ConsumerState runtime) {
        if (runtime.fadingOut) {
            return;
        }
        releaseConsumerLease(consolePos, runtime);
        runtime.active = false;
        runtime.entering = false;
        runtime.entryStartedNanos = 0L;
        runtime.fadingOut = true;
        runtime.fadeStartedNanos = System.nanoTime();
        runtime.fadeBaseGain = Math.clamp(runtime.lastRangeGain, 0.0F, 1.0F);
    }

    private static void tickBurstExit(BlockPos consolePos, ConsumerState runtime) {
        float gain = runtime.fadeBaseGain * ControlConsoleExitFade.gain(runtime.fadeStartedNanos, System.nanoTime());
        updateAudioRangeGain(consolePos, gain);
        if (gain <= 0.0F) {
            deactivateConsumer(consolePos);
        }
    }

    private static void updateAudioRangeGain(BlockPos consolePos, float gain) {
        Set<BlockPos> keys = CONSOLE_AUDIO_KEYS.get(consolePos);
        if (keys != null) {
            keys.forEach(key -> ClientAudioOutputRegistry.updateRelayRangeGain(key, gain));
        }
    }

    private static void releaseConsumer(BlockPos consolePos) {
        ConsumerState runtime = CONSUMERS.get(consolePos);
        if (runtime != null) {
            releaseConsumerLease(consolePos, runtime);
        }
        deactivateConsumer(consolePos);
    }

    private static void releaseConsumerLease(BlockPos consolePos, ConsumerState runtime) {
        if (runtime.leaseId != null) {
            ClientPacketDistributor.sendToServer(new com.zhongbai233.net_music_can_play_bili.network
                    .ControlConsoleConsumerLeasePacket(consolePos,
                    com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConsumerLeasePacket.Action.RELEASE,
                    runtime.leaseId, runtime.consumerGeneration));
        }
        runtime.leaseId = null;
        runtime.leaseExpiresAtMillis = 0L;
        runtime.nextLeaseRequestMillis = 0L;
        runtime.consumerGeneration++;
    }

        private static void registerAudioForConsole(BlockPos consolePos, BlockPos sourcePos,
            List<ControlConsoleElement> elements, float rangeGain) {
        Set<BlockPos> keys = ConcurrentHashMap.newKeySet();
        BlockPos previousSource = CONSOLE_AUDIO_SOURCES.put(consolePos, sourcePos);
        if (previousSource != null && !previousSource.equals(sourcePos)) {
            unregisterAudioForConsole(consolePos);
            CONSOLE_AUDIO_SOURCES.put(consolePos, sourcePos);
        }
        Set<BlockPos> previousKeys = CONSOLE_AUDIO_KEYS.get(consolePos);
        for (ControlConsoleElement element : elements) {
            if (element.type() != ControlConsoleElement.Type.AUDIO) {
                continue;
            }
            long identity = element.elementId().getMostSignificantBits() ^ element.elementId().getLeastSignificantBits();
            BlockPos key = new BlockPos(consolePos.getX() ^ (int) (identity >>> 32), consolePos.getY(),
                    consolePos.getZ() ^ (int) identity);
            float[] worldPos = { (float) (consolePos.getX() + 0.5D + element.offsetX()),
                    (float) (consolePos.getY() + 1.55D + element.offsetY()),
                    (float) (consolePos.getZ() + 0.5D + element.distance()) };
            if (element.enabled()) {
                ClientAudioOutputRegistry.registerConsoleRelay(key, sourcePos, worldPos,
                    element.channelIndex(), element.volume(), element.autoMixJoc(), element.maxDistance());
                ClientAudioOutputRegistry.updateRelayRangeGain(key, rangeGain);
            } else {
                ClientAudioOutputRegistry.unregisterConsoleRelay(key);
            }
            keys.add(key);
        }
        if (previousKeys != null) {
            previousKeys.stream().filter(key -> !keys.contains(key))
                    .forEach(ClientAudioOutputRegistry::unregisterConsoleRelay);
        }
        if (!keys.isEmpty()) {
            CONSOLE_AUDIO_KEYS.put(consolePos, keys);
        }
    }

    public static final class State extends BlockEntityRenderState {
        private BlockPos consolePos;
        private BlockPos sourcePos;
        private ControlConsoleDocument.SourceKind sourceKind;
        private boolean sourcePlaying;
        private boolean videoExpected;
        private ControlConsoleVideoStatePolicy.State videoState = ControlConsoleVideoStatePolicy.State.IDLE;
        private String sessionId;
        private java.util.List<ControlConsoleElement> elements = java.util.List.of();
        private VideoBillboardPreview.ProjectorFrameSnapshot frame = VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        private String currentLyric = "";
        private String translatedLyric = "";
        private Int2ObjectSortedMap<String> lyrics;
        private Int2ObjectSortedMap<String> transLyrics;
        private int lyricTick = -1;
        private float lyricVisualTick = -1.0F;
        private boolean consumerActive;
        private boolean irisCompatibilityMode;
        private float exitGain = 1.0F;
    }

    private static final class ConsumerState {
        private final net.minecraft.world.level.Level level;
        private BlockPos sourcePos;
        private ControlConsoleDocument.SourceKind sourceKind;
        private boolean active;
        private boolean fadingOut;
        private boolean entering;
        private long entryStartedNanos;
        private long fadeStartedNanos;
        private float fadeBaseGain = 1.0F;
        private float lastRangeGain = 1.0F;
        private double halfX;
        private double halfY;
        private double halfZ;
        private double playerX = Double.NaN;
        private double playerY = Double.NaN;
        private double playerZ = Double.NaN;
        private java.util.UUID leaseId;
        private long leaseExpiresAtMillis;
        private long nextLeaseRequestMillis;
        private long nextVideoHealthCheckMillis;
        private long consumerGeneration;
        private String rangeDiagnosticStatus = "";

        private ConsumerState(net.minecraft.world.level.Level level) {
            this.level = level;
        }
    }

    private record SourceSnapshot(boolean playing, boolean videoExpected,
            com.github.tartaricacid.netmusic.api.lyric.LyricRecord lyric, int lyricTick, float lyricVisualTick) {
        private static final SourceSnapshot EMPTY = new SourceSnapshot(false, false, null, -1, -1.0F);
    }

    private static String lineAt(it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap<String> lines, int tick) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        int key = TimedTextResolver.keyAt(lines.keySet().toIntArray(), tick);
        return key == Integer.MIN_VALUE ? "" : lines.getOrDefault(key, "");
    }

}