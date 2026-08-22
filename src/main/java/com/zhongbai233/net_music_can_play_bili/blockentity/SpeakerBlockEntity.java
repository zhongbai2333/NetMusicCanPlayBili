package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 音响方块实体 — 将唱片机音频重定向到本方块位置输出，支持 7.1.4 声道选择
 */
public class SpeakerBlockEntity extends SyncedBlockEntity {
    private static final String LINK_KEY = "LinkedTarget";
    private static final String CHANNEL_INDEX = "ChannelIndex";
    private static final String VOLUME = "Volume";
    private static final String AUTO_MIX_JOC = "AutoMixJoc";
    private static final String ENDPOINT_ID = "AudioEndpointId";
    private static final String ENDPOINT_REVISION = "AudioEndpointRevision";

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
    private UUID endpointId = UUID.randomUUID();
    private long endpointRevision;

    public SpeakerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.SPEAKER.get(), pos, blockState);
    }

    public void linkTo(BlockPos turntablePos) {
        this.linkedTurntablePos = turntablePos.immutable();
        endpointRevision++;
        syncServerEndpoint();
        markDirtyAndSync();
    }

    public void unlink() {
        if (level instanceof ServerLevel serverLevel) {
            AudioLinkIndex.removeSpeakerEndpoint(serverLevel, endpointId, worldPosition);
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

    public int getChannelIndex() {
        return channelIndex;
    }

    public void setChannelIndex(int v) {
        this.channelIndex = v;
        endpointRevision++;
        syncServerEndpoint();
        setChanged();
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float v) {
        this.volume = Math.clamp(v, 0.0f, 2.0f);
        endpointRevision++;
        syncServerEndpoint();
        setChanged();
    }

    public boolean isAutoMixJoc() {
        return autoMixJoc;
    }

    public void setAutoMixJoc(boolean v) {
        this.autoMixJoc = v;
        endpointRevision++;
        syncServerEndpoint();
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        LinkHelper.saveLinkToBE(output, linkedTurntablePos,
                LINK_KEY + "_has", LINK_KEY + "_x", LINK_KEY + "_y", LINK_KEY + "_z");
        output.putInt(CHANNEL_INDEX, channelIndex);
        output.putFloat(VOLUME, volume);
        output.putBoolean(AUTO_MIX_JOC, autoMixJoc);
        output.putString(ENDPOINT_ID, endpointId.toString());
        output.putLong(ENDPOINT_REVISION, endpointRevision);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.linkedTurntablePos = LinkHelper.loadLinkFromBE(input,
                LINK_KEY + "_has", LINK_KEY + "_x", LINK_KEY + "_y", LINK_KEY + "_z");
        this.channelIndex = input.getIntOr(CHANNEL_INDEX, CH_NONE);
        this.volume = input.getFloatOr(VOLUME, 1.0f);
        this.autoMixJoc = input.getBooleanOr(AUTO_MIX_JOC, false);
        UUID savedEndpointId = parseUuid(input.getStringOr(ENDPOINT_ID, ""));
        this.endpointId = savedEndpointId != null ? savedEndpointId : UUID.randomUUID();
        this.endpointRevision = Math.max(0L, input.getLongOr(ENDPOINT_REVISION, 0L));

        if (level != null && level.isClientSide()) {
            syncAudioOverride();
        } else if (level instanceof ServerLevel && linkedTurntablePos != null) {
            syncServerEndpoint();
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (linkedTurntablePos != null) {
            components.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            syncServerEndpoint();
        } else if (level != null && level.isClientSide()) {
            syncAudioOverride();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide()) {
            boolean bindingDestroyed = !level.getBlockState(worldPosition).is(ModBlocks.SPEAKER.get());
            if (bindingDestroyed) {
                ClientAudioOutputRegistry.clearMachineOverrideForSpeaker(worldPosition);
            }
        }
        super.setRemoved();
    }

    /**
     * 客户端：根据链接状态注册/清除音频 relay，每个音响有独立的 OpenAL 管线
     */
    private void syncAudioOverride() {
        ClientAudioOutputRegistry.clearMachineOverrideForSpeaker(worldPosition);
        if (linkedTurntablePos != null) {
            // 每次重新同步都创建新的 relay；clearMachineOverrideForSpeaker 会 cleanup 旧 relay
            SpeakerAudioRelay relay = new SpeakerAudioRelay();
            relay.setChannelIndex(channelIndex);
            relay.setUserVolume(volume);
            ClientAudioOutputRegistry.registerRelay(worldPosition, linkedTurntablePos, relay);
            ClientAudioOutputRegistry.updateRelayConfig(worldPosition, channelIndex, volume, autoMixJoc);
        }
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    private void syncServerEndpoint() {
        if (level instanceof ServerLevel serverLevel && linkedTurntablePos != null) {
            AudioLinkIndex.upsertSpeakerEndpoint(serverLevel, endpointId, worldPosition, linkedTurntablePos,
                    channelIndex, volume, autoMixJoc, 64.0F, endpointRevision);
        }
    }

    @Nullable
    private static UUID parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

}
