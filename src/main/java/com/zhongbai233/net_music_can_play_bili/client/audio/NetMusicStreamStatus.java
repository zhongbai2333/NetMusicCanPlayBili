package com.zhongbai233.net_music_can_play_bili.client.audio;

import java.nio.ByteBuffer;

/** Narrow adapter for upstream's swallowed producer errors and ambiguous empty reads. */
public interface NetMusicStreamStatus {
    Throwable ncpbReadFailure();
    ByteBuffer ncpbReadBuffered(int size);
}
