package com.zhongbai233.net_music_can_play_bili.bili.codec;

/**
 * Dolby Atmos JOC 解码器
 */
public class Eac3JocDecoder {

    /** 解析后的 JOC 配置 */
    public static class JocConfig {
        public int downmixConfig;
        public int channelCount; // 5 or 7
        public int objectCount;
        public int extConfig;
        public float gain = 1.0f;
        public int sequence;
    }

    /** 逐对象 JOC 元数据 */
    public static class JocObject {
        public boolean active;
        public int bandsIndex;
        public int numBands;
        public boolean sparse;
        public int quantizationTable;
        public boolean steepSlope;
        public int dataPoints;
        public int[] timeslotOffsets;
    }

    /** 单帧 JOC 解码结果 */
    public static class JocResult {
        public JocConfig config = new JocConfig();
        public JocObject[] objects;
        /** 反量化混合矩阵：[对象][数据点][声道][参数频带] */
        public float[][][][] mixMatrices;
        /** 逐时隙插值：[对象][时隙][声道][64子带] */
        public float[][][][] interpolatedMatrices;
    }

    // ── 公开 API ──────────────────────────────────────────

    /**
     * 解码从 EMDF 块中提取的 JOC 载荷。
     *
     * @param payload      原始 JOC 载荷字节
     * @param qmfTimeslots 每帧 QMF 时隙数（E-AC-3 为 6）
     */
    public JocResult decode(byte[] payload, int qmfTimeslots) {
        Eac3BitReader bits = new Eac3BitReader(payload);
        JocResult result = new JocResult();

        // 头部
        JocConfig cfg = result.config;
        cfg.downmixConfig = bits.read(3);
        if (cfg.downmixConfig > 4) {
            throw new IllegalArgumentException("unsupported joc_dmx_config_idx: " + cfg.downmixConfig);
        }
        cfg.channelCount = (cfg.downmixConfig == 0 || cfg.downmixConfig == 3) ? 5 : 7;
        cfg.objectCount = bits.read(6) + 1;
        cfg.extConfig = bits.read(3);

        // 信息
        int gainPower = bits.read(3);
        cfg.gain = 1.0f + (bits.read(5) / 32.0f) * (float) Math.pow(2, gainPower - 4);
        cfg.sequence = bits.read(10);

        JocObject[] objs = new JocObject[cfg.objectCount];
        for (int i = 0; i < cfg.objectCount; i++) {
            JocObject obj = new JocObject();
            obj.active = bits.readBit();
            if (obj.active) {
                obj.bandsIndex = bits.read(3);
                obj.numBands = Eac3JocTables.NUM_BANDS[obj.bandsIndex];
                obj.sparse = bits.readBit();
                obj.quantizationTable = bits.read(1);
                obj.steepSlope = bits.readBit();
                obj.dataPoints = bits.read(1) + 1;
                if (obj.steepSlope) {
                    obj.timeslotOffsets = new int[obj.dataPoints];
                    for (int dp = 0; dp < obj.dataPoints; dp++) {
                        obj.timeslotOffsets[dp] = bits.read(5) + 1;
                    }
                }
            }
            objs[i] = obj;
        }
        result.objects = objs;

        // 数据
        int[][][][] raw = decodeData(bits, cfg, objs);

        // 反量化
        result.mixMatrices = dequantize(cfg, objs, raw);

        // 插值
        result.interpolatedMatrices = interpolate(cfg, objs, result.mixMatrices, qmfTimeslots);

        return result;
    }

    // ── 霍夫曼解码 ──────────────────────────────────────

    private static int huffmanDecode(int[][] table, Eac3BitReader bits) {
        int node = 0;
        while (true) {
            int bit = bits.readBit() ? 1 : 0;
            node = table[node][bit];
            if (node < 0)
                return ~node;
        }
    }

    // ── 数据解码 ─────────────────────────────────────────

