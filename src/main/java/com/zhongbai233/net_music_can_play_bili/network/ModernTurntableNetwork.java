package com.zhongbai233.net_music_can_play_bili.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModernTurntableNetwork {
    private ModernTurntableNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                ModernTurntableControlPacket.TYPE,
                ModernTurntableControlPacket.STREAM_CODEC,
                ModernTurntableControlPacket::handle);
        registrar.playToServer(
                LyricProjectorConfigPacket.TYPE,
                LyricProjectorConfigPacket.STREAM_CODEC,
                LyricProjectorConfigPacket::handle);
    }
}
