package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ModernTurntableControlPacket(BlockPos pos, Action action, long targetMillis)
        implements CustomPacketPayload {
    public static final Type<ModernTurntableControlPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "modern_turntable_control"));

    private static final StreamCodec<RegistryFriendlyByteBuf, Action> ACTION_CODEC = new StreamCodec<>() {
        @Override
        public Action decode(RegistryFriendlyByteBuf buffer) {
            return Action.byId(buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, Action action) {
            buffer.writeVarInt(action.id());
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, ModernTurntableControlPacket> STREAM_CODEC = StreamCodec
            .composite(
                    BlockPos.STREAM_CODEC,
                    packet -> packet.pos(),
                    ACTION_CODEC,
                    packet -> packet.action(),
                    new StreamCodec<RegistryFriendlyByteBuf, Long>() {
                        @Override
                        public Long decode(RegistryFriendlyByteBuf buffer) {
                            return buffer.readVarLong();
                        }

                        @Override
                        public void encode(RegistryFriendlyByteBuf buffer, Long value) {
                            buffer.writeVarLong(value);
                        }
                    },
                    packet -> packet.targetMillis(),
                    (pos, action, targetMillis) -> new ModernTurntableControlPacket(pos, action, targetMillis));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ModernTurntableControlPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0D) {
            return;
        }
        if (!(level.getBlockEntity(payload.pos()) instanceof ModernTurntableBlockEntity turntable)) {
            return;
        }

        switch (payload.action()) {
            case REPLAY -> turntable.replayFromBeginning(player);
            case PAUSE -> turntable.pausePlayback(level);
            case START -> turntable.resumePlayback(player);
            case SEEK -> turntable.seekTo(level, payload.targetMillis());
        }
    }

    public enum Action {
        REPLAY,
        PAUSE,
        START,
        SEEK;

        public int id() {
            return ordinal();
        }

        public static Action byId(int id) {
            Action[] values = values();
            return id >= 0 && id < values.length ? values[id] : START;
        }
    }
}
