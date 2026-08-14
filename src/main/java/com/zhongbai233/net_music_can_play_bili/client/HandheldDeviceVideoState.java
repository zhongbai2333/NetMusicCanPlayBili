package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.ResolvedVideoStream;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable state for one handheld device; lifecycle and frame queue have separate locks. */
final class HandheldDeviceVideoState {
    final HandheldReplacementGate replacementGate;
    final AtomicReference<HandheldVideoFrame> latestFrame = new AtomicReference<>();
    final AtomicLong frameSequence = new AtomicLong();
    final Object lifecycleLock = new Object();
    final Object frameQueueLock = new Object();
    final ArrayDeque<HandheldVideoFrame> frameQueue = new ArrayDeque<>();
    long intentGeneration;
    volatile HandheldPlaybackKey activeKey = HandheldPlaybackKey.EMPTY;
    volatile HandheldVideoSession activeSession;
    volatile HandheldPlaybackKey resolvingKey = HandheldPlaybackKey.EMPTY;
    CancellableTaskFuture<ResolvedVideoStream> resolveTask;
    volatile HandheldPlaybackKey failedKey = HandheldPlaybackKey.EMPTY;
    volatile HandheldPlaybackKey endedKey = HandheldPlaybackKey.EMPTY;
    volatile String statusText = "等待播放";
    volatile int sourceWidth;
    volatile int sourceHeight;
    volatile boolean audioOnly;
    volatile LyricRecord subtitleRecord;
    volatile String currentSubtitle = "";
    volatile long lastVisibleNanoTime = System.nanoTime();
    volatile long offscreenSinceNanoTime;
    volatile long rgbaConsumerUntilNanoTime;

    HandheldDeviceVideoState(HandheldReplacementGate replacementGate) {
        this.replacementGate = Objects.requireNonNull(replacementGate);
    }
}
