package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import javax.annotation.Nullable;

/**
 * 视频投影仪方块实体
 */
public class VideoProjectorBlockEntity extends SyncedBlockEntity {
    private static final String LINK_KEY = "LinkedTarget";
    private static final String PROJ_YAW = "ProjYaw";
    private static final String PROJ_PITCH = "ProjPitch";
    private static final String PROJ_SCALE = "ProjScale";
    private static final String PROJ_HEIGHT = "ProjHeight";
    private static final String PROJ_DISTANCE_X = "ProjDistanceX";
    private static final String PROJ_DISTANCE_Z = "ProjDistanceZ";
    private static final String PREFERRED_QUALITY = "PreferredQuality";
    public static final int DEFAULT_PREFERRED_QUALITY = 116;

    @Nullable
    private BlockPos linkedTurntablePos;
    private float projectionYaw = 180.0F;
    private float projectionPitch = 0.0F;
    private float projectionScale = 1.0F;
    private float projectionHeight = 1.8F;
    private float projectionDistanceX = 0.0F;
    private float projectionDistanceZ = 0.0F;
    /** B站 qn：默认尝试 1080P60；实际会受登录/VIP/接口返回限制自动降级 */
    private int preferredQuality = DEFAULT_PREFERRED_QUALITY;

    public VideoProjectorBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.VIDEO_PROJECTOR.get(), pos, blockState);
    }

    public void linkTo(BlockPos turntablePos) {
        if (level instanceof ServerLevel serverLevel) {
            AudioLinkIndex.unregisterVideoProjector(serverLevel, worldPosition);
        }
        this.linkedTurntablePos = turntablePos.immutable();
        if (level instanceof ServerLevel serverLevel) {
            AudioLinkIndex.registerVideoProjector(serverLevel, worldPosition, linkedTurntablePos);
        }
        refreshClientLinkRegistration();
        markDirtyAndSync();
    }

    public void unlink() {
        if (level != null && level.isClientSide()) {
            com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry.unlink(worldPosition);
        }
        if (level instanceof ServerLevel serverLevel) {
            AudioLinkIndex.unregisterVideoProjector(serverLevel, worldPosition);
        }
        this.linkedTurntablePos = null;
        markDirtyAndSync();
    }

    @Nullable
    public BlockPos getLinkedTurntablePos() {
        return linkedTurntablePos;
    }

    public boolean isLinked() {
        return linkedTurntablePos != null;
    }

    public float getProjectionYaw() {
        return projectionYaw;
    }

    public void setProjectionYaw(float v) {
        this.projectionYaw = v;
        setChanged();
    }

    public float getProjectionPitch() {
        return projectionPitch;
    }

    public void setProjectionPitch(float v) {
        this.projectionPitch = v;
        setChanged();
    }

    public float getProjectionScale() {
        return projectionScale;
    }

    public void setProjectionScale(float v) {
        this.projectionScale = v;
        setChanged();
    }

    public float getProjectionHeight() {
        return projectionHeight;
    }

    public void setProjectionHeight(float v) {
        this.projectionHeight = v;
        setChanged();
    }

    public float getProjectionDistance() {
        return projectionDistanceX;
    }

    public void setProjectionDistance(float v) {
        setProjectionDistanceX(v);
    }

    public float getProjectionDistanceX() {
        return projectionDistanceX;
    }

    public void setProjectionDistanceX(float v) {
        this.projectionDistanceX = v;
        setChanged();
    }

    public float getProjectionDistanceZ() {
        return projectionDistanceZ;
    }

    public void setProjectionDistanceZ(float v) {
        this.projectionDistanceZ = v;
        setChanged();
    }

    public int getPreferredQuality() {
        return preferredQuality;
    }

    public void setPreferredQuality(int v) {
        this.preferredQuality = v;
        setChanged();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) {
            com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry.unlink(worldPosition);
            com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview
                    .stopIfProjector(worldPosition);
        }
        if (level instanceof ServerLevel serverLevel) {
            AudioLinkIndex.unregisterVideoProjector(serverLevel, worldPosition);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        LinkHelper.saveLinkToBE(output, linkedTurntablePos,
                LINK_KEY + "_has", LINK_KEY + "_x", LINK_KEY + "_y", LINK_KEY + "_z");
        output.putFloat(PROJ_YAW, projectionYaw);
        output.putFloat(PROJ_PITCH, projectionPitch);
        output.putFloat(PROJ_SCALE, projectionScale);
        output.putFloat(PROJ_HEIGHT, projectionHeight);
        output.putFloat(PROJ_DISTANCE_X, projectionDistanceX);
        output.putFloat(PROJ_DISTANCE_Z, projectionDistanceZ);
        output.putInt(PREFERRED_QUALITY, preferredQuality);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        int oldPreferredQuality = this.preferredQuality;
        this.linkedTurntablePos = LinkHelper.loadLinkFromBE(input,
                LINK_KEY + "_has", LINK_KEY + "_x", LINK_KEY + "_y", LINK_KEY + "_z");
        this.projectionYaw = input.getFloatOr(PROJ_YAW, 180.0F);
        this.projectionPitch = input.getFloatOr(PROJ_PITCH, 0.0F);
        this.projectionScale = input.getFloatOr(PROJ_SCALE, 1.0F);
        this.projectionHeight = input.getFloatOr(PROJ_HEIGHT, 1.8F);
        this.projectionDistanceX = input.getFloatOr(PROJ_DISTANCE_X, 0.0F);
        this.projectionDistanceZ = input.getFloatOr(PROJ_DISTANCE_Z, 0.0F);
        this.preferredQuality = input.getIntOr(PREFERRED_QUALITY, DEFAULT_PREFERRED_QUALITY);
        refreshClientLinkRegistration();
        if (level instanceof ServerLevel serverLevel && linkedTurntablePos != null) {
            AudioLinkIndex.registerVideoProjector(serverLevel, worldPosition, linkedTurntablePos);
        }
        if (level != null && level.isClientSide() && oldPreferredQuality != preferredQuality) {
            com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient.refreshProjector(worldPosition);
        }
    }

    private void refreshClientLinkRegistration() {
        if (level != null && level.isClientSide()) {
            com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry.unlink(worldPosition);
            if (linkedTurntablePos != null) {
                com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry.link(worldPosition, linkedTurntablePos);
            }
        }
    }

}