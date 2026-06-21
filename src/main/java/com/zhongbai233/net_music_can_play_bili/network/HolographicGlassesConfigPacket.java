package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record HolographicGlassesConfigPacket(int screenIndex, float distance, float offsetX, float offsetY,
        float height, float aspect, float roll) implements CustomPacketPayload {
    public static final Type<HolographicGlassesConfigPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("holographic_glasses_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HolographicGlassesConfigPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public HolographicGlassesConfigPacket decode(RegistryFriendlyByteBuf buf) {
            return new HolographicGlassesConfigPacket(buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, HolographicGlassesConfigPacket packet) {
            buf.writeInt(packet.screenIndex);
            buf.writeFloat(packet.distance);
            buf.writeFloat(packet.offsetX);
            buf.writeFloat(packet.offsetY);
            buf.writeFloat(packet.height);
            buf.writeFloat(packet.aspect);
            buf.writeFloat(packet.roll);
        }
    };

    public static HolographicGlassesConfigPacket fromConfig(int screenIndex,
            HolographicGlassesItem.ScreenConfig config) {
        return new HolographicGlassesConfigPacket(screenIndex, config.distance(), config.offsetX(), config.offsetY(),
                config.height(), config.aspect(), config.roll());
    }

    private HolographicGlassesItem.ScreenConfig toConfig() {
        return new HolographicGlassesItem.ScreenConfig(distance, offsetX, offsetY, height, aspect, roll);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(HolographicGlassesConfigPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!NetworkRateLimiter.allow(player.getUUID(), "holographic_glasses_config", 8)) {
            return;
        }
        ItemStack head = EquippedMediaItems.firstHolographicGlasses(player);
        if (!HolographicGlassesAbility.has(head)) {
            return;
        }
        List<HolographicGlassesItem.ScreenConfig> configs = new ArrayList<>(
                HolographicGlassesItem.readScreenConfigs(head));
        if (payload.screenIndex() < 0 || payload.screenIndex() >= configs.size()) {
            return;
        }
        configs.set(payload.screenIndex(), payload.toConfig());
        HolographicGlassesItem.writeScreenConfigs(head, configs);
        player.getInventory().setChanged();
    }
}
