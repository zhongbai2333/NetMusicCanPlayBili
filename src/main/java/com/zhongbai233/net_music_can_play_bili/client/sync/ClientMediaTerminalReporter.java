package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackEndReason;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackEndPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.UUID;

/** Keeps Minecraft packet linkage at the actual client reporting boundary. */
public final class ClientMediaTerminalReporter {
    private ClientMediaTerminalReporter() { }
    static void failure(ClientMediaSyncPayload payload, UUID source) {
        failure(source, payload.sessionId(), PlaybackSync.parseTerminalToken(payload.playUrl()));
    }
    public static void videoEnd(UUID source, String session, String token) {
        Minecraft client = Minecraft.getInstance();
        var active = ClientMediaPlaybackRegistry.get(source);
        if (client.player != null && active != null && active.durationMillis() <= 0L
                && ClientMediaTerminalRegistry.videoOnly(source, session)
                && ClientMediaTerminalRegistry.reporter(source, session, token, client.player.getUUID())) {
            ClientPacketDistributor.sendToServer(new MP4PlaybackEndPacket(source, session, token, PlaybackEndReason.NATURAL_EOF));
        }
    }
    public static void failure(UUID source, String session, String token) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && ClientMediaTerminalRegistry.reporter(source, session, token,
                client.player.getUUID())) ClientPacketDistributor.sendToServer(new MP4PlaybackEndPacket(
                        source, session, token, PlaybackEndReason.FAILED));
    }
}
