package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.soundlibs.net.sourceforge.jaad.SampleBuffer;
import com.github.tartaricacid.netmusic.soundlibs.net.sourceforge.jaad.aac.Decoder;
import com.github.tartaricacid.netmusic.soundlibs.org.jflac.sound.spi.Flac2PcmAudioInputStream;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.codec.Eac3NativeDecoder;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * B站 CDN 音频流处理器
 */
public class BiliHttpAudioStreamHandler implements IAudioStreamHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final int PIPE_BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;
    private static final int SKIP_BUFFER_SIZE = 8192;
    private static final int MIN_AAC_SAMPLE_SIZE = 20;

    @Override
    public boolean canHandle(URL url) {
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            return false;
        }

        String host = url.getHost();
        if (host == null)
            return false;
        host = host.toLowerCase();

        if (url.getPath() != null && url.getPath().endsWith(".m3u8")) {
            return false;
        }

        return host.contains("bilivideo")
                || host.contains("hdslb")
                || host.contains("mcdn")
                || host.contains("bilibili");
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        LOGGER.info("使用 B站流式音频处理器: {}://{}/...", url.getProtocol(), url.getHost());

        BlockingAudioPipe pipe = new BlockingAudioPipe(PIPE_BUFFER_SIZE);

        AtomicReference<AudioFormat> formatRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        AtomicReference<InputStream> bodyRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean isFlac = new AtomicBoolean(false);
        AtomicBoolean isDolby = new AtomicBoolean(false);
        AtomicReference<DolbyAudioHandler> dolbyRef = new AtomicReference<>();
        CountDownLatch formatReady = new CountDownLatch(1);

        Thread worker = new Thread(
                () -> streamDecode(url, pipe, bodyRef, formatRef, errorRef, formatReady, closed, isFlac, isDolby,
                        dolbyRef),
                "BiliTrueAudioStreamWorker");
        worker.setDaemon(true);
        worker.start();

        try {
            if (!formatReady.await(15, TimeUnit.SECONDS)) {
                closed.set(true);
                closeBody(bodyRef);
                worker.interrupt();
                throw new IOException("等待 B站音频格式超时");
            }
        } catch (InterruptedException e) {
            closed.set(true);
            closeBody(bodyRef);
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException("B站音频加载被中断", e);
        }

        Exception err = errorRef.get();
        if (err != null) {
            closed.set(true);
            closeBody(bodyRef);
            worker.interrupt();
            if (err instanceof IOException)
                throw (IOException) err;
            if (err instanceof UnsupportedAudioFileException)
                throw (UnsupportedAudioFileException) err;
            throw new IOException("B站音频处理失败", err);
        }

        AudioFormat format = formatRef.get();
        if (format == null) {
            closed.set(true);
            closeBody(bodyRef);
            worker.interrupt();
            throw new IOException("无法读取 B站音频格式");
        }

        LOGGER.info("B站音频流就绪: encoding={}, sampleRate={}Hz, channels={}, sampleSize={}bit",
                format.getEncoding(), format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits());

        if (isDolby.get()) {
            // Dolby Atmos: OpenAL 直接渲染空间音频，AudioInputStream 仅输出静音以保持音轨存活
            return new AudioInputStream(pipe, format, AudioSystem.NOT_SPECIFIED) {
                @Override
                public int read() throws IOException {
                    return 0;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (closed.get())
                        return -1;
                    if (len <= 0)
                        return 0;
                    int fill = Math.min(len, b.length - off);
                    Arrays.fill(b, off, off + fill, (byte) 0);
                    return fill;
                }

                @Override
                public void close() throws IOException {
                    LOGGER.info("B站 Dolby 音频流 close(), caller={}",
                            Thread.currentThread().getName());
                    closed.set(true);
                    worker.interrupt();
                    DolbyAudioHandler dolby = dolbyRef.get();
                    if (dolby != null) {
                        DolbyAudioRegistry.unregister(dolby);
                        dolby.cleanup();
                    }
                    super.close();
                }
            };
        } else if (isFlac.get()) {
            AudioInputStream flacDecoded = new Flac2PcmAudioInputStream(pipe, format, AudioSystem.NOT_SPECIFIED);
            if (format.getSampleSizeInBits() > 16) {
                AudioFormat fmt16 = new AudioFormat(format.getSampleRate(), 16,
                        format.getChannels(), true, false);
                LOGGER.info("B站 FLAC Hi-Res 启用 TPDF 抖动 24→16bit: {}Hz/{}ch",
                        format.getSampleRate(), format.getChannels());
                flacDecoded = new AudioInputStream(
                        new PcmDitheringStream(flacDecoded, format, fmt16),
                        fmt16, AudioSystem.NOT_SPECIFIED);
            }
            final AudioInputStream finalStream = flacDecoded;
            return new AudioInputStream(finalStream, finalStream.getFormat(), AudioSystem.NOT_SPECIFIED) {
                @Override
                public void close() throws IOException {
                    LOGGER.info("B站 FLAC 音频流 close(), caller={}",
                            Thread.currentThread().getName());
                    closed.set(true);
                    closeBody(bodyRef);
                    worker.interrupt();
                    finalStream.close();
                    super.close();
                }
            };
        } else {
            // AAC
            return new AudioInputStream(pipe, format, AudioSystem.NOT_SPECIFIED) {
                @Override
                public void close() throws IOException {
                    LOGGER.info("B站 AAC 音频流 close(), caller={}",
                            Thread.currentThread().getName());
                    closed.set(true);
                    closeBody(bodyRef);
                    worker.interrupt();
                    super.close();
                }
            };
        }
    }

    private static void streamDecode(URL url, BlockingAudioPipe output, AtomicReference<InputStream> bodyRef,
            AtomicReference<AudioFormat> formatRef, AtomicReference<Exception> errorRef, CountDownLatch formatReady,
            AtomicBoolean closed, AtomicBoolean isFlac, AtomicBoolean isDolby,
            AtomicReference<DolbyAudioHandler> dolbyRef) {
        Decoder aacDecoder = null;
        SampleBuffer sampleBuffer = null;
        int[] pendingSampleSizes = null;
        long decodedFrames = 0;
        int boxCount = 0;
        long totalBytes = 0;
        long dolbyMdatBoxes = 0;
        long dolbyMdatBytes = 0;
        long dolbyEc3Frames = 0;

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Origin", "https://www.bilibili.com")
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = BiliWbiSigner.HTTP.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("B站 CDN 返回 HTTP " + status);
            }

            InputStream in = response.body();
            bodyRef.set(in);

            BoxHeader box;
            while (!closed.get() && (box = readBoxHeader(in)) != null) {
                if (box.dataSize < 0) {
                    throw new IOException("暂不支持未知长度 MP4 box: " + box.type);
                }
                boxCount++;
                totalBytes += box.dataSize;

                switch (box.type) {
                    case "ftyp" -> skipFully(in, box.dataSize);
                    case "moov" -> {
                        byte[] moovData = readFully(in, box.dataSize);
                        Fmp4ToMp4Converter.ParseResult pr = Fmp4ToMp4Converter.parseMoov(moovData);
                        if ("ec-3".equals(pr.audioCodec)
                                && BiliConfig.dolbyEnabled
                                && Eac3NativeDecoder.isNativeAvailable()) {
                            // Dolby EC-3 → 空间音频管线
                            isDolby.set(true);
                            DolbyAudioHandler dolby = new DolbyAudioHandler();
                            dolbyRef.set(dolby);
                            DolbyAudioRegistry.register(dolby);
                            formatRef.set(new AudioFormat(48000, 16, 2, true, false));
                            formatReady.countDown();
                            LOGGER.info("B站 Dolby Atmos 空间音频初始化完成");
                        } else if (pr.flacDfLa != null) {
                            // FLAC
                            byte[] dfLa = pr.flacDfLa.clone();
                            isFlac.set(true);
                            output.write(new byte[] { 'f', 'L', 'a', 'C' });
                            output.write(Fmp4ToMp4Converter.dfLaToNativeFlacMetadata(dfLa));
                            AudioFormat format = Fmp4ToMp4Converter.flacDfLaToAudioFormat(dfLa);
                            if (format == null) {
                                throw new IOException("无法解析 B站 fMP4 FLAC 流信息");
                            }
                            formatRef.set(format);
                            formatReady.countDown();
                            LOGGER.info("B站 fMP4 FLAC(HIRES) 初始化完成: {}Hz, {}ch, {}bit",
                                    format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits());
                        } else if (pr.asc != null) {
                            // AAC
                            byte[] asc = pr.asc.clone();
                            aacDecoder = Decoder.create(asc);
                            AudioFormat format = aacDecoder.getAudioFormat();
                            sampleBuffer = new SampleBuffer(format);
                            sampleBuffer.setBigEndian(format.isBigEndian());
                            formatRef.set(format);
                            formatReady.countDown();
                            LOGGER.info("B站 fMP4 AAC 初始化完成: {}Hz, {}ch, {}bit, bigEndian={}",
                                    format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits(),
                                    format.isBigEndian());
                        } else {
                            // 未知编码
                            String codecs = Fmp4ToMp4Converter.listAudioCodecs(moovData);
                            LOGGER.warn("B站 fMP4 moov 中未找到支持的音频轨, 发现: {}", codecs);
                            throw new IOException("B站音频编码不支持 (仅支持 AAC/FLAC/EC-3), 发现: " + codecs);
                        }
                    }
                    case "moof" -> {
                        if (isFlac.get()) {
                            skipFully(in, box.dataSize);
                        } else {
                            byte[] moofData = readFully(in, box.dataSize);
                            pendingSampleSizes = Fmp4ToMp4Converter.extractSampleSizesFromMoof(moofData);
                            if (pendingSampleSizes.length == 0) {
                                LOGGER.warn("B站 fMP4 moof 未解析到 sample size，本片段将被跳过");
                            }
                        }
                    }
                    case "mdat" -> {
                        if (isDolby.get()) {
                            DolbyAudioHandler dolby = dolbyRef.get();
                            if (dolby != null) {
                                byte[] mdatData = readFully(in, box.dataSize);
                                // 扫描 EC-3 同步帧 (0x0B77) 并入队
                                int pos = 0;
                                int framesFound = 0;
                                while (pos < mdatData.length - 6) {
                                    if ((mdatData[pos] & 0xFF) == 0x0B && (mdatData[pos + 1] & 0xFF) == 0x77) {
                                        // 从头部解析帧大小
                                        if (pos + 4 <= mdatData.length) {
                                            int fszRaw = ((mdatData[pos + 2] & 0x07) << 8) | (mdatData[pos + 3] & 0xFF);
                                            int fsz = (fszRaw + 1) * 2;
                                            if (fsz >= 16 && pos + fsz <= mdatData.length) {
                                                byte[] ec3Frame = new byte[fsz];
                                                System.arraycopy(mdatData, pos, ec3Frame, 0, fsz);
                                                dolby.enqueueFrame(ec3Frame);
                                                framesFound++;
                                                pos += fsz;
                                                continue;
                                            }
                                        }
                                    }
                                    pos++;
                                }
                                if (framesFound > 0) {
                                    dolbyMdatBoxes++;
                                    dolbyMdatBytes += box.dataSize;
                                    dolbyEc3Frames += framesFound;
                                } else {
                                    LOGGER.warn("B站 Dolby mdat: {} bytes → 0 EC-3 frames (sync scan failed)",
                                            box.dataSize);
                                }
                            }
                        } else if (isFlac.get()) {
                            streamToPipe(in, box.dataSize, output, closed);
                        } else if (aacDecoder == null || sampleBuffer == null) {
                            LOGGER.warn("B站 fMP4 在 moov 前遇到 mdat，跳过 {} bytes", box.dataSize);
                            skipFully(in, box.dataSize);
                        } else if (pendingSampleSizes == null || pendingSampleSizes.length == 0) {
                            LOGGER.warn("B站 fMP4 mdat 缺少 sample size，跳过 {} bytes", box.dataSize);
                            skipFully(in, box.dataSize);
                        } else {
                            decodedFrames += decodeMdat(in, box.dataSize, pendingSampleSizes, aacDecoder, sampleBuffer,
                                    output, closed);
                            pendingSampleSizes = null;
                        }
                    }
                    default -> skipFully(in, box.dataSize);
                }
            }

            String dolbyInfo = "";
            if (isDolby.get()) {
                DolbyAudioHandler dh = dolbyRef.get();
                dolbyInfo = ", Dolbymdat=" + dolbyMdatBoxes
                        + ", Dolbybytes=" + dolbyMdatBytes
                        + ", Dolbyframes=" + dolbyEc3Frames
                        + (dh != null ? ", Dolby队列=" + dh.queuedFrames() + "帧" : "");
            }
            LOGGER.info("B站音频流结束: {} boxes, {} bytes, 共解码 {} 帧{}",
                    boxCount, totalBytes, decodedFrames, dolbyInfo);
        } catch (EOFException e) {
            LOGGER.info("B站音频流 EOF: {} boxes, {} bytes, 共解码 {} 帧", boxCount, totalBytes, decodedFrames);
        } catch (IOException e) {
            if (closed.get() || isStreamEndException(e)) {
                String dolbyInfo2 = "";
                if (isDolby.get()) {
                    DolbyAudioHandler dh = dolbyRef.get();
                    dolbyInfo2 = ", Dolbymdat=" + dolbyMdatBoxes
                            + ", Dolbybytes=" + dolbyMdatBytes
                            + ", Dolbyframes=" + dolbyEc3Frames
                            + (dh != null ? ", Dolby队列=" + dh.queuedFrames() + "帧" : "");
                }
                LOGGER.info("B站音频流中断: {} boxes, {} bytes, closed={}, msg={}, 共解码 {} 帧{}",
                        boxCount, totalBytes, closed.get(), e.getMessage(), decodedFrames, dolbyInfo2);
                LOGGER.info("B站音频流中断堆栈", e);
            } else {
                LOGGER.error("B站音频流 IO 失败: {} boxes, {} bytes", boxCount, totalBytes, e);
                errorRef.set(e);
            }
        } catch (Exception e) {
            if (!closed.get()) {
                LOGGER.error("B站音频流解码失败", e);
                errorRef.set(e);
            }
        } finally {
            formatReady.countDown();
            closeBody(bodyRef);
            output.closeWriter();
        }
    }

    private static long decodeMdat(InputStream in, long dataSize, int[] sampleSizes, Decoder decoder,
            SampleBuffer sampleBuffer, BlockingAudioPipe output, AtomicBoolean closed) throws IOException {
        long remaining = dataSize;
        long decoded = 0;

        for (int sampleSize : sampleSizes) {
            if (closed.get() || remaining <= 0) {
                break;
            }
            if (sampleSize <= 0) {
                continue;
            }
            if (sampleSize > remaining) {
                LOGGER.warn("B站 fMP4 sample size 超出 mdat 剩余长度: sample={}, remaining={}", sampleSize, remaining);
                if (remaining >= MIN_AAC_SAMPLE_SIZE) {
                    byte[] partial = readFully(in, remaining);
                    try {
                        decoder.decodeFrame(partial, sampleBuffer);
                        byte[] pcm = sampleBuffer.getData();
                        if (pcm != null && pcm.length > 0) {
                            output.write(pcm);
                            decoded++;
                        }
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        LOGGER.debug("B站 fMP4 尾部截断帧无法解码(size={}): {}", remaining, e.getMessage());
                    }
                } else {
                    skipFully(in, remaining);
                }
                return decoded;
            }

            byte[] sample = readFully(in, sampleSize);
            remaining -= sampleSize;
            if (sampleSize < MIN_AAC_SAMPLE_SIZE) {
                continue;
            }

            try {
                decoder.decodeFrame(sample, sampleBuffer);
                byte[] pcm = sampleBuffer.getData();
                if (pcm != null && pcm.length > 0) {
                    output.write(pcm);
                    decoded++;
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                LOGGER.debug("跳过无法解码的 B站 AAC frame(size={}): {}", sampleSize, e.getMessage());
            }
        }

        if (remaining > 0) {
            skipFully(in, remaining);
        }
        return decoded;
    }

    private static BoxHeader readBoxHeader(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }

        byte[] header = new byte[8];
        header[0] = (byte) first;
        readFullyInto(in, header, 1, 7);

        long size = readUInt32(header, 0);
        String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
        int headerSize = 8;
        if (size == 1) {
            byte[] ext = readFully(in, 8);
            size = readUInt64(ext, 0);
            headerSize = 16;
        } else if (size == 0) {
            return new BoxHeader(type, -1);
        }

        long dataSize = size - headerSize;
        if (dataSize < 0) {
            throw new IOException("非法 MP4 box size: type=" + type + ", size=" + size);
        }
        return new BoxHeader(type, dataSize);
    }

    private static byte[] readFully(InputStream in, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new IOException("MP4 box 过大，无法一次读取: " + length);
        }
        byte[] data = new byte[(int) length];
        readFullyInto(in, data, 0, data.length);
        return data;
    }

    private static void readFullyInto(InputStream in, byte[] data, int offset, int length) throws IOException {
        int readTotal = 0;
        while (readTotal < length) {
            int n = in.read(data, offset + readTotal, length - readTotal);
            if (n < 0) {
                throw new EOFException("读取 MP4 box 时提前到达 EOF");
            }
            readTotal += n;
        }
    }

    private static void skipFully(InputStream in, long length) throws IOException {
        byte[] buffer = new byte[SKIP_BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            int toRead = (int) Math.min(buffer.length, remaining);
            int n = in.read(buffer, 0, toRead);
            if (n < 0) {
                throw new EOFException("跳过 MP4 box 时提前到达 EOF");
            }
            remaining -= n;
        }
    }

    private static void streamToPipe(InputStream in, long length, BlockingAudioPipe pipe, AtomicBoolean closed)
            throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0 && !closed.get()) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int n = in.read(buffer, 0, toRead);
            if (n < 0) {
                throw new EOFException("流式写入管道时提前到达 EOF");
            }
            pipe.write(buffer, 0, n);
            remaining -= n;
        }
    }

    private static long readUInt32(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF) << 24
                | ((long) data[offset + 1] & 0xFF) << 16
                | ((long) data[offset + 2] & 0xFF) << 8
                | ((long) data[offset + 3] & 0xFF);
    }

    private static long readUInt64(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    private static void closeBody(AtomicReference<InputStream> bodyRef) {
        InputStream body = bodyRef.getAndSet(null);
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isStreamEndException(IOException e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("closed") || msg.contains("EOF") || msg.contains("Stream Closed"))) {
            return true;
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof EOFException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public int getPriority() {
        return 100;
    }

    // 专门给 NetMusicAudioStream 用的阻塞音频管道，自动扩容
    private static final class BlockingAudioPipe extends InputStream {
        private static final int MAX_CAPACITY = 512 * 1024 * 1024;
        private byte[] buffer;
        private int readPos;
        private int writePos;
        private int size;
        private boolean readerClosed;
        private boolean writerClosed;

        private BlockingAudioPipe(int capacity) {
            this.buffer = new byte[Math.max(4096, capacity)];
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
            if (size == 0 && buffer.length > PIPE_BUFFER_SIZE * 8) {
                buffer = new byte[PIPE_BUFFER_SIZE * 2];
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
                while (size == buffer.length && !readerClosed && buffer.length >= MAX_CAPACITY) {
                    waitForPipe();
                }
                if (readerClosed) {
                    throw new IOException("Audio pipe reader closed");
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

        private void grow() {
            int newCapacity = Math.min(buffer.length * 2, MAX_CAPACITY);
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

        public synchronized void closeWriter() {
            writerClosed = true;
            notifyAll();
        }

        @Override
        public synchronized void close() {
            readerClosed = true;
            notifyAll();
        }

        private void waitForPipe() throws IOException {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Audio pipe interrupted", e);
            }
        }
    }

    private record BoxHeader(String type, long dataSize) {
    }
}
