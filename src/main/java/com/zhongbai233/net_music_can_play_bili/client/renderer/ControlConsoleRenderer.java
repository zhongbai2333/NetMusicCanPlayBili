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
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.sync.LiveRoomMetadataRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientAiSubtitleRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElementPosition;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.LiveSubtitleMetadata;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.AiSubtitleText;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.TimedTextResolver;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleVideoStatePolicy;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleExitFade;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleExitPolicy;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleRangeGate;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackApproachPredictor;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** 中控台运行时渲染器：屏幕元素复用绑定源的同一视频 session。 */
public final class ControlConsoleRenderer
        implements BlockEntityRenderer<ControlConsoleBlockEntity, ControlConsoleRenderer.State> {
    private static final Logger LOGGER = LogUtils.getLogger();
        private static final float TEXT_SCALE = com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media
            .SubtitleLayout.WORLD_TEXT_SCALE;
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final Map<BlockPos, Set<BlockPos>> CONSOLE_AUDIO_KEYS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, BlockPos> CONSOLE_AUDIO_SOURCES = new ConcurrentHashMap<>();
    private static final Map<BlockPos, ConsumerState> CONSUMERS = new ConcurrentHashMap<>();
    private static final long CONSUMER_LEASE_MILLIS = 3_000L;
    private static final long CONSUMER_RENEW_MILLIS = 1_000L;
    private static final long VIDEO_HEALTH_CHECK_MILLIS =
            ClientDisplayProperties.controlConsoleVideoHealthCheckMillis();
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
        if (state.sourcePos != null) {
            if (state.sourceKind == ControlConsoleDocument.SourceKind.TURNTABLE) {
                ClientLinkRegistry.linkSubtitleProjector(state.consolePos, state.sourcePos);
            } else {
                ClientLinkRegistry.link(state.consolePos, state.sourcePos);
            }
            if (state.sourceKind == ControlConsoleDocument.SourceKind.TURNTABLE) {
                ClientAudioOutputRegistry.bindConsoleRoute(state.consolePos, state.sourcePos);
            } else {
                ClientAudioOutputRegistry.unbindConsoleRoute(state.consolePos);
            }
        } else {
            ClientLinkRegistry.unlink(state.consolePos);
            ClientAudioOutputRegistry.unbindConsoleRoute(state.consolePos);
        }
        registerConsumer(console);
        reconcileConsumer(state.consolePos);
        state.consumerActive = isConsumerActive(state.consolePos);
        state.hideVideoForPrivacy = com.zhongbai233.net_music_can_play_bili.client.renderer.video
            .VideoSurfacePrivacyPolicy.hideVideo(HolographicGlassesClient.shouldHideProjectorVideos(),
                com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoSurfacePrivacyPolicy
                    .SurfaceKind.CONTROL_CONSOLE);
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
        state.playbackSessionId = video != null
                ? PlaybackSessionId.parse(video.sessionId()) : Optional.empty();
        ConsumerState videoRuntime = CONSUMERS.get(state.consolePos);
        boolean realVideoReady = state.videoState == ControlConsoleVideoStatePolicy.State.ACTIVE
                && state.frame.hasFrame();
        float videoEntryGain = videoRuntime != null
                ? videoRuntime.videoEnvelope.gain(realVideoReady, System.nanoTime()) : 1.0F;
        state.videoGain = realVideoReady ? videoEntryGain : 1.0F;

        state.currentLyric = "";
        state.translatedLyric = "";
        state.lyrics = null;
        state.transLyrics = null;
        state.lyricTick = -1;
        state.lyricVisualTick = -1.0F;
        state.liveMetadata = sourceSnapshot.liveMetadata();
        state.aiLyric = sourceSnapshot.aiLyric();
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
        for (ControlConsoleElement element : state.elements) {
            if (!element.enabled()) {
                continue;
            }
            float halfHeight = element.height() * 0.5F;
            float halfWidth = halfHeight * element.aspect();
            poseStack.pushPose();
            poseStack.translate(0.5D, 1.55D, 0.5D);
            poseStack.mulPose(element.editorTransform().matrix());
            Matrix4f pose = new Matrix4f(poseStack.last().pose());
            if (element.type() == ControlConsoleElement.Type.SCREEN) {
                boolean screenPotentiallyVisible = VideoBillboardPreview
                        .isControlConsoleScreenPotentiallyVisible(state.consolePos,
                                element.editorTransform().matrix(), halfWidth, halfHeight);
                if (!state.hideVideoForPrivacy
                        && screenPotentiallyVisible
                        && (state.videoState == ControlConsoleVideoStatePolicy.State.ACTIVE
                            || state.videoState == ControlConsoleVideoStatePolicy.State.BUFFERING)
                        && state.playbackSessionId.isPresent()) {
                    // A console-level BER submission can be caused by subtitles, audio elements,
                    // or an oversized owner bound. Only an enabled screen is visual video demand.
                    VideoBillboardPreview.markProjectorSubmittedByBer(
                            state.playbackSessionId.orElseThrow(), state.consolePos);
                }
                if (!state.hideVideoForPrivacy
                        && state.videoState == ControlConsoleVideoStatePolicy.State.ACTIVE
                        && state.playbackSessionId.isPresent()) {
                    VideoBillboardPreview.captureProjectorImmediatePose(
                            state.playbackSessionId.orElseThrow(), state.consolePos, pose, halfHeight,
                            state.exitGain * state.videoGain, element.brightness());
                }
                if (state.hideVideoForPrivacy && state.frame.hasFrame()
                        && state.frame.width() > 0 && state.frame.height() > 0) {
                    VideoBillboardPreview.submitProjectorPrivacyOverlayOnPose(
                            collector, poseStack, halfWidth, halfHeight);
                } else if (!state.hideVideoForPrivacy) {
                    VideoBillboardPreview.submitProjectorFrameOnPose(collector, poseStack, state.frame, halfWidth,
                        halfHeight, VideoBillboardPreview.cameraRelativeBackOffset(pose,
                            state.frame.rgbaDepthOffset()), state.exitGain * state.videoGain, element.brightness());
                }
            } else if (element.type() == ControlConsoleElement.Type.SUBTITLE) {
                submitSubtitle(state, element, poseStack, collector);
            }
            poseStack.popPose();
        }
    }

    private void submitSubtitle(State state, ControlConsoleElement element, PoseStack poseStack,
            SubmitNodeCollector collector) {
        String mode = element.contentMode();
        if ("AI_SUBTITLE".equals(mode)) {
            submitAiSubtitle(state, element, poseStack, collector);
            return;
        }
        if (LiveSubtitleMetadata.isLiveMode(mode)) {
            String metadataText = LiveSubtitleMetadata.text(mode, state.liveMetadata);
            if (!metadataText.isBlank()) {
                submitSubtitleFace(metadataText, "", element, poseStack, collector, false, state.exitGain);
                submitSubtitleFace(metadataText, "", element, poseStack, collector, true, state.exitGain);
            }
            return;
        }
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

    private void submitAiSubtitle(State state, ControlConsoleElement element, PoseStack poseStack,
            SubmitNodeCollector collector) {
        AiSubtitleText.Lines lines = AiSubtitleText.resolve(
                state.aiLyric != null ? tick -> lineAt(state.aiLyric.getLyrics(), tick) : null,
                state.aiLyric != null ? tick -> lineAt(state.aiLyric.getTransLyrics(), tick) : null,
                state.currentLyric, state.translatedLyric, state.lyricTick,
                element.showTranslation(), element.text());
        String current = lines.primary();
        String translated = lines.translation();
        if (current.isBlank() && translated.isBlank()) {
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
                float size = com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.SubtitleLayout
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
        return com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.SubtitleLayout
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
        int splitWidth = com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.SubtitleLayout
                .splitWidth(element.maxWidth(), element.wrap());
        List<FormattedCharSequence> lines = splitWidth == Integer.MAX_VALUE
                ? List.of(text.getVisualOrderText()) : font.split(text, splitWidth);
        int lineColor = multiplyAlpha(color, opacity);
        int background = multiplyAlpha(backgroundColor, opacity);
        float lineY = y;
        for (FormattedCharSequence visual : lines) {
            int width = font.width(visual);
            float x = com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.SubtitleLayout
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
        double centerX = pos.getX() + 0.5D;
        double centerZ = pos.getZ() + 0.5D;
        // Consumer range is maintained by tickConsumers(), independently from BER culling.
        // The render bound must describe only renderable geometry; otherwise a 64-block
        // audience range makes an off-camera console look visible to the video decoder.
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = pos.getX() + 1.0D;
        double maxY = pos.getY() + 1.0D;
        double maxZ = pos.getZ() + 1.0D;
        for (ControlConsoleElement element : document.elements()) {
            if (!element.enabled()) {
                continue;
            }
            Matrix4f transform = element.editorTransform().matrix();
            if (element.type() == ControlConsoleElement.Type.AUDIO) {
                org.joml.Vector3d worldPosition = ControlConsoleElementPosition.worldPosition(
                        pos.getX(), pos.getY(), pos.getZ(), element);
                double worldX = worldPosition.x, worldY = worldPosition.y, worldZ = worldPosition.z;
                minX = Math.min(minX, worldX);
                minY = Math.min(minY, worldY);
                minZ = Math.min(minZ, worldZ);
                maxX = Math.max(maxX, worldX);
                maxY = Math.max(maxY, worldY);
                maxZ = Math.max(maxZ, worldZ);
                continue;
            }
            float halfHeight = element.height() * 0.5F;
            float halfWidth = halfHeight * element.aspect();
            for (int ySign : new int[] { -1, 1 }) {
                for (int xSign : new int[] { -1, 1 }) {
                    org.joml.Vector3f corner = transform.transformPosition(
                            new org.joml.Vector3f(xSign * halfWidth, ySign * halfHeight, 0.0F));
                    double worldX = centerX + corner.x;
                    double worldY = pos.getY() + 1.55D + corner.y;
                    double worldZ = centerZ + corner.z;
                    minX = Math.min(minX, worldX);
                    minY = Math.min(minY, worldY);
                    minZ = Math.min(minZ, worldZ);
                    maxX = Math.max(maxX, worldX);
                    maxY = Math.max(maxY, worldY);
                    maxZ = Math.max(maxZ, worldZ);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean shouldRender(ControlConsoleBlockEntity console, Vec3 cameraPos) {
        return true;
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
            var cached = LiveRoomMetadataRegistry.snapshot(
                    new LiveRoomMetadataRegistry.SourceKey(sourcePos.getX(), sourcePos.getY(), sourcePos.getZ()),
                    live.getRoomId()).orElse(null);
            LiveSubtitleMetadata.Metadata metadata = LiveSubtitleMetadata.resolve(live.getRoomId(),
                    cached != null ? cached.title() : "", cached != null ? cached.parentAreaName() : "",
                    cached != null ? cached.areaName() : "", cached != null ? cached.liveStatus() : -1,
                    live.isPlaying(), live.isWaitingForLive());
            return new SourceSnapshot(live.isPlaying(), live.isPlaying(), null, null, -1, -1.0F, metadata);
        }
        if (document.sourceKind() == ControlConsoleDocument.SourceKind.TURNTABLE
                && source instanceof ModernTurntableBlockEntity turntable) {
            int lyricTick = PlaybackClock.mediaTick(sourcePos);
            if (lyricTick < 0) lyricTick = turntable.getClientLyricTick();
            long visualMillis = PlaybackClock.visualMillis(sourcePos);
            float lyricVisualTick = visualMillis >= 0L ? visualMillis / 50.0F : lyricTick;
            PlaybackSessionId sessionId = turntable.getPlaybackSyncMetadata()
                    .playbackSessionId().orElse(null);
            var aiSnapshot = ClientAiSubtitleRegistry.snapshot(sourcePos, sessionId);
            return new SourceSnapshot(turntable.isPlaying(),
                    BiliVideoStreamResolver.selectionOrNull(turntable.getRawUrl()) != null,
                    turntable.getClientLyricRecord(), aiSnapshot.ready() ? aiSnapshot.lyricRecord() : null,
                    lyricTick, lyricVisualTick, LiveSubtitleMetadata.EMPTY);
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

    public static void notifyConsoleRemoved(BlockPos consolePos, boolean bindingDestroyed) {
        if (consolePos != null) {
            if (bindingDestroyed) {
                ClientLinkRegistry.unlink(consolePos);
                // Active relays keep suppression during their short exit fade. Once they are
                // gone, an actually removed console no longer owns the source route.
                ClientAudioOutputRegistry.unbindConsoleRoute(consolePos);
            }
        }
        ConsumerState runtime = consolePos != null ? CONSUMERS.get(consolePos) : null;
        Minecraft minecraft = Minecraft.getInstance();
        if (runtime != null && runtime.active && minecraft.level != null && minecraft.player != null
                && runtime.level == minecraft.level) {
            beginBurstExit(consolePos, runtime);
            return;
        }
        if (bindingDestroyed) {
            unregisterConsumer(consolePos);
        } else {
            // Chunk unload is not a logical unbind. Keep a dormant state so that a later
            // loaded chunk can either restore the binding or prove that the block was removed
            // while it was outside this client's view.
            if (runtime != null) {
                releaseConsumerLease(consolePos, runtime);
            }
            deactivateConsumer(consolePos);
        }
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
            BlockPos consolePos = console.getBlockPos().immutable();
            CONSUMERS.compute(consolePos, (pos, existing) -> existing != null
                    && existing.level == console.getLevel() ? existing : new ConsumerState(console.getLevel()));
            reconcileRouteBinding(consolePos, console.document(), console.getLevel());
        }
    }

    private static void reconcileRouteBinding(BlockPos consolePos, ControlConsoleDocument document,
            net.minecraft.world.level.Level level) {
        BlockPos source = sourcePos(document, level);
        if (source != null) {
            if (document.sourceKind() == ControlConsoleDocument.SourceKind.TURNTABLE) {
                ClientLinkRegistry.linkSubtitleProjector(consolePos, source);
            } else {
                ClientLinkRegistry.link(consolePos, source);
            }
        } else {
            ClientLinkRegistry.unlink(consolePos);
        }
        if (source != null && ControlConsoleAudioRoutePolicy.takesOverMainOutput(document)) {
            ClientAudioOutputRegistry.bindConsoleRoute(consolePos, source);
        } else {
            ClientAudioOutputRegistry.unbindConsoleRoute(consolePos);
        }
    }

    private static boolean isConsumerActive(BlockPos consolePos) {
        ConsumerState state = CONSUMERS.get(consolePos);
        return state != null && (state.active || state.fadingOut);
    }

    /** Read-only client diagnostic used by commands and physical-client system tests. */
    public static ConsumerLeaseDiagnostic consumerLeaseDiagnostic(BlockPos consolePos) {
        ConsumerState state = CONSUMERS.get(consolePos);
        return state == null ? ConsumerLeaseDiagnostic.ABSENT
                : new ConsumerLeaseDiagnostic(true, state.active, state.fadingOut,
                        state.leaseId != null && state.leaseExpiresAtMillis > System.currentTimeMillis(),
                        state.consumerGeneration);
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
                ? com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleEntryFade.gain(
                        state.entryStartedNanos, now)
                : 1.0F;
        return com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleConsumerGain
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
        if (runtime.level != minecraft.level) {
            CONSUMERS.remove(consolePos, runtime);
            releaseConsumerLease(consolePos, runtime);
            deactivateConsumer(consolePos);
            return;
        }
        boolean consoleChunkLoaded = minecraft.level.hasChunk(Math.floorDiv(consolePos.getX(), 16),
                Math.floorDiv(consolePos.getZ(), 16));
        if (!consoleChunkLoaded) {
            if (runtime.active) {
                beginBurstExit(consolePos, runtime);
                tickBurstExit(consolePos, runtime);
            } else {
                releaseConsumerLease(consolePos, runtime);
                deactivateConsumer(consolePos);
            }
            return;
        }
        if (!(minecraft.level.getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console)) {
            // The chunk is authoritative now: a console that disappeared while unloaded is an
            // actual removal, not another range exit. Release the persistent route owner.
            ClientLinkRegistry.unlink(consolePos);
            ClientAudioOutputRegistry.unbindConsoleRoute(consolePos);
            CONSUMERS.remove(consolePos, runtime);
            releaseConsumerLease(consolePos, runtime);
            deactivateConsumer(consolePos);
            return;
        }
        ControlConsoleDocument document = console.document();
        reconcileRouteBinding(consolePos, document, minecraft.level);
        boolean rangeChanged = runtime.active && (runtime.halfX != document.hardRangeX()
                || runtime.halfY != document.hardRangeY() || runtime.halfZ != document.hardRangeZ());
        runtime.halfX = document.hardRangeX();
        runtime.halfY = document.hardRangeY();
        runtime.halfZ = document.hardRangeZ();
        BlockPos source = sourcePos(document, minecraft.level);
        double centerX = consolePos.getX() + 0.5D;
        double centerY = consolePos.getY() + 0.5D;
        double centerZ = consolePos.getZ() + 0.5D;
        double playerX = minecraft.player.getX();
        double playerY = minecraft.player.getY();
        double playerZ = minecraft.player.getZ();
        boolean positionDiscontinuous = ControlConsoleExitPolicy.positionDiscontinuous(runtime.playerX,
            runtime.playerY, runtime.playerZ, playerX, playerY, playerZ);
        var result = ControlConsoleRangeGate.evaluate(
            runtime.active, playerX - centerX, playerY - centerY,
            playerZ - centerZ, document.hardRangeX(), document.hardRangeY(),
                document.hardRangeZ());
        Vec3 movement = minecraft.player.getDeltaMovement();
        boolean approachingHardRange = !result.active() && source != null
                && PlaybackApproachPredictor.willEnterAabb(playerX, playerY, playerZ,
                    movement.x, movement.y, movement.z, centerX, centerY, centerZ,
                    document.hardRangeX(), document.hardRangeY(), document.hardRangeZ());
        if (approachingHardRange) {
            enterPredictivePrewarm(consolePos, runtime, source, document, minecraft);
            logRangeTransition(consolePos, runtime, "PREWARM", playerX - centerX, playerY - centerY,
                    playerZ - centerZ, document, 0.0F);
            return;
        }
        if (!result.active() || source == null) {
            logRangeTransition(consolePos, runtime, !result.active() ? "OUTSIDE" : "NO_SOURCE",
                    playerX - centerX, playerY - centerY, playerZ - centerZ,
                    document, result.gain());
            releaseConsumerLease(consolePos, runtime);
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
            }
            // A first acquisition is asynchronous. Keep the loaded consumer and its generation
            // alive until GRANTED/REJECTED arrives; removing it here turns a valid GRANTED into a
            // stale response that immediately releases the server lease.
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
        runtime.prewarming = false;
        logRangeTransition(consolePos, runtime, "ACTIVE",
            playerX - centerX, playerY - centerY, playerZ - centerZ,
            document, result.gain());
        float entryGain = runtime.entering
            ? com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleEntryFade.gain(
                runtime.entryStartedNanos, System.nanoTime())
            : 1.0F;
        if (entryGain >= 1.0F) {
            runtime.entering = false;
            runtime.entryStartedNanos = 0L;
        }
        runtime.lastRangeGain = result.gain();
        runtime.playerX = playerX;
        runtime.playerY = playerY;
        runtime.playerZ = playerZ;
        runtime.sourcePos = source;
        runtime.sourceKind = document.sourceKind();
        boolean hasVideoSurface = ControlConsoleVideoSurfacePolicy.hasEnabledScreen(document.elements());
        int videoQualityCeiling = document.elements().stream()
            .filter(element -> element.enabled() && element.type() == ControlConsoleElement.Type.SCREEN)
            .mapToInt(element -> com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media
                .ControlConsoleMediaSettings.videoQualityCeiling(element.channelIndex()))
            .max()
            .orElse(com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media
                .ControlConsoleMediaSettings.videoQualityCeiling(0));
        if (document.sourceKind() == ControlConsoleDocument.SourceKind.LIVE_STREAMER) {
            ClientAiSubtitleRegistry.release(consolePos);
            ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
            if (hasVideoSurface) {
                LiveStreamerVideoClient.registerControlConsoleConsumer(source, consolePos, videoQualityCeiling);
            } else {
                LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
                VideoBillboardPreview.detachControlConsoleConsumer(consolePos);
            }
        } else {
            LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
            if (hasVideoSurface) {
                ModernTurntableVideoClient.registerControlConsoleConsumer(source, consolePos, videoQualityCeiling);
                boolean healthCheckDue = now >= runtime.nextVideoHealthCheckMillis;
                if ((activating || healthCheckDue)
                        && minecraft.level.getBlockEntity(source) instanceof ModernTurntableBlockEntity turntable) {
                    // 激活边沿立即恢复；稳态低频确认使首次恢复碰到 pending/decoder 竞态时仍能自愈。
                    ModernTurntableVideoClient.syncFromTurntableIfPossible(turntable);
                    runtime.nextVideoHealthCheckMillis = now + VIDEO_HEALTH_CHECK_MILLIS;
                }
            } else {
                ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
                VideoBillboardPreview.detachControlConsoleConsumer(consolePos);
            }
            reconcileAiSubtitleConsumer(consolePos, source, document, minecraft);
        }
        registerAudioForConsole(consolePos, source, document.elements(), result.gain() * entryGain, false);
    }


    private static void enterPredictivePrewarm(BlockPos consolePos, ConsumerState runtime, BlockPos source,
            ControlConsoleDocument document, Minecraft minecraft) {
        if (runtime.active || runtime.fadingOut
                || runtime.prewarming && (runtime.sourcePos == null || !runtime.sourcePos.equals(source)
                    || runtime.sourceKind != document.sourceKind())) {
            deactivateConsumer(consolePos);
        }
        if (runtime.leaseId != null) {
            releaseConsumerLease(consolePos, runtime);
        }
        runtime.active = false;
        runtime.fadingOut = false;
        runtime.entering = false;
        runtime.entryStartedNanos = 0L;
        runtime.lastRangeGain = 0.0F;
        runtime.playerX = minecraft.player.getX();
        runtime.playerY = minecraft.player.getY();
        runtime.playerZ = minecraft.player.getZ();
        runtime.sourcePos = source;
        runtime.sourceKind = document.sourceKind();
        runtime.prewarming = true;
        long now = System.currentTimeMillis();
        boolean hasVideoSurface = ControlConsoleVideoSurfacePolicy.hasEnabledScreen(document.elements());
        boolean videoPrewarm = hasVideoSurface
                && isVideoPrewarmPredicted(consolePos, document, minecraft);
        int videoQualityCeiling = document.elements().stream()
                .filter(element -> element.enabled() && element.type() == ControlConsoleElement.Type.SCREEN)
                .mapToInt(element -> com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media
                    .ControlConsoleMediaSettings.videoQualityCeiling(element.channelIndex()))
                .max().orElse(com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media
                    .ControlConsoleMediaSettings.videoQualityCeiling(0));
        if (document.sourceKind() == ControlConsoleDocument.SourceKind.LIVE_STREAMER) {
            ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
            if (videoPrewarm) {
                LiveStreamerVideoClient.registerControlConsoleConsumer(source, consolePos, videoQualityCeiling);
            } else {
                LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
            }
        } else {
            LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
            if (videoPrewarm) {
                ModernTurntableVideoClient.registerControlConsoleConsumer(source, consolePos, videoQualityCeiling);
                if (now >= runtime.nextVideoHealthCheckMillis
                        && minecraft.level.getBlockEntity(source) instanceof ModernTurntableBlockEntity turntable) {
                    ModernTurntableVideoClient.syncFromTurntableIfPossible(turntable);
                    runtime.nextVideoHealthCheckMillis = now + VIDEO_HEALTH_CHECK_MILLIS;
                }
            } else {
                ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
            }
        }
        registerAudioForConsole(consolePos, source, document.elements(), 0.0F, true);
    }
    private static void reconcileAiSubtitleConsumer(BlockPos consolePos, BlockPos source,
            ControlConsoleDocument document, Minecraft minecraft) {
        boolean requested = document.elements().stream().anyMatch(element -> element.enabled()
                && element.type() == ControlConsoleElement.Type.SUBTITLE
                && "AI_SUBTITLE".equals(element.contentMode()));
        if (!requested || minecraft.level == null
                || !(minecraft.level.getBlockEntity(source) instanceof ModernTurntableBlockEntity turntable)
                || !turntable.isPlaying()
                || BiliVideoStreamResolver.selectionOrNull(turntable.getRawUrl()) == null) {
            ClientAiSubtitleRegistry.release(consolePos);
            return;
        }
        PlaybackSessionId sessionId = turntable.getPlaybackSyncMetadata()
                .playbackSessionId().orElse(null);
        ClientAiSubtitleRegistry.acquire(consolePos, source, sessionId, turntable.getRawUrl(),
                turntable.getSongName());
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
        ClientAiSubtitleRegistry.release(consolePos);
        ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
        LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
        VideoBillboardPreview.detachControlConsoleConsumer(consolePos);
        unregisterAudioForConsole(consolePos);
        ConsumerState state = CONSUMERS.get(consolePos);
        if (state != null) {
            state.active = false;
            state.fadingOut = false;
            state.entering = false;
            state.prewarming = false;
            state.entryStartedNanos = 0L;
            state.sourcePos = null;
            state.sourceKind = null;
            state.fadeStartedNanos = 0L;
            state.nextVideoHealthCheckMillis = 0L;
            state.videoEnvelope.reset();
        }
    }

    private static void beginBurstExit(BlockPos consolePos, ConsumerState runtime) {
        if (runtime.fadingOut) {
            return;
        }
        releaseConsumerLease(consolePos, runtime);
        // Fade may consume only already-owned short text state; it must not keep an AI HTTP request alive.
        ClientAiSubtitleRegistry.release(consolePos);
        runtime.active = false;
        runtime.prewarming = false;
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
            List<ControlConsoleElement> elements, float rangeGain, boolean preparationDemand) {
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
            if (element.enabled()) {
                org.joml.Vector3d position = ControlConsoleElementPosition.worldPosition(
                        consolePos.getX(), consolePos.getY(), consolePos.getZ(), element);
                float[] worldPos = { (float) position.x, (float) position.y, (float) position.z };
                ClientAudioOutputRegistry.registerConsoleRelay(key, sourcePos, worldPos,
                    element.channelIndex(), element.volume(), element.autoMixJoc(), element.maxDistance());
                ClientAudioOutputRegistry.updateRelayRangeGain(key, rangeGain);
                ClientAudioOutputRegistry.updateRelayPreparationDemand(key, preparationDemand);
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
        private Optional<PlaybackSessionId> playbackSessionId = Optional.empty();
        private java.util.List<ControlConsoleElement> elements = java.util.List.of();
        private VideoBillboardPreview.ProjectorFrameSnapshot frame = VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        private String currentLyric = "";
        private String translatedLyric = "";
        private Int2ObjectSortedMap<String> lyrics;
        private Int2ObjectSortedMap<String> transLyrics;
        private com.github.tartaricacid.netmusic.api.lyric.LyricRecord aiLyric;
        private int lyricTick = -1;
        private float lyricVisualTick = -1.0F;
        private LiveSubtitleMetadata.Metadata liveMetadata = LiveSubtitleMetadata.EMPTY;
        private boolean consumerActive;
        private boolean hideVideoForPrivacy;
        private boolean irisCompatibilityMode;
        private float exitGain = 1.0F;
        private float videoGain = 1.0F;
    }

    private static final class ConsumerState {
        private final net.minecraft.world.level.Level level;
        private BlockPos sourcePos;
        private ControlConsoleDocument.SourceKind sourceKind;
        private boolean active;
        private boolean fadingOut;
        private boolean prewarming;
        private boolean entering;
        private long entryStartedNanos;
        private long fadeStartedNanos;
        private float fadeBaseGain = 1.0F;
        private float lastRangeGain = 1.0F;
        private double halfX;
        private final com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope
                videoEnvelope = new com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope();
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


    public static boolean isPredictivePrewarmActive(BlockPos consolePos) {
        ConsumerState state = consolePos != null ? CONSUMERS.get(consolePos) : null;
        if (state == null || !state.prewarming
                || !(state.level.getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console)) {
            return false;
        }
        return isVideoPrewarmPredicted(consolePos, console.document(), Minecraft.getInstance());
    }

    private static boolean isVideoPrewarmPredicted(BlockPos consolePos, ControlConsoleDocument document,
            Minecraft minecraft) {
        if (consolePos == null || document == null || minecraft == null || minecraft.player == null) {
            return false;
        }
        for (ControlConsoleElement element : document.elements()) {
            if (!element.enabled() || element.type() != ControlConsoleElement.Type.SCREEN) {
                continue;
            }
            float halfHeight = element.height() * 0.5F;
            float halfWidth = halfHeight * element.aspect();
            Matrix4f transform = element.editorTransform().matrix();
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (int ySign : new int[] { -1, 1 }) {
                for (int xSign : new int[] { -1, 1 }) {
                    org.joml.Vector3f corner = transform.transformPosition(
                            new org.joml.Vector3f(xSign * halfWidth, ySign * halfHeight, 0.0F));
                    double x = consolePos.getX() + 0.5D + corner.x;
                    double y = consolePos.getY() + 1.55D + corner.y;
                    double z = consolePos.getZ() + 0.5D + corner.z;
                    minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
                }
            }
            if (VideoBillboardPreview.isScreenAabbPredictedVisible(
                    new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.05D))) {
                return true;
            }
        }
        return false;
    }

    public record ConsumerLeaseDiagnostic(boolean registered, boolean active, boolean fadingOut,
            boolean leasePresent, long generation) {
        private static final ConsumerLeaseDiagnostic ABSENT = new ConsumerLeaseDiagnostic(
                false, false, false, false, -1L);
    }

    public static java.util.List<RangeDebugSnapshot> rangeDebugSnapshots() {
        return CONSUMERS.entrySet().stream().map(entry -> {
            ConsumerState state = entry.getValue();
            return new RangeDebugSnapshot(entry.getKey(), state.sourcePos, state.active, state.fadingOut,
                    state.halfX, state.halfY, state.halfZ, state.lastRangeGain);
        }).filter(snapshot -> snapshot.radiusX() > 0.0D && snapshot.radiusY() > 0.0D
                && snapshot.radiusZ() > 0.0D).toList();
    }

    /** Configured virtual audio-element spheres; their effective area is clipped by the console hard range. */
    public static java.util.List<ElementRangeDebugSnapshot> elementRangeDebugSnapshots() {
        java.util.List<ElementRangeDebugSnapshot> snapshots = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, ConsumerState> entry : CONSUMERS.entrySet()) {
            ConsumerState state = entry.getValue();
            if (!(state.level.getBlockEntity(entry.getKey()) instanceof ControlConsoleBlockEntity console)) {
                continue;
            }
            for (ControlConsoleElement element : console.document().elements()) {
                if (!element.enabled() || element.type() != ControlConsoleElement.Type.AUDIO) {
                    continue;
                }
                org.joml.Vector3d position = ControlConsoleElementPosition.worldPosition(
                        entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ(), element);
                snapshots.add(new ElementRangeDebugSnapshot(entry.getKey(), element.elementId(),
                        new Vec3(position.x, position.y, position.z),
                        element.maxDistance(), element.volume(), state.active, state.fadingOut,
                        state.lastRangeGain));
            }
        }
        return java.util.List.copyOf(snapshots);
    }

    /** Enabled console video screen quads for the independent video debug overlay. */
    public static java.util.List<VideoElementRangeDebugSnapshot> videoElementRangeDebugSnapshots() {
        java.util.List<VideoElementRangeDebugSnapshot> snapshots = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, ConsumerState> entry : CONSUMERS.entrySet()) {
            ConsumerState state = entry.getValue();
            if (!(state.level.getBlockEntity(entry.getKey()) instanceof ControlConsoleBlockEntity console)) {
                continue;
            }
            for (ControlConsoleElement element : console.document().elements()) {
                if (!element.enabled() || element.type() != ControlConsoleElement.Type.SCREEN) {
                    continue;
                }
                float halfHeight = element.height() * 0.5F;
                float halfWidth = halfHeight * element.aspect();
                Matrix4f transform = element.editorTransform().matrix();
                java.util.List<Vec3> corners = new java.util.ArrayList<>(4);
                int[][] signs = { { -1, 1 }, { -1, -1 }, { 1, -1 }, { 1, 1 } };
                for (int[] sign : signs) {
                    org.joml.Vector3f corner = transform.transformPosition(
                            new org.joml.Vector3f(sign[0] * halfWidth, sign[1] * halfHeight, 0.0F));
                    corners.add(new Vec3(entry.getKey().getX() + 0.5D + corner.x,
                            entry.getKey().getY() + 1.55D + corner.y,
                            entry.getKey().getZ() + 0.5D + corner.z));
                }
                snapshots.add(new VideoElementRangeDebugSnapshot(entry.getKey(), element.elementId(), corners,
                        state.active, state.prewarming && isVideoPrewarmPredicted(
                                entry.getKey(), console.document(), Minecraft.getInstance())));
            }
        }
        return java.util.List.copyOf(snapshots);
    }

    public record VideoElementRangeDebugSnapshot(BlockPos consolePos, java.util.UUID elementId,
            java.util.List<Vec3> corners, boolean consoleActive, boolean predictedPrewarm) {
        public VideoElementRangeDebugSnapshot { corners = java.util.List.copyOf(corners); }
    }

    public record ElementRangeDebugSnapshot(BlockPos consolePos, java.util.UUID elementId, Vec3 center,
            float configuredDistance, float volume, boolean consoleActive, boolean consoleFadingOut,
            float hardRangeGain) {
    }

    public record RangeDebugSnapshot(BlockPos consolePos, BlockPos sourcePos, boolean active,
            boolean fadingOut, double radiusX, double radiusY, double radiusZ, float gain) {
    }

    private record SourceSnapshot(boolean playing, boolean videoExpected,
            com.github.tartaricacid.netmusic.api.lyric.LyricRecord lyric,
            com.github.tartaricacid.netmusic.api.lyric.LyricRecord aiLyric,
            int lyricTick, float lyricVisualTick,
            LiveSubtitleMetadata.Metadata liveMetadata) {
        private static final SourceSnapshot EMPTY = new SourceSnapshot(false, false, null, null, -1, -1.0F,
                LiveSubtitleMetadata.EMPTY);
    }

    private static String lineAt(it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap<String> lines, int tick) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        int key = TimedTextResolver.keyAt(lines.keySet().toIntArray(), tick);
        return key == Integer.MIN_VALUE ? "" : lines.getOrDefault(key, "");
    }

}
