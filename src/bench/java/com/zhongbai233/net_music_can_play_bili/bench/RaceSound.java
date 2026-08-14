package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class RaceSound implements ClientMediaSoundHandle {
    private final String sessionId;
    private final AtomicBoolean transportFailed = new AtomicBoolean();
    private final AtomicBoolean discarded = new AtomicBoolean();
    private final AtomicInteger discards = new AtomicInteger();

    RaceSound(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public boolean headphoneRouted() {
        return false;
    }

    @Override
    public boolean stopped() {
        return transportFailed.get() || discarded.get();
    }

    @Override
    public void discardWithoutFinishing() {
        if (discarded.compareAndSet(false, true)) {
            discards.incrementAndGet();
        }
    }

    @Override
    public void setMediaVolume(float volume) {
    }

    void failTransport() {
        transportFailed.set(true);
    }

    int discards() {
        return discards.get();
    }
}
