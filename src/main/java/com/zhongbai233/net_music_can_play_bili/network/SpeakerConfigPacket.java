package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SpeakerConfigPacket(BlockPos pos, int channelIndex, float volume,
        boolean autoMixJoc) implements CustomPacketPayload {

    public static final Type<SpeakerConfigPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("speaker_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerConfigPacket> STREAM_CODEC = StreamCodec
            .composite(
                    BlockPos.STREAM_CODEC, packet -> packet.pos(),
                    ByteBufCodecs.INT, p -> p.channelIndex(),
                    ByteBufCodecs.FLOAT, p -> p.volume(),
                    ByteBufCodecs.BOOL, p -> p.autoMixJoc(),
                    (BlockPos pos, Integer channelMask, Float volume, Boolean autoMixJoc) -> new SpeakerConfigPacket(
                            pos,
                            channelMask, volume, autoMixJoc));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SpeakerConfigPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0D) {
            return;
        }
        if (!(level.getBlockEntity(payload.pos()) instanceof SpeakerBlockEntity be)) {
            return;
        }
        be.setChannelIndex(payload.channelIndex());
        be.setVolume(payload.volume());
        be.setAutoMixJoc(payload.autoMixJoc());
        be.markDirtyAndSync();
    }
}
