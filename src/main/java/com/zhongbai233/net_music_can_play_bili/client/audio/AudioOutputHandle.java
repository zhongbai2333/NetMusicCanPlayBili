package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;

/** Stereo 与 Dolby OpenAL 输出共享的设备句柄契约。 */
public interface AudioOutputHandle extends AutoCloseable {
    default void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks,
            boolean followLocalPlayerFront) {
        tick(machinePos, listenerPos, targetRelativeTicks, followLocalPlayerFront, followLocalPlayerFront);
    }

    void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks,
            boolean followLocalPlayerFront, boolean muteWorldRelays);

    void setUserVolume(float volume);

    /** Mirrors Minecraft pause transitions into native sources owned outside SoundEngine. */
    default void setPaused(boolean paused) {
    }

    void addRelay(SpeakerAudioRelay relay);

    void removeRelay(SpeakerAudioRelay relay);

    /** 保持中控台越界后的主输出静音，不持有 relay 或 OpenAL 资源。 */
    default void setConsoleRouteSuppressed(boolean suppressed) {
    }

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
