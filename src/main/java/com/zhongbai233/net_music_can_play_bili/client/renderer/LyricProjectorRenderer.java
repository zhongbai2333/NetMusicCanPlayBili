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
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌词投影仪渲染器
 */
public class LyricProjectorRenderer
        implements BlockEntityRenderer<LyricProjectorBlockEntity, LyricProjectorRenderer.State> {
    private static final float TEXT_SCALE = 0.025F;
    private static final float TRANSLATED_LINE_OFFSET = 12.0F;
    private static final long SCROLL_DURATION_MS = 500;
    private static final float LINE_STEP = 14.0F;
    private static final int VISIBLE_LINES_ABOVE = 2;
    private static final int VISIBLE_LINES_BELOW = 2;

    /** 轮换模式动画状态：记录每个投影仪的上一当前行索引与切换时间 */
    private static final Map<BlockPos, ScrollState> scrollStates = new HashMap<>();

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
        state.linkedPos = projector.getLinkedTurntablePos();
        state.projectorPos = projector.getBlockPos();
        state.projectionYaw = projector.getProjectionYaw();
        state.projectionPitch = projector.getProjectionPitch();
        state.projectionScale = projector.getProjectionScale();
        state.projectionHeight = projector.getProjectionHeight();
        state.projectionDistance = projector.getProjectionDistance();
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
            scrollStates.remove(projector.getBlockPos());
            syncActivatedState(projector, false);
            return;
        }

        LyricRecord lyricRecord = turntable.getClientLyricRecord();
        if (lyricRecord == null) {
            scrollStates.remove(projector.getBlockPos());
            syncActivatedState(projector, false);
            return;
        }

        // 动态 AI 字幕刷新：当 allowAi 开启但尚未缓存 AI 歌词时，异步获取
        if (state.allowAi) {
            String rawUrl = turntable.getRawUrl();
            if (rawUrl != null && !rawUrl.isBlank() && BiliApiClient.isStoredVideoSelection(rawUrl)) {
                String cachedUrl = projector.getCachedAiRawUrl();
                LyricRecord aiCached = projector.getCachedAiLyricRecord();
                if (aiCached != null && rawUrl.equals(cachedUrl)) {
                    // 使用 OpenAL 播放位置同步 AI 歌词进度（与唱片机自身字幕逻辑一致）
                    long dolbyTicks = DolbyAudioRegistry.getStereoPositionTicks(state.linkedPos);
                    if (dolbyTicks >= 0L) {
                        long baseTick = projector.getCachedAiBaseTick();
                        if (baseTick < 0L) {
                            // 首次使用：用当前经过时间校准基准偏移
                            baseTick = turntable.getPlaybackElapsedMillis(level.getGameTime()) / 50L - dolbyTicks;
                            projector.setCachedAiBaseTick(Math.max(0L, baseTick));
                        }
                        int currentTick = (int) Math.min(Integer.MAX_VALUE,
                                Math.max(0L, projector.getCachedAiBaseTick() + dolbyTicks));
                        aiCached.updateCurrentLine(currentTick);
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

        String current = currentLine(lyricRecord.getLyrics());
        String translated = currentLine(lyricRecord.getTransLyrics());
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
        state.scrollFutureLines = null;

        if (state.projectionMode >= 1) {
            Int2ObjectSortedMap<String> active = state.projectionMode == 2
                    ? state.transLyrics
                    : state.lyrics;
            if ((active == null || active.isEmpty()) && state.projectionMode == 2) {
                active = state.lyrics;
            }
            if (active != null && !active.isEmpty()) {
                String activeLine = active.get(active.firstIntKey());
                if (activeLine != null && !activeLine.isBlank()) {
                    state.currentLine = Component.literal(activeLine);
                    state.translatedLine = null;
                    state.currentLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;

                    List<String> future = new ArrayList<>();
                    int[] keys = active.keySet().toIntArray();
                    int first = active.firstIntKey();
                    for (int tick : keys) {
                        if (tick == first)
                            continue;
                        String line = active.get(tick);
                        if (line != null && !line.isBlank()) {
                            future.add(line);
                            if (future.size() >= VISIBLE_LINES_BELOW)
                                break;
                        }
                    }
                    state.scrollFutureLines = future;

                    BlockPos pos = projector.getBlockPos();
                    ScrollState sc = scrollStates.computeIfAbsent(pos, k -> new ScrollState());

                    // 检测换歌（LyricRecord 变了），清理旧历史
                    int recordId = System.identityHashCode(lyricRecord);
                    if (sc.recordId != recordId) {
                        sc.history.clear();
                        sc.currentTick = 0;
                        sc.currentText = null;
                        sc.recordId = recordId;
                    }

                    int newTick = active.firstIntKey();
                    if (newTick != sc.currentTick && !activeLine.equals(sc.currentText)) {
                        long elapsed = System.currentTimeMillis() - sc.switchTime;
                        if (elapsed > SCROLL_DURATION_MS && sc.currentText != null
                                && !sc.currentText.isBlank()) {
                            sc.history.add(sc.currentText);
                            if (sc.history.size() > 10)
                                sc.history.remove(0);
                        }
                        sc.currentTick = newTick;
                        sc.currentText = activeLine;
                        sc.switchTime = System.currentTimeMillis();
                    }
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

        poseStack.translate(0.5D + state.projectionDistance, state.projectionHeight, 0.5D);
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
        ScrollState sc = scrollStates.get(state.projectorPos);

        float progress = 1.0F;
        if (sc != null) {
            long elapsed = System.currentTimeMillis() - sc.switchTime;
            if (elapsed < SCROLL_DURATION_MS) {
                float raw = (float) elapsed / SCROLL_DURATION_MS;
                progress = 1.0f - (float) Math.pow(1.0 - raw, 3);
            }
        }

        List<String> lines = new ArrayList<>();
        if (sc != null) {
            int start = Math.max(0, sc.history.size() - VISIBLE_LINES_ABOVE);
            for (int i = start; i < sc.history.size(); i++) {
                lines.add(sc.history.get(i));
            }
        }
        lines.add(state.currentLine.getString());
        if (state.scrollFutureLines != null) {
            lines.addAll(state.scrollFutureLines);
        }

        if (lines.isEmpty())
            return;

        int centerIdx = sc != null ? Math.min(sc.history.size(), VISIBLE_LINES_ABOVE) : 0;
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
        return new AABB(blockEntity.getBlockPos()).inflate(2.0D, 2.5D, 2.0D);
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
        public float projectionDistance = 0.0F;
        public int projectionMode;
        public boolean allowAi;
        public Int2ObjectSortedMap<String> lyrics;
        public Int2ObjectSortedMap<String> transLyrics;
        public List<String> scrollFutureLines;
    }

    private static class ScrollState {
        int currentTick;
        String currentText;
        int recordId;
        final List<String> history = new ArrayList<>();
        long switchTime;
    }
}
