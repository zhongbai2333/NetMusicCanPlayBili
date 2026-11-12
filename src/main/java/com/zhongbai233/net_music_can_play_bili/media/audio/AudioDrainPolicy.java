package com.zhongbai233.net_music_can_play_bili.media.audio;

/** Shared production drain boundary, including frames polled by a decoder but not yet published. */
public final class AudioDrainPolicy {
    private AudioDrainPolicy() { }
    public static AudioDrainStatus status(boolean cancelled, boolean failed, boolean inputEnded,
            boolean paused, boolean outputValid, long outstandingDecode, int pendingSamples,
            int queuedFrames, boolean nativeMediaDrained) {
        if (cancelled) return AudioDrainStatus.CANCELLED;
        if (failed) return AudioDrainStatus.FAILED;
        if (!inputEnded) return AudioDrainStatus.RUNNING;
        return !paused && outputValid && outstandingDecode == 0L && pendingSamples == 0
                && queuedFrames == 0 && nativeMediaDrained ? AudioDrainStatus.DRAINED : AudioDrainStatus.DRAINING;
    }
    public static boolean mayStart(int queued, int prebuffer, boolean inputEnded) {
        return queued >= prebuffer || inputEnded && queued > 0;
    }
}
