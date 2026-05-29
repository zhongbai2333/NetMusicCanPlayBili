package com.zhongbai233.net_music_can_play_bili.bili.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * OAMD（对象音频元数据）解析器和 EMDF（可扩展元数据格式）提取器
 */
public class Eac3AtmosParser {

    private Eac3AtmosParser() {
    }

    // ═══════════════════════════════════════════════════════
    // EMDF 提取
    // ═══════════════════════════════════════════════════════

    /**
     * 在原始 E-AC-3 帧中扫描 EMDF 块（字节级同步搜索）
     *
     * @param frame 原始帧字节（448kbps 5.1 时为 1792 字节）
     * @return 原始 EMDF 块字节数组列表（含 4 字节头部）
     */
    public static List<byte[]> findEmdfBlocks(byte[] frame) {
        List<byte[]> blocks = new ArrayList<>();
        int pos = 0;
        while (pos < frame.length - 4) {
            if (((frame[pos] & 0xFF) == 0x58) && ((frame[pos + 1] & 0xFF) == 0x38)) {
                // 读取长度（pos+2 处大端 16 位）
                int length = ((frame[pos + 2] & 0xFF) << 8) | (frame[pos + 3] & 0xFF);
                int total = length + 4;
                if (pos + total <= frame.length) {
                    byte[] block = new byte[total];
                    System.arraycopy(frame, pos, block, 0, total);
                    blocks.add(block);
                    pos += total;
                    continue;
                }
            }
            pos++;
        }
        return blocks;
    }

    /**
     * 从 EMDF 块中提取 JOC 载荷字节
     * 
     * @param emdfBlock 原始 EMDF 块字节（含 4 字节头部）
     * @return JOC 载荷字节，未找到则返回 null
     */
    public static byte[] extractJocPayload(byte[] emdfBlock) {
        Eac3BitReader bits = new Eac3BitReader(emdfBlock);
        try {
            // 跳过 EMDF 头部
            bits.skip(16);
            int length = bits.read(16);
            int blockEnd = bits.position() + length * 8;

            int version = bits.read(2);
            if (version == 3)
                version += bits.variableBits(2);
            int key = bits.read(3);
            if (key == 7)
                key += bits.variableBits(3);
            if (version != 0 || key != 0)
                return null;

            // 遍历载荷
            while (bits.position() < blockEnd) {
                int pid = bits.read(5);
                if (pid == 0)
                    break;
                if (pid == 0x1F)
                    pid += bits.variableBits(5);

                boolean hasOffset = bits.readBit();
                if (hasOffset)
                    bits.skip(12);
                if (bits.readBit())
                    bits.variableBits(11);
                if (bits.readBit())
                    bits.variableBits(2);
                if (bits.readBit())
                    bits.skip(8);
                if (!bits.readBit()) {
                    boolean frameAligned = false;
                    if (!hasOffset) {
                        frameAligned = bits.readBit();
                        if (frameAligned)
                            bits.skip(2);
                    }
                    if (hasOffset || frameAligned)
                        bits.skip(7);
                }

                int payloadLen = bits.variableBits(8);
                int payloadStart = bits.position();
                int payloadEnd = payloadStart + payloadLen * 8;

                if (pid == 14) {
                    return readBitsAsBytes(emdfBlock, payloadStart, payloadLen * 8);
                }

                bits.position(Math.min(payloadEnd, blockEnd));
            }
        } catch (IndexOutOfBoundsException ignored) {
        }
        return null;
    }

    /**
     * 从 EMDF 块中提取 OAMD 载荷字节
     * 
     * @return OAMD 载荷字节，未找到则返回 null
     */
    public static byte[] extractOamdPayload(byte[] emdfBlock) {
        Eac3BitReader bits = new Eac3BitReader(emdfBlock);
        try {
            bits.skip(16);
            int length = bits.read(16);
            int blockEnd = bits.position() + length * 8;
            int version = bits.read(2);
            if (version == 3)
                version += bits.variableBits(2);
            int key = bits.read(3);
            if (key == 7)
                key += bits.variableBits(3);
            if (version != 0 || key != 0)
                return null;

            while (bits.position() < blockEnd) {
                int pid = bits.read(5);
                if (pid == 0)
                    break;
                if (pid == 0x1F)
                    pid += bits.variableBits(5);
                boolean hasOffset = bits.readBit();
                if (hasOffset)
                    bits.skip(12);
                if (bits.readBit())
                    bits.variableBits(11);
                if (bits.readBit())
                    bits.variableBits(2);
                if (bits.readBit())
                    bits.skip(8);
                if (!bits.readBit()) {
                    boolean frameAligned = false;
                    if (!hasOffset) {
                        frameAligned = bits.readBit();
                        if (frameAligned)
                            bits.skip(2);
                    }
                    if (hasOffset || frameAligned)
                        bits.skip(7);
                }
                int plen = bits.variableBits(8);
                int ps = bits.position();
                int pe = ps + plen * 8;
                if (pid == 11) { // OAMD
                    return readBitsAsBytes(emdfBlock, ps, plen * 8);
                }
                bits.position(Math.min(pe, blockEnd));
            }
        } catch (IndexOutOfBoundsException ignored) {
        }
        return null;
    }

