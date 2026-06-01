package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
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

public class LyricProjectorBlockEntity extends BlockEntity {
    private static final String LINK_KEY = "LinkedTarget";
    private static final String PROJ_YAW = "ProjYaw";
    private static final String PROJ_PITCH = "ProjPitch";
    private static final String PROJ_SCALE = "ProjScale";
    private static final String PROJ_HEIGHT = "ProjHeight";
    private static final String PROJ_DISTANCE = "ProjDistance";
    private static final String PROJ_MODE = "ProjMode";

    @Nullable
    private BlockPos linkedTurntablePos;

    /** 水平朝向 0-360，默认朝南=180 */
    private float projectionYaw = 180.0F;
    /** 俯仰角 -90~90，默认水平=0 */
    private float projectionPitch = 0.0F;
    /** 文字缩放 0.25-3.0 */
    private float projectionScale = 1.0F;
    /** 投影高度（方块上方偏移）-5.0~5.0 */
    private float projectionHeight = 1.2F;
    /** 投影前方距离 -5.0~5.0 */
    private float projectionDistance = 0.0F;
    /** 字幕显示模式：0=静态, 1=轮换(主字幕), 2=轮换(副字幕) */
    private int projectionMode;

    private transient LyricRecord cachedLyricRecord;

    public LyricProjectorBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.LYRIC_PROJECTOR.get(), pos, blockState);
    }

    public void linkTo(BlockPos turntablePos) {
        this.linkedTurntablePos = turntablePos.immutable();
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void unlink() {
        this.linkedTurntablePos = null;
        this.cachedLyricRecord = null;
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

    public void cacheLyricRecord(LyricRecord record) {
        this.cachedLyricRecord = record;
    }

    @Nullable
    public LyricRecord getCachedLyricRecord() {
        return cachedLyricRecord;
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

    public int getProjectionMode() {
        return projectionMode;
    }

    public void setProjectionMode(int v) {
        this.projectionMode = v;
        setChanged();
    }

    public void markDirtyAndSync() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
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
        output.putInt(PROJ_MODE, projectionMode);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.linkedTurntablePos = LinkHelper.loadLinkFromBE(input,
                LINK_KEY + "_has", LINK_KEY + "_x", LINK_KEY + "_y", LINK_KEY + "_z");
        this.projectionYaw = input.getFloatOr(PROJ_YAW, 180.0F);
        this.projectionPitch = input.getFloatOr(PROJ_PITCH, 0.0F);
        this.projectionScale = input.getFloatOr(PROJ_SCALE, 1.0F);
        this.projectionHeight = input.getFloatOr(PROJ_HEIGHT, 1.2F);
        this.projectionDistance = input.getFloatOr(PROJ_DISTANCE, 0.0F);
        this.projectionMode = input.getIntOr(PROJ_MODE, 0);
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
