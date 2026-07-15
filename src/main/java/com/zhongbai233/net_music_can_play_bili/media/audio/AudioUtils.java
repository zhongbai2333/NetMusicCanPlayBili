package com.zhongbai233.net_music_can_play_bili.media.audio;

import net.minecraft.core.BlockPos;

/** 音频相关通用工具：位置复制、距离/增益计算、格式化等 */
public final class AudioUtils {

    /** 手动反比衰减参考距离 */
    public static final float DISTANCE_REFERENCE = 8.0f;
    /** 最小有效距离（音响半径），避免进入音响圈内部时增益异常 */
    public static final float SPATIAL_RADIUS = 1.5f;
    /** 空间音源满音量时的最大可听距离；音量降低时会按比例收缩 */
    public static final float MAX_AUDIBLE_DISTANCE = 64.0f;
    /** 超出设计听距后用于客户端平滑淡入淡出的额外距离比例。 */
    public static final float AUDIBLE_FADE_FRACTION = 0.20f;
    /** @deprecated 使用 {@link #MAX_AUDIBLE_DISTANCE}。 */
    @Deprecated
    public static final float SPEAKER_MAX_AUDIBLE_DISTANCE = MAX_AUDIBLE_DISTANCE;

    private AudioUtils() {
    }

    /** 防御性复制 BlockPos（可处理 null） */
    public static BlockPos copyPos(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 防御性复制 float[3] 坐标（可处理 null） */
    public static float[] copyPos3(float[] pos) {
        if (pos == null) {
            return null;
        }
        return new float[] { pos[0], pos[1], pos[2] };
    }

    /** 从 BlockPos 计算方块中心坐标，支持 fallback */
    public static float[] centerFor(BlockPos pos, float[] fallbackMachinePos) {
        if (pos != null) {
            return new float[] { pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f };
        }
        return fallbackMachinePos != null ? copyPos3(fallbackMachinePos) : new float[] { 0.0f, 0.0f, 0.0f };
    }

    /** 两点间欧氏距离 */
    public static float distance(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 限制增益到 [0, 1] */
    public static float clampGain(float gain) {
        return Math.max(0.0f, Math.min(1.0f, gain));
    }

    /** 基于距离的衰减增益 */
    public static float gainForDistance(float d) {
        float clamped = Math.max(SPATIAL_RADIUS, d);
        return clampGain(DISTANCE_REFERENCE / (DISTANCE_REFERENCE + clamped));
    }

    /**
     * 客户端空间音源衰减：设计听距内保留原始增益，越界后再平滑淡出。
     * 音量只负责缩放设计听距与响度，不要求服务端按该距离停止媒体流。
     */
    public static float spatialGainForDistance(float d, float volume) {
        float clampedVolume = clampGain(volume);
        if (clampedVolume <= 0.0f) {
            return 0.0f;
        }
        float maxAudibleDistance = MAX_AUDIBLE_DISTANCE * clampedVolume;
        float baseGain = gainForDistance(d) * clampedVolume;
        if (d <= maxAudibleDistance) {
            return baseGain;
        }
        float fadeDistance = maxAudibleDistance * AUDIBLE_FADE_FRACTION;
        float fadeEnd = maxAudibleDistance + fadeDistance;
        if (d >= fadeEnd) {
            return 0.0f;
        }
        float remaining = clampGain((fadeEnd - d) / fadeDistance);
        float fadeGain = remaining * remaining * (3.0f - 2.0f * remaining);
        return baseGain * fadeGain;
    }

    /** 音响衰减兼容入口。 */
    public static float speakerGainForDistance(float d, float volume) {
        return spatialGainForDistance(d, volume);
    }

    /** 格式化坐标为可读字符串 */
    public static String fmtPos(float[] p) {
        if (p == null) {
            return "(null)";
        }
        return String.format("(%.2f, %.2f, %.2f)", p[0], p[1], p[2]);
    }
}
