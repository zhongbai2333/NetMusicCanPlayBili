package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
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

public record VideoProjectorConfigPacket(BlockPos pos, float yaw, float pitch, float scale,
        float height, float distanceX, float distanceZ, int preferredQuality) implements CustomPacketPayload {

    public static final Type<VideoProjectorConfigPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "video_projector_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VideoProjectorConfigPacket> STREAM_CODEC = StreamCodec
            .composite(
                    BlockPos.STREAM_CODEC, packet -> packet.pos(),
                    ByteBufCodecs.FLOAT, p -> p.yaw(),
                    ByteBufCodecs.FLOAT, p -> p.pitch(),
                    ByteBufCodecs.FLOAT, p -> p.scale(),
                    ByteBufCodecs.FLOAT, p -> p.height(),
                    ByteBufCodecs.FLOAT, p -> p.distanceX(),
                    ByteBufCodecs.FLOAT, p -> p.distanceZ(),
                    ByteBufCodecs.INT, p -> p.preferredQuality(),
                    (BlockPos pos, Float yaw, Float pitch, Float scale,
                            Float height, Float distanceX, Float distanceZ,
                            Integer preferredQuality) -> new VideoProjectorConfigPacket(
                                    pos, yaw, pitch, scale, height, distanceX, distanceZ, preferredQuality));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(VideoProjectorConfigPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0D) {
            return;
        }
        if (!(level.getBlockEntity(payload.pos()) instanceof VideoProjectorBlockEntity be)) {
            return;
        }
        be.setProjectionYaw(payload.yaw());
        be.setProjectionPitch(payload.pitch());
        be.setProjectionScale(payload.scale());
        be.setProjectionHeight(payload.height());
        be.setProjectionDistanceX(payload.distanceX());
        be.setProjectionDistanceZ(payload.distanceZ());
        be.setPreferredQuality(payload.preferredQuality());
        be.markDirtyAndSync();
    }
}