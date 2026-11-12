package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.IOException;

/** A retired physical output is not a decoder EOF or a transport failure. */
public final class PlaybackStreamCancelledException extends IOException {
    public PlaybackStreamCancelledException() { super("media output cancelled"); }
}
