package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.nio.ByteBuffer;
import java.util.Queue;

/** Reads partial terminal buffers; an asynchronous underrun is silence, never EOF. */
public final class BufferedAudioReadBoundary {
    private BufferedAudioReadBoundary() { }
    public static ByteBuffer readUpstream(Queue<ByteBuffer> queue, int size, int frameSize,
            java.util.function.BooleanSupplier inputEnded, java.util.function.BooleanSupplier loading, Runnable load) {
        if (!inputEnded.getAsBoolean()) load.run();
        return read(queue, size, frameSize, inputEnded.getAsBoolean() && !loading.getAsBoolean());
    }
    public static ByteBuffer read(Queue<ByteBuffer> queue, int size, int frameSize, boolean producerFinished) {
        int aligned = size - size % Math.max(1, frameSize);
        if (aligned <= 0) return ByteBuffer.allocateDirect(0);
        ByteBuffer output = ByteBuffer.allocateDirect(aligned);
        while (output.hasRemaining()) {
            ByteBuffer head = queue.peek();
            if (head == null) break;
            int count = Math.min(output.remaining(), head.remaining());
            int originalLimit = head.limit();
            head.limit(head.position() + count);
            output.put(head);
            head.limit(originalLimit);
            if (!head.hasRemaining()) queue.poll();
        }
        if (output.position() == 0) {
            if (producerFinished) return null;
            output.position(aligned); // temporary starvation keeps the carrier alive
        }
        output.flip();
        return output;
    }
}
