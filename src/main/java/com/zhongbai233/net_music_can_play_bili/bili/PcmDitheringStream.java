package com.zhongbai233.net_music_can_play_bili.bili;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * 将高位深 PCM（24-bit）通过 TPDF 抖动转换为 16-bit PCM 的输入流包装器
 */
final class PcmDitheringStream extends InputStream {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每个读取缓存块的大小（按源帧计），太大浪费内存，太小影响吞吐 */
    private static final int BLOCK_FRAMES = 4096;
    private static final int SRC_SAMPLE_BYTES = 3;
    private static final int DST_SAMPLE_BYTES = 2;
    private static final int QUANTIZATION_STEP = 1 << ((SRC_SAMPLE_BYTES - DST_SAMPLE_BYTES) * 8);
    private static final AudioFormat.Encoding PCM_SIGNED = AudioFormat.Encoding.PCM_SIGNED;

    private final InputStream source;
    private final int srcFrameSize; // 源每帧字节数
    private final int dstFrameSize; // 目标每帧字节数
    private final int channels;
    private final boolean srcBigEndian;
    private final boolean dstBigEndian;

    /** 源端缓存，一次读入一块，避免逐字节调用底层流 */
    private final byte[] srcBlock;
    /** 转换后的目标缓存 */
    private final byte[] dstBlock;
    /** 目标缓存有效字节数 */
    private int dstLen;
    /** 目标缓存读指针 */
    private int dstPos;
    /** 避免 read() 每次分配临时数组 */
    private final byte[] singleByte = new byte[1];

    private final RandomGenerator rng;
    private boolean closed;
    private boolean firstBlockLogged;

    /**
     * @param source    源 PCM 流（如 JFlac 解码后的 24-bit）
     * @param srcFormat 源格式（需是 24-bit signed PCM）
     * @param dstFormat 目标格式（需是 16-bit signed PCM，采样率/声道数需一致）
     */
    PcmDitheringStream(InputStream source, AudioFormat srcFormat, AudioFormat dstFormat) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(srcFormat, "srcFormat");
        Objects.requireNonNull(dstFormat, "dstFormat");

        if (!PCM_SIGNED.equals(srcFormat.getEncoding())) {
            throw new IllegalArgumentException("仅支持 signed PCM 源格式，got: " + srcFormat);
        }
        if (!PCM_SIGNED.equals(dstFormat.getEncoding())) {
            throw new IllegalArgumentException("仅支持 signed PCM 目标格式，got: " + dstFormat);
        }
        if (srcFormat.getSampleSizeInBits() != 24) {
            throw new IllegalArgumentException("仅支持 24-bit 源格式，got: " + srcFormat);
        }
        if (dstFormat.getSampleSizeInBits() != 16) {
            throw new IllegalArgumentException("仅支持 16-bit 目标格式，got: " + dstFormat);
        }
        if (srcFormat.getChannels() != dstFormat.getChannels()) {
            throw new IllegalArgumentException("源和目标声道数不一致");
        }
        if (Float.compare(srcFormat.getSampleRate(), dstFormat.getSampleRate()) != 0) {
            throw new IllegalArgumentException("源和目标采样率不一致");
        }

        this.channels = srcFormat.getChannels();
        this.srcFrameSize = SRC_SAMPLE_BYTES * channels;
        this.dstFrameSize = DST_SAMPLE_BYTES * channels;
        this.srcBigEndian = srcFormat.isBigEndian();
        this.dstBigEndian = dstFormat.isBigEndian();

