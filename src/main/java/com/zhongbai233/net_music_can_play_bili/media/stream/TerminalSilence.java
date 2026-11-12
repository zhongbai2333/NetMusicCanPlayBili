package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioDrainStatus;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** Silent SoundEngine carrier continues until real native media has drained. */
public final class TerminalSilence {
    private TerminalSilence() { }
    public static int read(byte[] bytes, int offset, int length, boolean cancelled, Throwable failure,
            boolean naturalInputEnd, AudioDrainStatus output) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (cancelled || output == AudioDrainStatus.CANCELLED) throw new PlaybackStreamCancelledException();
        if (failure != null || output == AudioDrainStatus.FAILED) throw new IOException("media decode failed", failure);
        if (naturalInputEnd && output == AudioDrainStatus.DRAINED) return -1;
        Arrays.fill(bytes, offset, offset + length, (byte) 0);
        return length;
    }
}
