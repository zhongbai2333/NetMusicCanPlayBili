package com.zhongbai233.net_music_can_play_bili.client.sync;

/** Video-only EOF waits for the last queued frame and its display interval. */
public final class VideoOnlyEndPolicy {
    private VideoOnlyEndPolicy() { }
    public static boolean ready(boolean naturalEnd, boolean cancelled, boolean paused, boolean exactTransport,
            boolean hasFrame, boolean queueEmpty, long visualMillis, long lastFrameMillis, long frameMillis, long presentedMillis) {
        return naturalEnd && !cancelled && !paused && exactTransport && hasFrame && queueEmpty
                && visualMillis >= lastFrameMillis + Math.max(1L, frameMillis)
                && presentedMillis >= Math.max(1L, frameMillis);
    }
}
