package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 临时文件字节缓冲池
 * <p>
 * 使用独立的读写文件描述符，消除 seek 竞争：
 * <ul>
 * <li>写端使用 "rw" 模式 {@link RandomAccessFile}，追加写入</li>
 * <li>读端使用 "r" 模式 {@link RandomAccessFile}，随机位置读取</li>
 * </ul>
 * synchronized 仅保护状态变量，I/O 操作在锁外执行
 * 大幅降低下载线程与解析线程之间的锁竞争
 */
public final class TempFileByteSpool implements Closeable {
    private final Path path;
    private final RandomAccessFile readFile;
    private final RandomAccessFile writeFile;
    private long cachedLength;
    private boolean complete;
    private boolean closed;
    private IOException failure;

    public TempFileByteSpool(String prefix) throws IOException {
        this.path = Files.createTempFile(prefix, ".spool");
        path.toFile().deleteOnExit();
        this.readFile = new RandomAccessFile(path.toFile(), "r");
        this.writeFile = new RandomAccessFile(path.toFile(), "rw");
    }

    public void write(byte[] buffer, int offset, int length) throws IOException {
        if (length <= 0) {
            return;
        }
        long pos;
        synchronized (this) {
            ensureOpen();
            pos = cachedLength;
        }
        writeFile.seek(pos);
        writeFile.write(buffer, offset, length);
        synchronized (this) {
            cachedLength = pos + length;
            notifyAll();
        }
    }

    public int read(long position, byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        int available;
        synchronized (this) {
            while (!closed && failure == null && position >= cachedLength && !complete) {
                waitForData();
            }
            if (failure != null) {
                throw failure;
            }
            if (closed) {
                return -1;
            }
            if (position >= cachedLength) {
                return -1;
            }
            available = (int) Math.min(length, cachedLength - position);
        }
        readFile.seek(position);
        int total = 0;
        while (total < available) {
            int n = readFile.read(buffer, offset + total, available - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        if (total == 0 && !complete) {
            throw new EOFException("temp spool ended before cached length");
        }
        return total > 0 ? total : -1;
    }

    public synchronized long cachedLength() {
        return cachedLength;
    }

    public synchronized boolean isComplete() {
        return complete;
    }

    public synchronized void complete() {
        complete = true;
        notifyAll();
    }

    public synchronized void fail(IOException exception) {
        if (failure == null) {
            failure = exception;
        }
        complete = true;
        notifyAll();
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        complete = true;
        notifyAll();
        IOException closeError = null;
        try {
            readFile.close();
        } catch (IOException e) {
            closeError = e;
        }
        try {
            writeFile.close();
        } catch (IOException e) {
            if (closeError != null) {
                closeError.addSuppressed(e);
            } else {
                closeError = e;
            }
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            if (closeError != null) {
                closeError.addSuppressed(e);
            } else {
                closeError = e;
            }
        }
        if (closeError != null) {
            throw closeError;
        }
    }

    /** 启动时清理上次异常退出残留的 .spool 临时文件 */
    public static void cleanupOrphanedSpoolFiles() {
        try {
            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
            if (!Files.isDirectory(tmpDir)) {
                return;
            }
            try (var files = Files.newDirectoryStream(tmpDir, "http-prefetch-*.spool")) {
                for (Path file : files) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("temp spool is closed");
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void waitForData() throws IOException {
        try {
            wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for temp spool", e);
        }
    }
}
