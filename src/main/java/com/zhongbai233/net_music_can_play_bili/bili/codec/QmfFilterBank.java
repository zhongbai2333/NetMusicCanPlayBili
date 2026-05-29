package com.zhongbai233.net_music_can_play_bili.bili.codec;

/**
 * 64 子带正交镜像滤波器组，用于 JOC
 *
 *
 * 用于在时域 PCM 和 QMF 子带域之间进行变换，JOC 混合矩阵在子带域中运算
 *
 * 每次变换处理 256 个时域样本 → 64 个子带 × 4 个复数时隙
 * 每个时隙包含 64 个实部 + 64 个虚部
 */
public class QmfFilterBank {

    /** 子带数量 */
    public static final int SUBBANDS = 64;

    /** 每次变换的输入样本数 */
    public static final int INPUT_SIZE = 256;

    /** 每次变换的输出时隙数 */
    public static final int TIMESLOTS = 4;
    private static final float INV_SQRT_SUBBANDS = 1.0f / (float) Math.sqrt(SUBBANDS);

    /**
     * 前向 QMF：时域 → 子带域
     *
     * @param input 256 个时域 PCM 样本
     * @return [时隙][子带] 的复数值（实部、虚部交织：[r,i,r,i,...]）
     */
    public static float[][][] forward(float[] input) {
        float[][][] result = new float[TIMESLOTS][SUBBANDS][2];
        // 应用分析滤波器组
        for (int ts = 0; ts < TIMESLOTS; ts++) {
            for (int sb = 0; sb < SUBBANDS; sb++) {
                float real = 0, imag = 0;
                for (int n = 0; n < 64; n++) {
                    int idx = ts * SUBBANDS + n;
                    if (idx < INPUT_SIZE) {
                        float x = input[idx];
                        double angle = Math.PI / (2.0 * SUBBANDS)
                                * (2 * sb + 1) * (2 * n - (SUBBANDS - 1));
                        real += (float) (x * Math.cos(angle));
                        imag += (float) (x * Math.sin(angle));
                    }
                }
                result[ts][sb][0] = real * INV_SQRT_SUBBANDS;
                result[ts][sb][1] = imag * INV_SQRT_SUBBANDS;
            }
        }
        return result;
    }

    /**
     * 逆向 QMF：子带域 → 时域
     *
     * @param input [时隙][子带][2] 复数值（实部、虚部）
     * @return 256 个时域 PCM 样本
     */
    public static float[] inverse(float[][][] input) {
        float[] output = new float[INPUT_SIZE];
        for (int ts = 0; ts < TIMESLOTS; ts++) {
            if (ts >= input.length)
                break;
            for (int n = 0; n < SUBBANDS; n++) {
                float sample = 0;
                for (int sb = 0; sb < SUBBANDS; sb++) {
                    double angle = Math.PI / (2.0 * SUBBANDS)
                            * (2 * sb + 1) * (2 * n - (SUBBANDS - 1));
                    float real = input[ts][sb][0];
                    float imag = input[ts][sb][1];
                    sample += (float) (real * Math.cos(angle) + imag * Math.sin(angle));
                }
                output[ts * SUBBANDS + n] = sample * INV_SQRT_SUBBANDS;
            }
        }
        return output;
    }

    /**
     * 应用 JOC 混合矩阵，从下混声道恢复对象音频
     *
     * @param downmixQmf  [声道][时隙][子带][2] QMF 域下混复数数据
     * @param mixMatrix   [对象][时隙][声道][子带] 混合系数（实数）
     * @param numChannels 下混声道数
     * @param numObjects  输出对象数
     * @return [对象][时隙][子带][2] 对象 QMF 系数
     */
    public static float[][][][] applyMixingMatrix(
            float[][][][] downmixQmf,
            float[][][][] mixMatrix,
            int numChannels, int numObjects) {

        float[][][][] result = new float[numObjects][TIMESLOTS][SUBBANDS][2];

        for (int ts = 0; ts < TIMESLOTS; ts++) {
            for (int sb = 0; sb < SUBBANDS; sb++) {
                for (int obj = 0; obj < numObjects; obj++) {
                    float realSum = 0, imagSum = 0;
                    int channels = Math.min(numChannels, Math.min(downmixQmf.length, mixMatrix[obj][ts].length));
                    for (int ch = 0; ch < channels; ch++) {
                        float coeff = mixMatrix[obj][ts][ch][sb];
                        realSum += downmixQmf[ch][ts][sb][0] * coeff;
                        imagSum += downmixQmf[ch][ts][sb][1] * coeff;
                    }
                    result[obj][ts][sb][0] = realSum;
                    result[obj][ts][sb][1] = imagSum;
                }
            }
        }
        return result;
    }

    /**
     * 便捷方法：对多声道执行前向 QMF
     *
     * @param pcm [声道][256] PCM 样本
     * @return [声道][时隙][子带][2]
     */
    public static float[][][][] forwardMulti(float[][] pcm) {
        int nch = pcm.length;
        float[][][][] result = new float[nch][][][];
        for (int ch = 0; ch < nch; ch++) {
            result[ch] = forward(pcm[ch]);
        }
        return result;
    }

    /**
     * 便捷方法：对多个对象执行逆向 QMF
     *
     * @param objectQmf [对象][时隙][子带][2]
     * @return [对象][256] PCM 样本
     */
    public static float[][] inverseMulti(float[][][][] objectQmf) {
        int nobj = objectQmf.length;
        float[][] result = new float[nobj][];
        for (int obj = 0; obj < nobj; obj++) {
            result[obj] = inverse(objectQmf[obj]);
        }
        return result;
    }
}
