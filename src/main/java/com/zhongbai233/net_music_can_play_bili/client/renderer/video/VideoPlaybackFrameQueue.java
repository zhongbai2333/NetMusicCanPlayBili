package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

/** Bounded decoded-frame queue with latest-due-frame selection and drop accounting. */
final class VideoPlaybackFrameQueue {
    private final int capacity;
    private final ArrayDeque<DecodedVideoFrame> frames = new ArrayDeque<>();
    private long droppedFrames;

    VideoPlaybackFrameQueue(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    synchronized boolean offer(DecodedVideoFrame frame, BooleanSupplier shouldContinue) throws InterruptedException {
        while (frames.size() >= capacity && shouldContinue.getAsBoolean()) {
            wait(5L);
        }
        if (!shouldContinue.getAsBoolean()) {
            return false;
        }
        frames.addLast(frame);
        notifyAll();
        return true;
    }

    synchronized DecodedVideoFrame pollBestFrame(long playbackNanos, long earlyToleranceNanos) {
        DecodedVideoFrame best = null;
        long visibleUntil = playbackNanos + Math.max(0L, earlyToleranceNanos);
        while (!frames.isEmpty()) {
            DecodedVideoFrame next = frames.peekFirst();
            if (next.ptsNanos() > visibleUntil) {
                break;
            }
            DecodedVideoFrame polled = frames.pollFirst();
            if (best != null) {
                best.close();
                droppedFrames++;
            }
            best = polled;
        }
        if (best != null) {
            notifyAll();
        }
        return best;
    }

    synchronized void clear() {
        for (DecodedVideoFrame frame : frames) {
            frame.close();
        }
        frames.clear();
        notifyAll();
    }

    synchronized long drainDroppedFrames() {
        long value = droppedFrames;
        droppedFrames = 0L;
        return value;
    }

    synchronized boolean isFull() {
        return frames.size() >= capacity;
    }

    synchronized boolean isEmpty() {
        return frames.isEmpty();
    }

    synchronized int size() {
        return frames.size();
    }

    int capacity() {
        return capacity;
    }

    synchronized long latestPtsNanos() {
        DecodedVideoFrame latest = frames.peekLast();
        return latest != null ? latest.ptsNanos() : -1L;
    }
}

record DecodedVideoFrame(long frameIndex, long ptsNanos, VideoBillboardPreview.DecodedFrame frame) {
    void close() {
        frame.close();
    }
}
