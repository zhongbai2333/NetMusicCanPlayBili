package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.event.ConfigEvent;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

public class ModernTurntableRenderer
        implements BlockEntityRenderer<ModernTurntableBlockEntity, ModernTurntableRenderer.State> {
    private static final float TEXT_SCALE = 0.025F;
    private static final float TRANSLATED_LINE_OFFSET = 12.0F;

    private final Font font;

    public ModernTurntableRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ModernTurntableBlockEntity turntable, State state, float partialTick,
            Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(turntable, state, partialTick, cameraPos, crumblingOverlay);

        state.currentLine = Component.empty();
        state.translatedLine = null;
        state.currentLyricColor = ConfigEvent.PLAYER_ORIGINAL_COLOR;
        state.transLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
        state.y = 0.5F;
        state.projected = false;

        if (!GeneralConfig.ENABLE_PLAYER_LYRICS.get() || !turntable.isPlaying()) {
            return;
        }

        if (isLinkedToProjector(turntable)) {
            state.projected = true;
            state.currentLine = Component.translatable(
                    "message.net_music_can_play_bili.modern_turntable.projected");
            state.currentLyricColor = 0xFFAAAAAA;
            return;
        }

        LyricRecord lyricRecord = turntable.getClientLyricRecord();
        if (lyricRecord == null) {
            return;
        }

        String current = currentLine(lyricRecord.getLyrics());
        String translated = currentLine(lyricRecord.getTransLyrics());
        boolean hasCurrent = current != null && !current.isBlank();
        boolean hasTranslated = translated != null && !translated.isBlank();
        if (!hasCurrent && !hasTranslated) {
            return;
        }

        if (hasCurrent) {
            state.currentLine = Component.literal(current);
        }
        if (hasTranslated) {
            state.translatedLine = Component.literal(translated);
            state.y += 0.5F;
        } else {
            state.currentLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraState) {
        boolean hasCurrent = state.currentLine != null && !state.currentLine.getString().isBlank();
        boolean hasTranslated = state.translatedLine != null && !state.translatedLine.getString().isBlank();
        if (!hasCurrent && !hasTranslated) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, 1.625D, 0.5D);
        poseStack.mulPose(Axis.YN.rotationDegrees(cameraState.yRot));
        poseStack.mulPose(Axis.XN.rotationDegrees(-cameraState.xRot));
        poseStack.scale(-TEXT_SCALE, -TEXT_SCALE, -TEXT_SCALE);

        if (hasCurrent) {
            submitCenteredText(state.currentLine, -state.y, state.currentLyricColor, state, poseStack, collector);
        }
        if (hasTranslated) {
            submitCenteredText(state.translatedLine, -state.y - TRANSLATED_LINE_OFFSET, state.transLyricColor,
                    state, poseStack, collector);
        }

        poseStack.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(ModernTurntableBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(1.0D, 2.5D, 1.0D);
    }

    private void submitCenteredText(MutableComponent text, float y, int color, State state, PoseStack poseStack,
            SubmitNodeCollector collector) {
        FormattedCharSequence visual = text.getVisualOrderText();
        float x = -font.width(text) / 2.0F;
        int backgroundColor = ((int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F)) << 24;
        collector.submitText(poseStack, x, y, visual, false, Font.DisplayMode.NORMAL,
                state.lightCoords, color, backgroundColor, 0);
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
        public float y = 0.5F;
        public boolean projected;
    }

    private static boolean isLinkedToProjector(ModernTurntableBlockEntity turntable) {
        var level = turntable.getLevel();
        if (level == null) {
            return false;
        }
        for (BlockPos sourcePos : ClientLinkRegistry.getSources(turntable.getBlockPos())) {
            if (level.getBlockEntity(sourcePos) instanceof LyricProjectorBlockEntity) {
                return true;
            }
        }
        return false;
    }
}