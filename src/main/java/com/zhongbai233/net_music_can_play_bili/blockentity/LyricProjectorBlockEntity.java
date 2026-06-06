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
    private static final String PROJ_DISTANCE_X = "ProjDistanceX";
    private static final String PROJ_DISTANCE_Z = "ProjDistanceZ";
    private static final String PROJ_MODE = "ProjMode";
    private static final String ALLOW_AI = "AllowAi";

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
    /** 投影 X 轴偏移 -5.0~5.0 */
    private float projectionDistanceX = 0.0F;
    /** 投影 Z 轴偏移 -5.0~5.0 */
    private float projectionDistanceZ = 0.0F;
    /** 字幕显示模式：0=静态, 1=轮换(主字幕), 2=轮换(副字幕) */
    private int projectionMode;
    /** 是否允许显示 AI 字幕 */
    private boolean allowAi;

    private transient LyricRecord cachedLyricRecord;
    private transient LyricRecord cachedAiLyricRecord;
    private transient String cachedAiRawUrl;
    private transient long cachedAiBaseTick = -1;

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

    @Nullable
    public LyricRecord getCachedAiLyricRecord() {
        return cachedAiLyricRecord;
    }

    public void setCachedAiLyricRecord(@Nullable LyricRecord record, String rawUrl) {
        this.cachedAiLyricRecord = record;
        this.cachedAiRawUrl = rawUrl;
        this.cachedAiBaseTick = -1;
    }

    public String getCachedAiRawUrl() {
        return cachedAiRawUrl;
    }

    public long getCachedAiBaseTick() {
        return cachedAiBaseTick;
    }

    public void setCachedAiBaseTick(long tick) {
        this.cachedAiBaseTick = tick;
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

    public int getProjectionMode() {
        return projectionMode;
    }

    public void setProjectionMode(int v) {
        this.projectionMode = v;
        setChanged();
    }

    public boolean getAllowAi() {
        return allowAi;
    }

    public void setAllowAi(boolean v) {
        this.allowAi = v;
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
        output.putInt(PROJ_MODE, projectionMode);
        output.putBoolean(ALLOW_AI, allowAi);
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
        float legacyDistance = input.getFloatOr(PROJ_DISTANCE, 0.0F);
        this.projectionDistanceX = input.getFloatOr(PROJ_DISTANCE_X, legacyDistance);
        this.projectionDistanceZ = input.getFloatOr(PROJ_DISTANCE_Z, 0.0F);
        this.projectionMode = input.getIntOr(PROJ_MODE, 0);
        this.allowAi = input.getBooleanOr(ALLOW_AI, false);
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
