package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker.Category;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/** Reuses native NV12 output buffers while preserving explicit memory accounting. */
final class NativeNv12BufferPool {
    private final int maxIdle;
    private final ArrayDeque<NativeNv12Buffer> idle = new ArrayDeque<>();
    private boolean retired;

    NativeNv12BufferPool(int maxIdle) {
        this.maxIdle = Math.max(1, maxIdle);
    }

    synchronized NativeNv12Buffer acquire(int byteCount) {
        if (retired) {
            return null;
        }
        NativeNv12Buffer selected = null;
        while (!idle.isEmpty()) {
            NativeNv12Buffer candidate = idle.removeFirst();
            if (candidate.buffer().capacity() >= byteCount) {
                selected = candidate;
                break;
            }
            MemoryResourceTracker.freed(Category.DECODER_NV12, candidate.buffer().capacity());
            MemoryUtil.memFree(candidate.buffer());
        }
        if (selected == null) {
            ByteBuffer buffer = MemoryUtil.memAlloc(byteCount).order(ByteOrder.nativeOrder());
            MemoryResourceTracker.allocated(Category.DECODER_NV12, buffer.capacity());
            selected = new NativeNv12Buffer(buffer);
        }
        return selected;
    }

    synchronized void release(NativeNv12Buffer buffer) {
        if (retired || idle.size() >= maxIdle) {
            MemoryResourceTracker.freed(Category.DECODER_NV12, buffer.buffer().capacity());
            MemoryUtil.memFree(buffer.buffer());
        } else {
            idle.addLast(buffer);
        }
    }

    synchronized void retire() {
        retired = true;
        while (!idle.isEmpty()) {
            ByteBuffer buffer = idle.removeFirst().buffer();
            MemoryResourceTracker.freed(Category.DECODER_NV12, buffer.capacity());
            MemoryUtil.memFree(buffer);
        }
    }

    record NativeNv12Buffer(ByteBuffer buffer) {
    }
}
