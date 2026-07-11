package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry.ActivePlayback;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/** Shared client-side synchronized media packet handler. */
public final class ClientMediaSyncHandler {
    private ClientMediaSyncHandler() {
    }

    public static void handleSync(ClientMediaSyncPayload payload, ClientMediaSyncPolicy policy) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || payload == null || policy == null) {
            return;
        }
        UUID sourceId = payload.sourceId() != null ? payload.sourceId() : payload.ownerId();
        policy.onSyncReceived(payload, sourceId);
        if (!payload.playing()) {
            policy.stop(sourceId);
            return;
        }
        if (!policy.canHear(sourceId, payload.headphoneRouted())) {
            policy.stop(sourceId);
            policy.onIgnoredCannotHear(payload, sourceId);
            return;
        }
        if (payload.playUrl().isBlank() || payload.sessionId().isBlank()) {
            return;
        }

        ClientMediaPlaybackRegistry.SourceLocation sourceLocation = ClientMediaPlaybackRegistry.SourceLocation
                .from(payload);
        ActivePlayback previous = ClientMediaPlaybackRegistry.get(sourceId);
        policy.beforeRegisterPlayback(payload, sourceId);
        if (previous != null && payload.sessionId().equals(previous.sessionId())) {
            ActivePlayback updated = previous.withServerElapsed(Math.max(0L, payload.elapsedMillis()),
                    Math.max(0L, payload.durationSeconds()) * 1000L)
                    .withSourceLocation(sourceLocation)
                    .withHeadphoneRouted(payload.headphoneRouted());
            ClientMediaPlaybackRegistry.put(sourceId, updated);
            policy.updateVolume(sourceId, payload.volumePerMille() / 1000.0F);
            if (policy.shouldRebuildSound(sourceId, payload)) {
                policy.onRebuildSound(payload, sourceId);
                policy.preparePlayback(payload, sourceId);
            }
            return;
        }

        ClientMediaPlaybackRegistry.put(sourceId, ClientMediaPlaybackRegistry.createFromSync(payload));
        policy.afterRegisterPlayback(payload, sourceId);
        policy.preparePlayback(payload, sourceId);
    }

    public static void handleTimeline(ClientMediaTimelinePayload payload, ClientMediaSyncPolicy policy) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || payload == null || payload.sourceId() == null
                || policy == null) {
            return;
        }
        if (!policy.canHear(payload.sourceId(), payload.headphoneRouted())) {
            policy.stop(payload.sourceId());
            return;
        }
        ActivePlayback previous = ClientMediaPlaybackRegistry.get(payload.sourceId());
        if (previous == null || payload.sessionId() == null || !payload.sessionId().equals(previous.sessionId())) {
            return;
        }
        ClientMediaPlaybackRegistry.put(payload.sourceId(),
                previous.withServerElapsed(Math.max(0L, payload.elapsedMillis()),
                        previous.durationMillis()).withHeadphoneRouted(payload.headphoneRouted()));
        policy.updateVolume(payload.sourceId(), payload.volumePerMille() / 1000.0F);
    }
}