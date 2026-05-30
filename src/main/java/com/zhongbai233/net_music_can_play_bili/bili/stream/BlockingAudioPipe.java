package com.zhongbai233.net_music_can_play_bili.bili.stream;

import java.io.IOException;
import java.io.InputStream;

public final class BlockingAudioPipe extends InputStream {
    private static final int DEFAULT_MAX_CAPACITY = 32 * 1024 * 1024;

    private final int initialCapacity;
    private final int maxCapacity;
    private byte[] buffer;
    private int readPos;
    private int writePos;
    private int size;
    private boolean readerClosed;
    private boolean writerClosed;

    public BlockingAudioPipe(int capacity) {
        this(capacity, DEFAULT_MAX_CAPACITY);
    }

    public BlockingAudioPipe(int capacity, int maxCapacity) {
        this.initialCapacity = Math.max(4096, capacity);
        this.maxCapacity = Math.max(this.initialCapacity, maxCapacity);
        this.buffer = new byte[this.initialCapacity];
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("buffer");
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }

        while (size == 0 && !writerClosed && !readerClosed) {
            waitForPipe();
        }
        if (size == 0 && writerClosed) {
            return -1;
        }
        if (readerClosed) {
            return -1;
        }

        int n = Math.min(len, size);
        int first = Math.min(n, buffer.length - readPos);
        System.arraycopy(buffer, readPos, b, off, first);
        int second = n - first;
        if (second > 0) {
            System.arraycopy(buffer, 0, b, off + first, second);
        }
        readPos = (readPos + n) % buffer.length;
        size -= n;
        if (size == 0 && buffer.length > initialCapacity * 8) {
            buffer = new byte[initialCapacity * 2];
            readPos = 0;
            writePos = 0;
        }
        notifyAll();
        return n;
    }

    public synchronized void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public synchronized void write(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("buffer");
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }

        int written = 0;
        while (written < len) {
            while (size == buffer.length && !readerClosed && buffer.length >= maxCapacity) {
                waitForPipe();
            }
            if (readerClosed) {
                throw new IOException("audio pipe reader closed");
            }
            if (size == buffer.length) {
                grow();
            }

            int available = buffer.length - size;
            int n = Math.min(len - written, available);
            int first = Math.min(n, buffer.length - writePos);
            System.arraycopy(b, off + written, buffer, writePos, first);
            int second = n - first;
            if (second > 0) {
                System.arraycopy(b, off + written + first, buffer, 0, second);
            }
            writePos = (writePos + n) % buffer.length;
            size += n;
            written += n;
            notifyAll();
        }
    }

    public synchronized void closeWriter() {
        writerClosed = true;
        notifyAll();
    }

    @Override
    public synchronized void close() {
        readerClosed = true;
        notifyAll();
    }

    private void grow() {
        int newCapacity = Math.min(buffer.length * 2, maxCapacity);
        if (newCapacity <= buffer.length) {
            return;
        }
        byte[] newBuffer = new byte[newCapacity];
        int first = Math.min(size, buffer.length - readPos);
        System.arraycopy(buffer, readPos, newBuffer, 0, first);
        if (size > first) {
            System.arraycopy(buffer, 0, newBuffer, first, size - first);
        }
        buffer = newBuffer;
        readPos = 0;
        writePos = size;
    }

    private void waitForPipe() throws IOException {
        try {
            wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("audio pipe interrupted", e);
        }
    }
}