    /** 从字节数组中读取位到新字节数组 */
    private static byte[] readBitsAsBytes(byte[] data, int bitPos, int bitCount) {
        if (bitCount <= 0)
            return new byte[0];
        int byteLen = (bitCount + 7) / 8;
        byte[] result = new byte[byteLen];
        for (int i = 0; i < bitCount; i++) {
            int srcByte = data[(bitPos + i) >> 3] & 0xFF;
            int bit = (srcByte >> (7 - ((bitPos + i) & 7))) & 1;
            int dstIdx = i >> 3;
            result[dstIdx] = (byte) ((result[dstIdx] << 1) | bit);
        }
        // 若末字节不完整则移位
        int remaining = bitCount & 7;
        if (remaining != 0) {
            result[byteLen - 1] <<= (8 - remaining);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════
    // OAMD 解析
    // ═══════════════════════════════════════════════════════

    /** OAMD 配置 */
    public static class OamdConfig {
        public int version;
        public int objectCount;
        public boolean dynamicOnly;
        public int beds;
        public int isfObjects;
        public int elementCount;
        public ObjectElement objectElement;
    }

    /** 逐帧对象位置更新 */
    public static class ObjectElement {
        public int blockCount;
        public int[] offsets;
        public int[] ramps;
        public int dynamicObjects;
        public int validPositionUpdates;
        public ObjectPosition[] firstPositions;
    }

    /** 单个音频对象的 3D 位置 */
    public static class ObjectPosition {
        public int objectIndex;
        public int blockIndex;
        public float x, y, z;
        public boolean differential;
        public float distance;
    }

    private static final int[][] STANDARD_BED_CHANNELS = {
            { 0, 1 }, { 2 }, { 3 }, { 4, 5 }, { 6, 7 }, { 8, 9 }, { 10, 11 }, { 12, 13 }, { 14, 15 }, { 16 },
    };
    private static final int[] ISF_OBJECT_COUNT = { 4, 8, 10, 14, 15, 30 };
    private static final int[] RAMP_DURATION = { 32, 64, 128, 256, 320, 480, 1000, 1001,
            1024, 1600, 1601, 1602, 1920, 2000, 2002, 2048 };
    private static final float[] DISTANCE_FACTORS = { 1.1f, 1.3f, 1.6f, 2.0f, 2.5f, 3.2f, 4.0f,
            5.0f, 6.3f, 7.9f, 10.0f, 12.6f, 15.8f, 20.0f, 25.1f, 50.1f };

    /**
     * 将 OAMD 载荷字节解析为结构化的对象位置
     */
    public static OamdConfig parseOamd(byte[] payload) {
        Eac3BitReader bits = new Eac3BitReader(payload);
        OamdConfig cfg = new OamdConfig();

        try {
            cfg.version = bits.read(2);
            if (cfg.version == 3)
                cfg.version += bits.read(3);
            cfg.objectCount = bits.read(5) + 1;
            if (cfg.objectCount == 32)
                cfg.objectCount += bits.read(7);

            // 节目分配
            cfg.dynamicOnly = bits.readBit();
            cfg.beds = 0;
            cfg.isfObjects = 0;

            if (cfg.dynamicOnly) {
                if (bits.readBit())
                    cfg.beds = 1;
            } else {
                int contentDesc = bits.read(4);
                if ((contentDesc & 1) != 0) {
                    bits.read(1);
                    int bedInstances = bits.readBit() ? bits.read(3) + 2 : 1;
                    for (int bi = 0; bi < bedInstances; bi++) {
                        if (bits.readBit())
                            cfg.beds++;
                        else if (bits.readBit()) {
                            for (int s = 0; s < 10; s++) {
                                if (bits.readBit())
                                    cfg.beds += STANDARD_BED_CHANNELS[s].length;
                            }
                        } else {
                            for (int n = 0; n < 17; n++) {
                                if (bits.readBit())
                                    cfg.beds++;
                            }
                        }
                    }
                }
                if ((contentDesc & 2) != 0) {
                    int isfIdx = bits.read(3);
                    cfg.isfObjects = isfIdx < ISF_OBJECT_COUNT.length ? ISF_OBJECT_COUNT[isfIdx] : -1;
                }
                if ((contentDesc & 4) != 0) {
                    if (bits.read(5) == 31)
                        bits.skip(7);
                }
                if ((contentDesc & 8) != 0) {
                    bits.skip((bits.read(4) + 1) * 8);
                }
            }

            // 元素
            boolean altObjPresent = bits.readBit();
            cfg.elementCount = bits.read(4);
            if (cfg.elementCount == 15)
                cfg.elementCount += bits.read(5);

            int bedOrIsf = cfg.beds + cfg.isfObjects;
            for (int e = 0; e < cfg.elementCount; e++) {
                int elIdx = bits.read(4);
                int elEnd = bits.position() + bitsReadLimited(bits, 4, 4) + 1;
                bits.skip(altObjPresent ? 5 : 1);
                if (elIdx == 1) {
                    cfg.objectElement = parseObjectElement(bits, cfg.objectCount, bedOrIsf);
                }
                bits.position(Math.min(elEnd, bits.limit()));
            }

        } catch (IndexOutOfBoundsException ignored) {
        }

        return cfg;
    }

    private static int bitsReadLimited(Eac3BitReader bits, int groupBits, int limit) {
        int value = 0;
        while (true) {
            value += bits.read(groupBits);
            if (limit == 0)
                return value;
            limit--;
            if (!bits.readBit())
                return value;
            value = (value + 1) << groupBits;
        }
    }

    private static ObjectElement parseObjectElement(Eac3BitReader bits, int objCount,
            int bedOrIsf) {
        ObjectElement el = new ObjectElement();

        // 元数据更新信息
        int mode = bits.read(2);
        int sampleOffset;
        if (mode == 0)
            sampleOffset = 0;
        else if (mode == 1)
            sampleOffset = new int[] { 8, 16, 18, 24 }[bits.read(2)];
        else
            sampleOffset = bits.read(5);

        el.blockCount = bits.read(3) + 1;
        el.offsets = new int[el.blockCount];
        el.ramps = new int[el.blockCount];
        for (int b = 0; b < el.blockCount; b++) {
            el.offsets[b] = bits.read(6) + sampleOffset;
            int rc = bits.read(2);
            if (rc == 3) {
                if (bits.readBit())
                    el.ramps[b] = RAMP_DURATION[bits.read(4)];
                else
                    el.ramps[b] = bits.read(11);
            } else {
                el.ramps[b] = new int[] { 0, 512, 1536 }[rc];
            }
        }

        if (!bits.readBit())
            bits.skip(5);

        // 对象信息块
        List<ObjectPosition> positions = new ArrayList<>();
        int valid = 0;
        int dynamicObjs = Math.max(0, objCount - bedOrIsf);

        for (int obj = 0; obj < objCount; obj++) {
            boolean isBedOrIsf = obj < bedOrIsf;
            for (int blk = 0; blk < el.blockCount; blk++) {
                boolean inactive = bits.readBit();
                int basicStatus = inactive ? 0 : (blk == 0 ? 1 : bits.read(2));
                if ((basicStatus & 1) != 0)
                    skipObjectBasicInfo(bits, basicStatus == 1);

                int renderStatus = 0;
                if (!inactive && !isBedOrIsf) {
                    renderStatus = (blk == 0) ? 1 : bits.read(2);
                }

                if ((renderStatus & 1) != 0) {
                    ObjectPosition pos = parseObjectRenderInfo(bits, blk, renderStatus == 1);
                    valid++;
                    if (!isBedOrIsf) {
                        pos.objectIndex = obj - bedOrIsf;
                        pos.blockIndex = blk;
                        positions.add(pos);
                    }
                }

                if (bits.readBit())
                    bits.skip((bits.read(4) + 1) * 8);
            }
        }

        el.dynamicObjects = dynamicObjs;
        el.validPositionUpdates = valid;
        el.firstPositions = positions.toArray(new ObjectPosition[0]);
        return el;
    }

    private static void skipObjectBasicInfo(Eac3BitReader bits, boolean readAll) {
        int blocks = readAll ? 3 : bits.read(2);
        if ((blocks & 2) != 0) {
            if (bits.read(2) == 2)
                bits.read(6);
        }
        if ((blocks & 1) != 0 && !bits.readBit())
            bits.skip(5);
    }

    private static ObjectPosition parseObjectRenderInfo(Eac3BitReader bits, int blk,
            boolean readAll) {
        ObjectPosition pos = new ObjectPosition();
        int blocks = readAll ? 15 : bits.read(4);

        if ((blocks & 1) != 0) {
            pos.differential = blk != 0 && bits.readBit();
            if (pos.differential) {
                pos.x = bits.readSigned(3) / 62.0f;
                pos.y = bits.readSigned(3) / 62.0f;
                pos.z = bits.readSigned(3) / 15.0f;
            } else {
                pos.x = Math.min(1.0f, bits.read(6) / 62.0f);
                pos.y = Math.min(1.0f, bits.read(6) / 62.0f);
                pos.z = ((bits.read(1) << 1) - 1) * bits.read(4) / 15.0f;
            }
            if (bits.readBit()) {
                if (bits.readBit())
                    pos.distance = Float.POSITIVE_INFINITY;
                else
                    pos.distance = DISTANCE_FACTORS[bits.read(4)];
            }
        }
        if ((blocks & 2) != 0)
            bits.skip(4);
        if ((blocks & 4) != 0) {
            int sm = bits.read(2);
            if (sm == 1)
                bits.skip(5);
            else if (sm == 2)
                bits.skip(15);
        }
        if ((blocks & 8) != 0 && bits.readBit())
            bits.skip(5);
        bits.skip(1);
        return pos;
    }
}
