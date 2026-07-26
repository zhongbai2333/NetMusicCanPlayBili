package com.zhongbai233.net_music_can_play_bili.blockentity;

/**
 * 客户端音频输出层可读取的播放源方块契约。
 *
 * <p>
 * OpenAL 输出的音量与 pacing 策略只依赖这三个观测值，而不关心方块具体是
 * 唱片机还是直播机。
 * </p>
 */
public interface PlaybackAudioSource {
    boolean isPlaying();

    /** 0.0 ~ 1.0 的方块音量。 */
    float getVolume();

    /**
     * 服务端播放时间线（毫秒）。
     *
     * @return 无界媒体（如直播）返回 -1，表示输出层不做进度 pacing
     */
    long getPlaybackElapsedMillis(long gameTime);
}