        this.srcBlock = new byte[BLOCK_FRAMES * srcFrameSize];
        this.dstBlock = new byte[BLOCK_FRAMES * dstFrameSize];
        this.rng = RandomGeneratorFactory.getDefault().create();
        this.dstLen = 0;
        this.dstPos = 0;
    }

    @Override
    public int read() throws IOException {
        int n = read(singleByte, 0, 1);
        return n < 0 ? -1 : singleByte[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (closed)
            return -1;
        if (len == 0)
            return 0;

        int totalRead = 0;
        while (totalRead < len) {
            if (dstPos >= dstLen) {
                if (!convertNextBlock()) {
                    break;
                }
            }
            int n = Math.min(len - totalRead, dstLen - dstPos);
            System.arraycopy(dstBlock, dstPos, b, off + totalRead, n);
            dstPos += n;
            totalRead += n;
        }
        return totalRead == 0 ? -1 : totalRead;
    }

    /**
     * 从源流读一块 24-bit PCM，转换为 16-bit（带 TPDF 抖动），存入 dstBlock
     * 
     * @return true 如果成功转换了至少一帧，false 表示 EOF
     */
    private boolean convertNextBlock() throws IOException {
        int srcLen = readFully(source, srcBlock);

        if (!firstBlockLogged && srcLen > 0) {
            firstBlockLogged = true;
            LOGGER.debug("B站 TPDF 抖动转换已启动: 输入 {}bit/{}ch → 输出 16bit/{}ch, 首块 {} bytes",
                    SRC_SAMPLE_BYTES * 8, channels, channels, srcLen);
        }

        if (srcLen <= 0) {
            return false;
        }

        // 按完整帧处理，剩余不足一帧的字节丢弃
        int fullFrames = srcLen / srcFrameSize;
        if (fullFrames == 0) {
            return false;
        }

        dstPos = 0;
        dstLen = fullFrames * dstFrameSize;

        int srcOff = 0;
        int dstOff = 0;
        for (int f = 0; f < fullFrames; f++) {
            for (int ch = 0; ch < channels; ch++) {
                // 读取 24-bit 有符号样本
                int s24;
                if (srcBigEndian) {
                    s24 = ((srcBlock[srcOff] & 0xFF) << 16)
                            | ((srcBlock[srcOff + 1] & 0xFF) << 8)
                            | (srcBlock[srcOff + 2] & 0xFF);
                } else {
                    s24 = (srcBlock[srcOff] & 0xFF)
                            | ((srcBlock[srcOff + 1] & 0xFF) << 8)
                            | ((srcBlock[srcOff + 2] & 0xFF) << 16);
                }
                // 符号扩展 24→32
                if ((s24 & 0x800000) != 0) {
                    s24 |= 0xFF000000;
                }

                // TPDF 抖动：两个均匀随机数之和 → 三角分布
                // 峰值约为 ±1 LSB(16bit) = ±256（在 24-bit 刻度下）
                double tpdf = (rng.nextDouble() - rng.nextDouble()) * QUANTIZATION_STEP;

                double dithered = s24 + tpdf;
                // 缩放到 16-bit（除以 256）并四舍五入
                int s16 = (int) Math.round(dithered / QUANTIZATION_STEP);

                // 限幅到 16-bit 有符号范围
                if (s16 > 32767)
                    s16 = 32767;
                if (s16 < -32768)
                    s16 = -32768;

                // 写出 16-bit，端序跟随目标 AudioFormat
                if (dstBigEndian) {
                    dstBlock[dstOff] = (byte) ((s16 >> 8) & 0xFF);
                    dstBlock[dstOff + 1] = (byte) (s16 & 0xFF);
                } else {
                    dstBlock[dstOff] = (byte) (s16 & 0xFF);
                    dstBlock[dstOff + 1] = (byte) ((s16 >> 8) & 0xFF);
                }
                srcOff += SRC_SAMPLE_BYTES;
                dstOff += DST_SAMPLE_BYTES;
            }
        }
        return dstLen > 0;
    }

    /**
     * 尽可能填满缓冲区，返回实际读取的字节数
     */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0)
                break;
            if (n == 0) {
                int one = in.read();
                if (one < 0)
                    break;
                buf[total++] = (byte) one;
                continue;
            }
            total += n;
        }
        return total;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        source.close();
    }
}
