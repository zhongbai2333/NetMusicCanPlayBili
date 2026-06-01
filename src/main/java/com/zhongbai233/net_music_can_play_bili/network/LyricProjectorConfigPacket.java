package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LyricProjectorConfigPacket(BlockPos pos, float yaw, float pitch, float scale,
        float height, float distance, int mode, boolean allowAi) implements CustomPacketPayload {

    public static final Type<LyricProjectorConfigPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "lyric_projector_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LyricProjectorConfigPacket> STREAM_CODEC = StreamCodec
            .composite(
                    BlockPos.STREAM_CODEC, LyricProjectorConfigPacket::pos,
                    ByteBufCodecs.FLOAT, p -> p.yaw(),
                    ByteBufCodecs.FLOAT, p -> p.pitch(),
                    ByteBufCodecs.FLOAT, p -> p.scale(),
                    ByteBufCodecs.FLOAT, p -> p.height(),
                    ByteBufCodecs.FLOAT, p -> p.distance(),
                    ByteBufCodecs.INT, p -> p.mode(),
                    ByteBufCodecs.BOOL, p -> p.allowAi(),
                    (BlockPos pos, Float yaw, Float pitch, Float scale,
                            Float height, Float distance, Integer mode,
                            Boolean allowAi) -> new LyricProjectorConfigPacket(pos, yaw,
                                    pitch, scale, height, distance, mode, allowAi));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LyricProjectorConfigPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0D) {
            return;
        }
        if (!(level.getBlockEntity(payload.pos()) instanceof LyricProjectorBlockEntity be)) {
            return;
        }
        be.setProjectionYaw(payload.yaw());
        be.setProjectionPitch(payload.pitch());
        be.setProjectionScale(payload.scale());
        be.setProjectionHeight(payload.height());
        be.setProjectionDistance(payload.distance());
        be.setProjectionMode(payload.mode());
        be.setAllowAi(payload.allowAi());
        be.markDirtyAndSync();
    }
}
