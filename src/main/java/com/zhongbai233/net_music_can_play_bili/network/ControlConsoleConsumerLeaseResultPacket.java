package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 服务端对消费资格的授权结果。 */
public record ControlConsoleConsumerLeaseResultPacket(BlockPos pos, Status status, UUID leaseId,
    long consumerGeneration, List<AudioOutputZone> audioOutputZones)
        implements CustomPacketPayload {
    private static final int MAX_AUDIO_OUTPUTS = 4096;
    public static final Type<ControlConsoleConsumerLeaseResultPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("control_console_consumer_lease_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlConsoleConsumerLeaseResultPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ControlConsoleConsumerLeaseResultPacket decode(RegistryFriendlyByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    Status status = Status.fromId(buf.readVarInt());
                    UUID leaseId = buf.readBoolean() ? buf.readUUID() : null;
                    long generation = buf.readVarLong();
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_AUDIO_OUTPUTS) {
                        throw new IllegalArgumentException("invalid console audio output zone count: " + count);
                    }
                    List<AudioOutputZone> zones = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        zones.add(AudioOutputZone.decode(buf));
                    }
                    return new ControlConsoleConsumerLeaseResultPacket(pos, status, leaseId, generation, zones);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ControlConsoleConsumerLeaseResultPacket packet) {
                    BlockPos.STREAM_CODEC.encode(buf, packet.pos());
                    buf.writeVarInt(packet.status().id);
                    buf.writeBoolean(packet.leaseId() != null);
                    if (packet.leaseId() != null) {
                        buf.writeUUID(packet.leaseId());
                    }
                    buf.writeVarLong(packet.consumerGeneration());
                    buf.writeVarInt(packet.audioOutputZones().size());
                    packet.audioOutputZones().forEach(zone -> zone.encode(buf));
                }
            };

    public ControlConsoleConsumerLeaseResultPacket {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        status = Objects.requireNonNull(status, "status");
        if (status == Status.GRANTED && leaseId == null) {
            throw new IllegalArgumentException("granted consumer lease requires leaseId");
        }
        audioOutputZones = List.copyOf(Objects.requireNonNull(audioOutputZones, "audioOutputZones"));
        if (audioOutputZones.size() > MAX_AUDIO_OUTPUTS) {
            throw new IllegalArgumentException("too many console audio output zones");
        }
    }

    public ControlConsoleConsumerLeaseResultPacket(BlockPos pos, Status status, UUID leaseId,
            long consumerGeneration) {
        this(pos, status, leaseId, consumerGeneration, List.of());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ControlConsoleConsumerLeaseResultPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer
                .acceptConsumerLeaseResult(payload));
    }

    public enum Status {
        GRANTED(0), OUTSIDE(1), REJECTED(2);

        private final int id;

        Status(int id) {
            this.id = id;
        }

        private static Status fromId(int id) {
            for (Status status : values()) {
                if (status.id == id) {
                    return status;
                }
            }
            throw new IllegalArgumentException("unknown consumer lease result status: " + id);
        }
    }

    public record AudioOutputZone(BlockPos outputKey, AreaAudioZone zone) {
        public AudioOutputZone {
            outputKey = Objects.requireNonNull(outputKey, "outputKey").immutable();
            zone = Objects.requireNonNull(zone, "zone");
        }

        private static AudioOutputZone decode(RegistryFriendlyByteBuf buffer) {
            return new AudioOutputZone(BlockPos.STREAM_CODEC.decode(buffer), AreaAudioZoneCodec.decode(buffer));
        }

        private void encode(RegistryFriendlyByteBuf buffer) {
            BlockPos.STREAM_CODEC.encode(buffer, outputKey);
            AreaAudioZoneCodec.encode(buffer, zone);
        }
    }
}