    private int[][][][] decodeData(Eac3BitReader bits, JocConfig cfg, JocObject[] objs) {
        int[][][][] raw = new int[cfg.objectCount][][][];

        for (int i = 0; i < cfg.objectCount; i++) {
            JocObject obj = objs[i];
            if (!obj.active) {
                raw[i] = new int[0][][];
                continue;
            }

            int[][][] objRaw = new int[obj.dataPoints][][];

            if (obj.sparse) {
                // 稀疏编码
                int[][] chanTable = (cfg.channelCount == 7) ? Eac3JocTables.HUFF_7CH_IDX : Eac3JocTables.HUFF_5CH_IDX;
                int[][] vecTable = (obj.quantizationTable == 1) ? Eac3JocTables.HUFF_FINE_SPARSE
                        : Eac3JocTables.HUFF_COARSE_SPARSE;

                for (int dp = 0; dp < obj.dataPoints; dp++) {
                    int[] channels = new int[obj.numBands];
                    int[] vectors = new int[obj.numBands];
                    channels[0] = bits.read(3);
                    for (int pb = 1; pb < obj.numBands; pb++) {
                        channels[pb] = huffmanDecode(chanTable, bits);
                    }
                    for (int pb = 0; pb < obj.numBands; pb++) {
                        vectors[pb] = huffmanDecode(vecTable, bits);
                    }

                    // 从稀疏重建矩阵
                    int[][] dpMatrix = new int[cfg.channelCount][obj.numBands];
                    int offset = obj.quantizationTable * 50 + 50;
                    int maxVal = (obj.quantizationTable * 48 + 48) * 2;

                    for (int ch = 0; ch < cfg.channelCount; ch++) {
                        for (int pb = 0; pb < obj.numBands; pb++) {
                            int activeCh = (pb == 0) ? channels[0]
                                    : (channels[pb - 1] + channels[pb]) % cfg.channelCount;
                            if (ch == activeCh) {
                                if (pb == 0) {
                                    dpMatrix[ch][pb] = (offset + vectors[pb]) % maxVal;
                                } else {
                                    dpMatrix[ch][pb] = (dpMatrix[ch][pb - 1] + vectors[pb]) % maxVal;
                                }
                            } else {
                                dpMatrix[ch][pb] = offset;
                            }
                        }
                    }
                    objRaw[dp] = dpMatrix;
                }

            } else {
                // 矩阵编码（非稀疏）
                int[][] huffTable = (obj.quantizationTable == 1) ? Eac3JocTables.HUFF_FINE : Eac3JocTables.HUFF_COARSE;

                for (int dp = 0; dp < obj.dataPoints; dp++) {
                    int[][] dpMatrix = new int[cfg.channelCount][obj.numBands];
                    for (int ch = 0; ch < cfg.channelCount; ch++) {
                        for (int pb = 0; pb < obj.numBands; pb++) {
                            dpMatrix[ch][pb] = huffmanDecode(huffTable, bits);
                        }
                    }
                    objRaw[dp] = dpMatrix;
                }
            }

            raw[i] = objRaw;
        }
        return raw;
    }

    // ── 反量化 ──────────────────────────────────────

    private static float[][][][] dequantize(JocConfig cfg, JocObject[] objs,
            int[][][][] raw) {
        float[][][][] result = new float[cfg.objectCount][][][];

        for (int i = 0; i < cfg.objectCount; i++) {
            JocObject obj = objs[i];
            if (!obj.active) {
                // 非活跃对象：空占位，插值时填充零
                result[i] = new float[0][][];
                continue;
            }

            float[][][] objMix = new float[obj.dataPoints][][];
            int quantCenter = obj.quantizationTable * 48 + 48;
            float gainStep = 0.2f - obj.quantizationTable * 0.1f;
            float centerFloat = quantCenter * gainStep;
            float maxVal = centerFloat * 2.0f;

            for (int dp = 0; dp < obj.dataPoints; dp++) {
                int[][] dpRaw = raw[i][dp];
                float[][] dpMix = new float[cfg.channelCount][obj.numBands];

                for (int ch = 0; ch < cfg.channelCount; ch++) {
                    float prev = 0;
                    for (int pb = 0; pb < obj.numBands; pb++) {
                        float val = (pb == 0)
                                ? (centerFloat + dpRaw[ch][pb] * gainStep) % maxVal
                                : (prev + dpRaw[ch][pb] * gainStep) % maxVal;
                        prev = val;
                        dpMix[ch][pb] = (val - centerFloat) * gainStep;
                    }
                }
                objMix[dp] = dpMix;
            }
            result[i] = objMix;
        }
        return result;
    }

    // ── 时隙插值 ──────────────────────────────────────

    private static float[][][][] interpolate(JocConfig cfg, JocObject[] objs,
            float[][][][] mix, int qmfTs) {
        float[][][][] result = new float[cfg.objectCount][][][];

        for (int i = 0; i < cfg.objectCount; i++) {
            JocObject obj = objs[i];
            if (!obj.active) {
                // 非活跃对象：填充正确形状的零矩阵
                float[][][] objInterp = new float[qmfTs][cfg.channelCount][64];
                result[i] = objInterp;
                continue;
            }

            float[][][] objInterp = new float[qmfTs][][];
            int[] pbMap = Eac3JocTables.SUBBAND_TO_BAND[obj.bandsIndex];

            for (int ts = 0; ts < qmfTs; ts++) {
                float[][] tsMatrix = new float[cfg.channelCount][64];
                for (int ch = 0; ch < cfg.channelCount; ch++) {
                    for (int sb = 0; sb < 64; sb++) {
                        int pb = pbMap[sb];
                        if (obj.dataPoints == 1) {
                            tsMatrix[ch][sb] = mix[i][0][ch][pb];
                        } else {
                            float lerp = (float) ts / qmfTs;
                            float from = mix[i][0][ch][pb];
                            float to = (mix[i].length > 1) ? mix[i][1][ch][pb] : from;
                            tsMatrix[ch][sb] = from + (to - from) * lerp;
                        }
                    }
                }
                objInterp[ts] = tsMatrix;
            }
            result[i] = objInterp;
        }
        return result;
    }
}
