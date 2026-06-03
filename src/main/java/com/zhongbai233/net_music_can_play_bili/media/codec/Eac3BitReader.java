package com.zhongbai233.net_music_can_play_bili.media.codec;

/**
 * E-AC-3 比特流位级读取器
 */
public class Eac3BitReader {

    private final byte[] data;
    private int pos;
    private final int limit;

    public Eac3BitReader(byte[] data) {
        this.data = data;
        this.pos = 0;
        this.limit = data.length * 8;
    }

    /** 读取 {@code count} 位，返回无符号整数 */
    public int read(int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            if (pos >= limit) {
                throw new IndexOutOfBoundsException(
                        "EOF at bit " + pos + "/" + limit);
            }
            int byteVal = data[pos >> 3] & 0xFF;
            int bit = (byteVal >> (7 - (pos & 7))) & 1;
            value = (value << 1) | bit;
            pos++;
        }
        return value;
    }

    /** 读取单个比特，返回布尔值 */
    public boolean readBit() {
        return read(1) != 0;
    }

    /** 跳过 {@code count} 位 */
    public void skip(int count) {
        if (pos + count > limit) {
            throw new IndexOutOfBoundsException(
                    "Skip past EOF: " + pos + "+" + count + " > " + limit);
        }
        pos += count;
    }

    /** 读取变长编码，每组 {@code bits} 个数据位 */
    public int variableBits(int bits) {
        int value = 0;
        while (true) {
            value += read(bits);
            if (!readBit()) {
                return value;
            }
            value = (value + 1) << bits;
        }
    }

    /** 当前比特位置 */
    public int position() {
        return pos;
    }

    /** 设置比特位置（用于缓冲区内的跳转） */
    public void position(int newPos) {
        if (newPos < 0 || newPos > limit) {
            throw new IndexOutOfBoundsException("position out of range: " + newPos);
        }
        this.pos = newPos;
    }

    /** 可用比特总数 */
    public int limit() {
        return limit;
    }

    /** 剩余比特数 */
    public int remaining() {
        return limit - pos;
    }

    /** 读取 {@code count} 位，返回有符号整数（补码） */
    public int readSigned(int count) {
        int value = read(count);
        int signBit = 1 << (count - 1);
        if ((value & signBit) != 0) {
            value -= (1 << count);
        }
        return value;
    }
}
