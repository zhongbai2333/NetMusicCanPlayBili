package com.zhongbai233.net_music_can_play_bili.media.codec;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VideoWorkerTerminalTest {
    @Test void nativeRuntimeFailureCannotBecomeNaturalEof() {
        var error = new IllegalStateException("native getNextOutputFrame");
        var result = VideoWorkerTerminal.execute(() -> { throw error; }, () -> false, () -> 2, 10);
        assertEquals(VideoWorkerTerminal.Reason.FAILED, result.reason());
        assertSame(error, result.failure().getCause());
    }
    @Test void nativeErrorIsVisibleToConsumerRatherThanSilentWorkerExit() {
        var error = new LinkageError("native linkage");
        var result = VideoWorkerTerminal.execute(() -> { throw error; }, () -> false, () -> 2, 10);
        assertEquals(VideoWorkerTerminal.Reason.FAILED, result.reason());
        assertSame(error, result.failure().getCause());
    }
    @Test void normalInputEofAndFrameSafetyCapAreDistinct() {
        var eof = VideoWorkerTerminal.execute(() -> {}, () -> false, () -> 2, 10);
        assertEquals(VideoWorkerTerminal.Reason.INPUT_EOF, eof.reason()); assertNull(eof.failure());
        var capped = VideoWorkerTerminal.execute(() -> {}, () -> false, () -> 2, 2);
        assertEquals(VideoWorkerTerminal.Reason.FRAME_LIMIT, capped.reason()); assertNotNull(capped.failure());
    }
    @Test void cancellationDoesNotBecomeFailureOrInputEof() {
        var result = VideoWorkerTerminal.execute(() -> { throw new IOException("closed input"); }, () -> true, () -> 2, 10);
        assertEquals(VideoWorkerTerminal.Reason.CANCELLED, result.reason()); assertNull(result.failure());
    }
}
