package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import javax.annotation.Nullable;

/**
 * 视频投影仪方块实体
 */
public class VideoProjectorBlockEntity extends BlockEntity {
    private static final String LINK_KEY = "LinkedTarget";
    private static final String PROJ_YAW = "ProjYaw";
    private static final String PROJ_PITCH = "ProjPitch";
    private static final String PROJ_SCALE = "ProjScale";
    private static final String PROJ_HEIGHT = "ProjHeight";
    private static final String PROJ_DISTANCE = "ProjDistance";
    private static final String PREFERRED_QUALITY = "PreferredQuality";
    public static final int DEFAULT_PREFERRED_QUALITY = 116;

    @Nullable
    private BlockPos linkedTurntablePos;
    private float projectionYaw = 180.0F;
    private float projectionPitch = 0.0F;
    private float projectionScale = 1.0F;
    private float projectionHeight = 1.8F;
    private float projectionDistance = 0.0F;
    /** B站 qn：默认尝试 1080P60；实际会受登录/VIP/接口返回限制自动降级 */
    private int preferredQuality = DEFAULT_PREFERRED_QUALITY;

    public VideoProjectorBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.VIDEO_PROJECTOR.get(), pos, blockState);
    }

    public void linkTo(BlockPos turntablePos) {
        this.linkedTurntablePos = turntablePos.immutable();
        refreshClientLinkRegistration();
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void unlink() {
        if (level != null && level.isClientSide()) {
            com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry.unlink(worldPosition);
        }
        this.linkedTurntablePos = null;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
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
        return projectionDistance;
    }

    public void setProjectionDistance(float v) {
        this.projectionDistance = v;
        setChanged();
    }

    public int getPreferredQuality() {
        return preferredQuality;
    }

    public void setPreferredQuality(int v) {
        this.preferredQuality = v;
        setChanged();
    }

    public void markDirtyAndSync() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) {
            com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry.unlink(worldPosition);
            com.zhongbai233.net_music_can_play_bili.client.renderer.VideoBillboardPreview
                    .stopIfProjector(worldPosition);
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
        output.putFloat(PROJ_DISTANCE, projectionDistance);
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
        this.projectionDistance = input.getFloatOr(PROJ_DISTANCE, 0.0F);
        this.preferredQuality = input.getIntOr(PREFERRED_QUALITY, DEFAULT_PREFERRED_QUALITY);
        refreshClientLinkRegistration();
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

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    @Nullable
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}