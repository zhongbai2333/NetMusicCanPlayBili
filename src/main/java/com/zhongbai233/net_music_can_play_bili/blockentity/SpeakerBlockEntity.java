package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;
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
 * 音响方块实体 — 将唱片机音频重定向到本方块位置输出，支持 7.1.4 声道选择
 */
public class SpeakerBlockEntity extends BlockEntity {
    private static final String LINK_KEY = "LinkedTarget";
    private static final String CHANNEL_INDEX = "ChannelIndex";
    private static final String VOLUME = "Volume";
    private static final String AUTO_MIX_JOC = "AutoMixJoc";

    /**
     * 声道索引：-1=无(静音), 0=L, 1=R, 2=C, 3=LFE, 4=Ls, 5=Rs, 6=Lrs, 7=Rrs, 8=Ltf, 9=Rtf,
     * 10=Ltr, 11=Rtr
     */
    public static final int CH_NONE = -1;
    public static final int CH_L = 0, CH_R = 1, CH_C = 2, CH_LFE = 3;
    public static final int CH_LS = 4, CH_RS = 5, CH_LRS = 6, CH_RRS = 7;
    public static final int CH_LTF = 8, CH_RTF = 9, CH_LTR = 10, CH_RTR = 11;
    public static final int CH_COUNT = 12;
    public static final String[] CH_NAMES = { "L", "R", "C", "LFE", "Ls", "Rs", "Lrs", "Rrs", "Ltf", "Rtf", "Ltr",
            "Rtr" };

    /** 声道索引 → 位（用于 relay 内部） */
    public static int channelBit(int index) {
        return index >= 0 && index < CH_COUNT ? (1 << index) : 0;
    }

    @Nullable
    private BlockPos linkedTurntablePos;

    /** 选中的声道索引，-1=静音 */
    private int channelIndex = CH_NONE;
    /** 音量 0.0~2.0，默认 1.0 */
    private float volume = 1.0f;
    /** 是否自动融合 JOC 移动对象到音响位置 */
    private boolean autoMixJoc;

    public SpeakerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.SPEAKER.get(), pos, blockState);
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

    public int getChannelIndex() {
        return channelIndex;
    }

    public void setChannelIndex(int v) {
        this.channelIndex = v;
        setChanged();
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float v) {
        this.volume = Math.clamp(v, 0.0f, 2.0f);
        setChanged();
    }

    public boolean isAutoMixJoc() {
        return autoMixJoc;
    }

    public void setAutoMixJoc(boolean v) {
        this.autoMixJoc = v;
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
        output.putInt(CHANNEL_INDEX, channelIndex);
        output.putFloat(VOLUME, volume);
        output.putBoolean(AUTO_MIX_JOC, autoMixJoc);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.linkedTurntablePos = LinkHelper.loadLinkFromBE(input,
                LINK_KEY + "_has", LINK_KEY + "_x", LINK_KEY + "_y", LINK_KEY + "_z");
        this.channelIndex = input.getIntOr(CHANNEL_INDEX, CH_NONE);
        this.volume = input.getFloatOr(VOLUME, 1.0f);
        this.autoMixJoc = input.getBooleanOr(AUTO_MIX_JOC, false);

        if (level != null && level.isClientSide()) {
            syncAudioOverride();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) {
            syncAudioOverride();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // 客户端和服务端均需清理 relay 和链接状态，防止长时间运行服务器内存泄漏
        DolbyAudioRegistry.clearMachineOverrideForSpeaker(worldPosition);
    }

    /** 此音响的独立音频 relay（客户端） */
    @Nullable
    private transient SpeakerAudioRelay audioRelay;

    /**
     * 客户端：根据链接状态注册/清除音频 relay，每个音响有独立的 OpenAL 管线
     */
    private void syncAudioOverride() {
        DolbyAudioRegistry.clearMachineOverrideForSpeaker(worldPosition);
        if (linkedTurntablePos != null) {
            // 每次重新同步都创建新的 relay；clearMachineOverrideForSpeaker 会 cleanup 旧 relay
            SpeakerAudioRelay relay = new SpeakerAudioRelay();
            audioRelay = relay;
            relay.setChannelIndex(channelIndex);
            relay.setUserVolume(volume);
            DolbyAudioRegistry.registerRelay(worldPosition, linkedTurntablePos, relay);
            DolbyAudioRegistry.updateRelayConfig(worldPosition, channelIndex, volume, autoMixJoc);
        } else {
            if (audioRelay != null) {
                audioRelay.cleanup();
                audioRelay = null;
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
