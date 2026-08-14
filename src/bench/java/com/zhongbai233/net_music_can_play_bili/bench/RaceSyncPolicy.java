package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackSessions;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryPolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class RaceSyncPolicy implements ClientMediaSyncPolicy {
    private final List<RaceSound> sounds = new ArrayList<>();
    private final AtomicInteger prepares = new AtomicInteger();
    private final AtomicInteger rebuilds = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicInteger retryDispatches = new AtomicInteger();
    private final ClientMediaRetryPolicy retryPolicy = new ClientMediaRetryPolicy() {
        @Override
        public long retryDelayMillis() {
            return 0L;
        }

        @Override
        public void scheduleRetry(UUID deviceId, String sessionId,
                ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
            tryScheduleRetry(deviceId, sessionId, active, error);
        }

        @Override
        public boolean tryScheduleRetry(UUID deviceId, String sessionId,
                ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
            retryDispatches.incrementAndGet();
            return true;
        }
    };

    @Override
    public boolean canHear(UUID sourceId, boolean headphoneRouted) {
        return true;
    }

    @Override
    public void stop(UUID sourceId) {
        stops.incrementAndGet();
        ClientMediaPlaybackSessions.stop(sourceId, null);
    }

    @Override
    public void updateVolume(UUID sourceId, float volume) {
        ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
        if (sound != null) {
            sound.setMediaVolume(volume);
        }
    }

    @Override
    public boolean shouldRebuildSound(UUID sourceId, ClientMediaSyncPayload payload) {
        ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
        return sound == null || sound.stopped() || !payload.playbackSessionId().equals(sound.playbackSession());
    }

    @Override
    public void preparePlayback(ClientMediaSyncPayload payload, UUID sourceId) {
        RaceSound sound = new RaceSound(payload.sessionId());
        sounds.add(sound);
        prepares.incrementAndGet();
        if (!ClientMediaSoundRegistry.tryRegister(sourceId, payload.sessionId(), sound)) {
            throw new AssertionError("Deterministic sound registration was rejected: " + payload.sessionId());
        }
    }

    @Override
    public void onRebuildSound(ClientMediaSyncPayload payload, UUID sourceId) {
        rebuilds.incrementAndGet();
    }

    ClientMediaRetryPolicy retryPolicy() {
        return retryPolicy;
    }

    List<RaceSound> sounds() {
        return List.copyOf(sounds);
    }

    RaceSound latestSound() {
        return sounds.getLast();
    }

    int prepareCount() {
        return prepares.get();
    }

    int rebuildCount() {
        return rebuilds.get();
    }

    int stopCount() {
        return stops.get();
    }

    int retryDispatches() {
        return retryDispatches.get();
    }

    int totalDiscards() {
        return sounds.stream().mapToInt(RaceSound::discards).sum();
    }

    String summary() {
        return "prepares=" + prepareCount() + ", rebuilds=" + rebuildCount() + ", stops=" + stopCount()
                + ", retryDispatches=" + retryDispatches() + ", discards="
                + sounds.stream().map(RaceSound::discards).toList();
    }
}
