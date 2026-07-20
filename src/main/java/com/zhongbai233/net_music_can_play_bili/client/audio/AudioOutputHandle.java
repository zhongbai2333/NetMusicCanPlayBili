package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;

/** Stereo 与 Dolby OpenAL 输出共享的设备句柄契约。 */
public interface AudioOutputHandle extends AutoCloseable {
    void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks, boolean followLocalPlayerFront);

    void setUserVolume(float volume);

    void addRelay(SpeakerAudioRelay relay);

    void removeRelay(SpeakerAudioRelay relay);

    float audioLevel();

    long getPositionTicks();

    long getPositionMillis();

    long getFedPositionMillis();

    long getOutputDelayMillis();

    void hardStopOutput();

    void cleanup();

    @Override
    default void close() {
        cleanup();
    }
}