package com.zhongbai233.net_music_can_play_bili.client.sync;

/** Neutral handle for a synchronized client media sound instance. */
public interface ClientMediaSoundHandle {
    String sessionId();

    boolean headphoneRouted();

    boolean stopped();

    void discardWithoutFinishing();

    void setMediaVolume(float volume);
}