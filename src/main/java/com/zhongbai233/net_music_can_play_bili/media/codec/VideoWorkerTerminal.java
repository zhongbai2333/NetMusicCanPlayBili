package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Production worker boundary: only a successful, uncapped input is natural EOF. */
final class VideoWorkerTerminal {
    enum Reason { INPUT_EOF, FRAME_LIMIT, CANCELLED, FAILED }
    record Result(Reason reason, IOException failure) { }
    @FunctionalInterface interface DecodeTask { void run() throws IOException; }
    static Result execute(DecodeTask task, BooleanSupplier cancelled, IntSupplier frames, int limit) {
        try {
            task.run();
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                return new Result(Reason.CANCELLED, null);
            if (frames.getAsInt() >= limit)
                return new Result(Reason.FRAME_LIMIT, new IOException("视频达到帧数安全上限，未确认完整媒体 EOF"));
            return new Result(Reason.INPUT_EOF, null);
        } catch (Throwable error) {
            if (cancelled.getAsBoolean()) return new Result(Reason.CANCELLED, null);
            return new Result(Reason.FAILED, error instanceof IOException io ? io
                    : new IOException("Native video decode failed", error));
        }
    }
    private VideoWorkerTerminal() { }
}
