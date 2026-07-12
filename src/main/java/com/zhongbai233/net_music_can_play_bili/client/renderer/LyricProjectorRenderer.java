package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.event.ConfigEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.block.LyricProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSubtitleLyricService;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntableTimeline;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 歌词投影仪渲染器
 */
public class LyricProjectorRenderer
        implements BlockEntityRenderer<LyricProjectorBlockEntity, LyricProjectorRenderer.State> {
    private static final float TEXT_SCALE = 0.025F;
    private static final float TRANSLATED_LINE_OFFSET = 12.0F;
    private static final long SCROLL_DURATION_MS = 500;
    private static final long SCROLL_MIN_DURATION_MS = Long.getLong("ncpb.lyric.scroll.min_duration_ms", 120L);
    private static final long SCROLL_FAST_GAP_MS = Long.getLong("ncpb.lyric.scroll.fast_gap_ms", 850L);
    private static final long SCROLL_INTERPOLATION_HALF_LIFE_MS = Long.getLong(
            "bili.lyric.scroll.interpolation_half_life_ms", 35L);
    private static final float SCROLL_MAX_INTERPOLATION_LAG = 0.18F;
    private static final long LYRIC_AUDIO_DELAY_MS = Long.getLong("ncpb.lyric.audio_delay_ms", 0L);
    private static final float LINE_STEP = 14.0F;
    private static final int VISIBLE_LINES_ABOVE = 2;
    private static final int VISIBLE_LINES_BELOW = 2;
    private static final long AI_BASE_RESYNC_TICKS = 40L;
    private static final double PROJECTOR_RENDER_MARGIN = Double.parseDouble(
            System.getProperty("ncpb.lyric.projector.render_margin", "2.0"));
    private static final double PROJECTOR_RENDER_MIN_INFLATE = Double.parseDouble(
            System.getProperty("ncpb.lyric.projector.render_min_inflate", "2.5"));
    private static final double PROJECTOR_RENDER_MAX_TEXT_WIDTH = Double.parseDouble(
            System.getProperty("ncpb.lyric.projector.render_max_text_width", "8.0"));

    private static final Map<BlockPos, ScrollProgressState> scrollProgressStates = new ConcurrentHashMap<>();

    private final Font font;

    public LyricProjectorRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(LyricProjectorBlockEntity projector, State state, float partialTick,
            Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(projector, state, partialTick, cameraPos, crumblingOverlay);

        state.currentLine = Component.empty();
        state.translatedLine = null;
        state.currentLyricColor = ConfigEvent.PLAYER_ORIGINAL_COLOR;
        state.transLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
        state.visible = false;
        state.linkedPos = immutable(projector.getLinkedTurntablePos());
        state.projectorPos = immutable(projector.getBlockPos());
        state.projectionYaw = projector.getProjectionYaw();
        state.projectionPitch = projector.getProjectionPitch();
        state.projectionScale = projector.getProjectionScale();
        state.projectionHeight = projector.getProjectionHeight();
        state.projectionDistanceX = projector.getProjectionDistanceX();
        state.projectionDistanceZ = projector.getProjectionDistanceZ();
        state.projectionMode = projector.getProjectionMode();
        state.allowAi = projector.getAllowAi();

        if (state.linkedPos == null || projector.getLevel() == null) {
            syncActivatedState(projector, false);
            return;
        }

        // 从连接的现代唱片机获取歌词
        var level = projector.getLevel();
        if (!(level.getBlockEntity(state.linkedPos) instanceof ModernTurntableBlockEntity turntable)) {
            ClientLinkRegistry.unlink(projector.getBlockPos());
            projector.unlink();
            syncActivatedState(projector, false);
            return;
        }

        ClientLinkRegistry.link(projector.getBlockPos(), state.linkedPos);

        if (!turntable.isPlaying()) {
            syncActivatedState(projector, false);
            return;
        }

        LyricRecord lyricRecord = turntable.getClientLyricRecord();
        if (lyricRecord == null) {
            syncActivatedState(projector, false);
            return;
        }
        float lyricTickOverride = resolveProjectorLyricTick(state.linkedPos, turntable);

        // 动态 AI 字幕刷新：当 allowAi 开启但尚未缓存 AI 歌词时，异步获取
        if (state.allowAi) {
            String rawUrl = turntable.getRawUrl();
            if (rawUrl != null && !rawUrl.isBlank() && BiliApiClient.isStoredVideoSelection(rawUrl)) {
                String cachedUrl = projector.getCachedAiRawUrl();
                LyricRecord aiCached = projector.getCachedAiLyricRecord();
                if (aiCached != null && rawUrl.equals(cachedUrl)) {
                    if (lyricTickOverride >= 0.0F) {
                        long baseTick = projector.getCachedAiBaseTick();
                        long roundedTick = Math.round(lyricTickOverride);
                        if (baseTick < 0L || Math.abs(baseTick - roundedTick) > AI_BASE_RESYNC_TICKS) {
                            projector.setCachedAiBaseTick(roundedTick);
                        }
                    }
                    lyricRecord = aiCached;
                } else if (cachedUrl == null || !cachedUrl.equals(rawUrl)) {
                    // 新歌曲或无缓存，标记 URL 并触发异步获取（标记即防重）
                    projector.setCachedAiLyricRecord(null, rawUrl);
                    String songName = turntable.getSongName();
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            LyricRecord aiRecord = BiliSubtitleLyricService.tryBuildLyricRecord(rawUrl, songName, true);
                            if (aiRecord != null) {
                                Minecraft.getInstance().execute(() -> {
                                    projector.setCachedAiLyricRecord(aiRecord, rawUrl);
                                });
                            }
                        } catch (Exception ignored) {
                        }
                    });
                }
                // else: pending（cachedUrl 匹配但 record 为 null），等待异步结果，先用现有歌词
            }
        }

        int lyricLookupTick = lyricTickOverride >= 0.0F ? (int) Math.floor(lyricTickOverride)
                : turntable.getClientLyricTick();
        String current = lyricTickOverride >= 0.0F
                ? currentLineAt(lyricRecord.getLyrics(), lyricLookupTick)
                : currentLine(lyricRecord.getLyrics());
        String translated = lyricTickOverride >= 0.0F
                ? currentLineAt(lyricRecord.getTransLyrics(), lyricLookupTick)
                : currentLine(lyricRecord.getTransLyrics());
        boolean hasCurrent = current != null && !current.isBlank();
        boolean hasTranslated = translated != null && !translated.isBlank();
        if (!hasCurrent && !hasTranslated) {
            syncActivatedState(projector, false);
            return;
        }

        state.visible = true;
        state.lightCoords = 0x00F000F0;
        if (hasCurrent) {
            state.currentLine = Component.literal(current);
        }
        if (hasTranslated) {
            state.translatedLine = Component.literal(translated);
        } else {
            state.currentLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
        }

        state.lyrics = lyricRecord.getLyrics();
        state.transLyrics = lyricRecord.getTransLyrics();
        state.scrollPastLines = null;
        state.scrollFutureLines = null;
        state.scrollProgress = 1.0F;

        if (state.projectionMode >= 1) {
            Int2ObjectSortedMap<String> active = state.projectionMode == 2
                    ? state.transLyrics
                    : state.lyrics;
            if ((active == null || active.isEmpty()) && state.projectionMode == 2) {
                active = state.lyrics;
            }
            if (active != null && !active.isEmpty()) {
                int currentKey = lyricTickOverride >= 0.0F
                        ? currentKeyAt(active, lyricLookupTick)
                        : active.firstIntKey();
                String activeLine = active.get(currentKey);
                if (activeLine != null && !activeLine.isBlank()) {
                    state.currentLine = Component.literal(activeLine);
                    state.translatedLine = null;
                    state.currentLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;

                    state.scrollPastLines = collectPastLines(active, currentKey);

                    List<String> future = new ArrayList<>();
                    int[] keys = active.keySet().toIntArray();
                    for (int tick : keys) {
                        if (tick <= currentKey)
                            continue;
                        String line = active.get(tick);
                        if (line != null && !line.isBlank()) {
                            future.add(line);
                            if (future.size() >= VISIBLE_LINES_BELOW)
                                break;
                        }
                    }
                    state.scrollFutureLines = future;
                    float nowTick = lyricTickOverride >= 0.0F ? lyricTickOverride : currentKey;
                    int nextTick = nextLyricTick(active, currentKey);
                    float targetProgress = timelineScrollProgress(currentKey, nextTick, nowTick);
                    state.scrollProgress = interpolateScrollProgress(state.projectorPos, currentKey, targetProgress);
                }
            }
        }

        syncActivatedState(projector, state.visible);
    }

    /**
     * 根据歌词是否可见，更新方块的 ACTIVATED 状态
     */
    private static void syncActivatedState(LyricProjectorBlockEntity projector, boolean visible) {
        var level = projector.getLevel();
        if (level == null)
            return;
        BlockPos pos = projector.getBlockPos();
        BlockState currentState = level.getBlockState(pos);
        if (!currentState.hasProperty(LyricProjectorBlock.ACTIVATED))
            return;
        boolean currentlyActivated = currentState.getValue(LyricProjectorBlock.ACTIVATED);
        if (visible != currentlyActivated) {
            Minecraft.getInstance().execute(() -> {
                var lvl = projector.getLevel();
                if (lvl != null) {
                    BlockState bs = lvl.getBlockState(pos);
                    if (bs.hasProperty(LyricProjectorBlock.ACTIVATED)) {
                        lvl.setBlock(pos, bs.setValue(LyricProjectorBlock.ACTIVATED, visible), 3);
                    }
                }
            });
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState cameraState) {
        if (!state.visible || state.linkedPos == null) {
            return;
        }

        boolean hasCurrent = !state.currentLine.getString().isBlank();
        boolean hasTranslated = state.translatedLine != null
                && !state.translatedLine.getString().isBlank();

        renderFace(state, poseStack, collector, cameraState, false, hasCurrent, hasTranslated);
        renderFace(state, poseStack, collector, cameraState, true, hasCurrent, hasTranslated);
    }

    private void renderFace(State state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState cameraState, boolean backFace, boolean hasCurrent, boolean hasTranslated) {
        poseStack.pushPose();

        float yaw = state.projectionYaw;
        float pitch = state.projectionPitch;
        float scale = state.projectionScale;
        int mode = state.projectionMode;

        poseStack.translate(0.5D + state.projectionDistanceX, state.projectionHeight,
                0.5D + state.projectionDistanceZ);
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));

        if (backFace) {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        }

        poseStack.scale(-TEXT_SCALE * Math.abs(scale), -TEXT_SCALE * Math.abs(scale),
                -TEXT_SCALE * Math.abs(scale));

        if (mode >= 1 && state.projectorPos != null) {
            renderScrollMode(state, poseStack, collector);
        } else {
            if (hasCurrent) {
                submitCenteredText(state.currentLine, 0, state.currentLyricColor, state, poseStack, collector);
            }
            if (hasTranslated) {
                submitCenteredText(state.translatedLine, -TRANSLATED_LINE_OFFSET, state.transLyricColor,
                        state, poseStack, collector);
            }
        }

        poseStack.popPose();
    }

    /** 轮换模式：单轨多行滚动，字号和透明度渐变 */
    private void renderScrollMode(State state, PoseStack poseStack, SubmitNodeCollector collector) {
        float progress = state.scrollProgress;

        List<String> lines = new ArrayList<>();
        int pastCount = 0;
        if (state.scrollPastLines != null) {
            lines.addAll(state.scrollPastLines);
            pastCount = state.scrollPastLines.size();
        }
        lines.add(state.currentLine.getString());
        if (state.scrollFutureLines != null) {
            lines.addAll(state.scrollFutureLines);
        }

        if (lines.isEmpty())
            return;

        int centerIdx = Math.min(pastCount, VISIBLE_LINES_ABOVE);
        centerIdx = Math.min(centerIdx, lines.size() - 1);
        if (centerIdx < 0)
            centerIdx = 0;

        for (int i = 0; i < lines.size(); i++) {
            float effectiveDist = (i - centerIdx) + (1.0F - progress);
            float absDist = Math.abs(effectiveDist);

            float t = Math.clamp(absDist / 2.0f, 0.0f, 1.0f);
            float eased = t * t * (3.0f - 2.0f * t);

            float sizeScale = 1.0f - eased * (1.0f - 0.56f);

            int userC = state.currentLyricColor;
            int dimWhite = 0x40FFFFFF;
            int color = lerpColor(userC, dimWhite, eased);

            float y = effectiveDist * LINE_STEP;
            submitCenteredText(Component.literal(lines.get(i)), y, color, sizeScale,
                    state, poseStack, collector);
        }
    }

    private static int lerpColor(int a, int b, float t) {
        int aa = (int) ((a >>> 24) + ((b >>> 24) - (a >>> 24)) * t);
        int ar = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int ag = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int ab = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }

    @Override
    public AABB getRenderBoundingBox(LyricProjectorBlockEntity blockEntity) {
        double scale = Math.max(0.25D, Math.abs(blockEntity.getProjectionScale()));
        double textHalfWidth = PROJECTOR_RENDER_MAX_TEXT_WIDTH * scale * 0.5D;
        double lineHeight = LINE_STEP * TEXT_SCALE * scale;
        double textHalfHeight = Math.max(lineHeight,
                lineHeight * (1 + VISIBLE_LINES_ABOVE + VISIBLE_LINES_BELOW) * 0.5D);
        double textRadius = Math.sqrt(textHalfWidth * textHalfWidth + textHalfHeight * textHalfHeight);
        double offsetRadius = Math.sqrt(
                blockEntity.getProjectionDistanceX() * blockEntity.getProjectionDistanceX()
                        + blockEntity.getProjectionHeight() * blockEntity.getProjectionHeight()
                        + blockEntity.getProjectionDistanceZ() * blockEntity.getProjectionDistanceZ());
        double inflate = Math.max(PROJECTOR_RENDER_MIN_INFLATE, offsetRadius + textRadius + PROJECTOR_RENDER_MARGIN);
        return new AABB(blockEntity.getBlockPos()).inflate(inflate, inflate, inflate);
    }

    private void submitCenteredText(MutableComponent text, float y, int color, State state, PoseStack poseStack,
            SubmitNodeCollector collector) {
        submitCenteredText(text, y, color, 1.0F, state, poseStack, collector);
    }

    private void submitCenteredText(MutableComponent text, float y, int color, float textScale,
            State state, PoseStack poseStack, SubmitNodeCollector collector) {
        poseStack.pushPose();
        poseStack.scale(textScale, textScale, 1.0F);
        FormattedCharSequence visual = text.getVisualOrderText();
        float x = -font.width(text) / 2.0F;
        int bgColor = ((int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F)) << 24;
        collector.submitText(poseStack, x, y / textScale, visual, false, Font.DisplayMode.NORMAL,
                state.lightCoords, color, bgColor, 0);
        poseStack.popPose();
    }

    private static String currentLine(Int2ObjectSortedMap<String> lyrics) {
        if (lyrics == null || lyrics.isEmpty()) {
            return null;
        }
        return lyrics.get(lyrics.firstIntKey());
    }

    private static String currentLineAt(Int2ObjectSortedMap<String> lyrics, int tick) {
        if (lyrics == null || lyrics.isEmpty()) {
            return null;
        }
        return lyrics.get(currentKeyAt(lyrics, tick));
    }

    private static int currentKeyAt(Int2ObjectSortedMap<String> lyrics, int tick) {
        if (lyrics == null || lyrics.isEmpty()) {
            return 0;
        }
        Int2ObjectSortedMap<String> elapsed = tick >= Integer.MAX_VALUE
                ? lyrics
                : lyrics.headMap(tick + 1);
        return elapsed.isEmpty() ? lyrics.firstIntKey() : elapsed.lastIntKey();
    }

    private static int nextLyricTick(Int2ObjectSortedMap<String> lyrics, int currentTick) {
        if (lyrics == null || lyrics.isEmpty()) {
            return -1;
        }
        Int2ObjectSortedMap<String> tail = lyrics.tailMap(currentTick + 1);
        for (var entry : tail.int2ObjectEntrySet()) {
            String line = entry.getValue();
            if (line != null && !line.isBlank()) {
                return entry.getIntKey();
            }
        }
        return -1;
    }

    private static List<String> collectPastLines(Int2ObjectSortedMap<String> lyrics, int currentTick) {
        List<String> past = new ArrayList<>();
        if (lyrics == null || lyrics.isEmpty()) {
            return past;
        }
        int[] keys = lyrics.keySet().toIntArray();
        for (int i = keys.length - 1; i >= 0 && past.size() < VISIBLE_LINES_ABOVE; i--) {
            int tick = keys[i];
            if (tick >= currentTick) {
                continue;
            }
            String line = lyrics.get(tick);
            if (line != null && !line.isBlank()) {
                past.add(0, line);
            }
        }
        return past;
    }

    private static float timelineScrollProgress(int currentTick, int nextTick, float nowTick) {
        if (nextTick <= currentTick || nowTick < currentTick) {
            return 1.0F;
        }
        float elapsedMillis = Math.max(0.0F, (nowTick - currentTick) * 50.0F);
        long durationMillis = adaptiveScrollDurationMillis(currentTick, nextTick);
        if (durationMillis <= 0L || elapsedMillis >= durationMillis) {
            return 1.0F;
        }
        float raw = Math.clamp((float) elapsedMillis / durationMillis, 0.0F, 1.0F);
        return 1.0F - (float) Math.pow(1.0F - raw, 3.0F);
    }

    private static float interpolateScrollProgress(BlockPos projectorPos, int currentKey, float targetProgress) {
        if (projectorPos == null) {
            return targetProgress;
        }
        ScrollProgressState state = scrollProgressStates.computeIfAbsent(projectorPos.immutable(),
                ignored -> new ScrollProgressState());
        long nowNanos = System.nanoTime();
        if (state.currentKey != currentKey) {
            state.currentKey = currentKey;
            state.targetProgress = targetProgress;
            state.displayProgress = targetProgress >= 0.98F ? 1.0F : Math.max(0.0F, targetProgress - 0.06F);
            state.lastUpdateNanos = nowNanos;
            return state.displayProgress;
        }
        if (targetProgress + 0.001F < state.targetProgress) {
            targetProgress = state.targetProgress;
        }
        state.targetProgress = targetProgress;

        long elapsedNanos = state.lastUpdateNanos > 0L ? Math.max(0L, nowNanos - state.lastUpdateNanos) : 0L;
        state.lastUpdateNanos = nowNanos;
        if (targetProgress >= 0.999F) {
            state.displayProgress = 1.0F;
            return 1.0F;
        }

        float delta = targetProgress - state.displayProgress;
        if (delta <= 0.001F) {
            state.displayProgress = Math.max(state.displayProgress, targetProgress);
            return state.displayProgress;
        }

        float alpha = interpolationAlpha(elapsedNanos);
        float interpolated = state.displayProgress + delta * alpha;
        float minProgress = Math.max(state.displayProgress, targetProgress - SCROLL_MAX_INTERPOLATION_LAG);
        state.displayProgress = Math.clamp(Math.max(interpolated, minProgress), 0.0F, targetProgress);
        return state.displayProgress;
    }

    private static float interpolationAlpha(long elapsedNanos) {
        if (elapsedNanos <= 0L) {
            return 0.0F;
        }
        double halfLifeMillis = Math.max(1.0D, SCROLL_INTERPOLATION_HALF_LIFE_MS);
        double elapsedMillis = elapsedNanos / 1_000_000.0D;
        return (float) Math.clamp(1.0D - Math.pow(0.5D, elapsedMillis / halfLifeMillis), 0.0D, 1.0D);
    }

    private static long adaptiveScrollDurationMillis(int currentTick, int nextTick) {
        if (nextTick <= currentTick) {
            return SCROLL_DURATION_MS;
        }
        long gapMillis = Math.max(0L, (nextTick - currentTick) * 50L);
        if (gapMillis <= 0L) {
            return SCROLL_MIN_DURATION_MS;
        }
        long target = Math.min(SCROLL_DURATION_MS, Math.max(SCROLL_MIN_DURATION_MS, gapMillis * 2L / 3L));
        if (gapMillis <= SCROLL_FAST_GAP_MS) {
            target = Math.min(target, Math.max(SCROLL_MIN_DURATION_MS, gapMillis / 2L));
        }
        return target;
    }

    private static float resolveProjectorLyricTick(BlockPos turntablePos, ModernTurntableBlockEntity turntable) {
        long audibleMillis = ClientAudioOutputRegistry.getAudioTimeline(turntablePos).audibleMillis();
        if (audibleMillis >= 0L) {
            return Math.max(0L, audibleMillis - Math.max(0L, LYRIC_AUDIO_DELAY_MS)) / 50.0F;
        }
        long visualMillis = ModernTurntableTimeline.visualMillis(turntablePos);
        if (visualMillis >= 0L) {
            return Math.max(0L, visualMillis - Math.max(0L, LYRIC_AUDIO_DELAY_MS)) / 50.0F;
        }
        return turntable.getClientLyricTick();
    }

    private static BlockPos immutable(BlockPos pos) {
        return pos != null ? pos.immutable() : null;
    }

    public static class State extends BlockEntityRenderState {
        public MutableComponent currentLine = Component.empty();
        public MutableComponent translatedLine;
        public int currentLyricColor = ConfigEvent.PLAYER_ORIGINAL_COLOR;
        public int transLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
        public boolean visible;
        public BlockPos linkedPos;
        public BlockPos projectorPos;
        public float projectionYaw = 180.0F;
        public float projectionPitch = 0.0F;
        public float projectionScale = 1.0F;
        public float projectionHeight = 1.2F;
        public float projectionDistanceX = 0.0F;
        public float projectionDistanceZ = 0.0F;
        public int projectionMode;
        public boolean allowAi;
        public Int2ObjectSortedMap<String> lyrics;
        public Int2ObjectSortedMap<String> transLyrics;
        public List<String> scrollPastLines;
        public List<String> scrollFutureLines;
        public float scrollProgress = 1.0F;
    }

    private static class ScrollProgressState {
        int currentKey = Integer.MIN_VALUE;
        float targetProgress = 1.0F;
        float displayProgress = 1.0F;
        long lastUpdateNanos;
    }
}
