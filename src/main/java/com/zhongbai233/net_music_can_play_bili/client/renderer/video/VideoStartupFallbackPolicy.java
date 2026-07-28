package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.VideoCandidate;

import java.util.List;

/** 视频候选首帧追赶与降级策略，不依赖 Minecraft/FFmpeg，便于单元测试。 */
final class VideoStartupFallbackPolicy {
    private VideoStartupFallbackPolicy() {
    }

    static DecodeSize candidateDecodeSize(int currentWidth, int currentHeight,
            int sourceWidth, int sourceHeight) {
        int safeCurrentWidth = Math.max(1, currentWidth);
        int safeCurrentHeight = Math.max(1, currentHeight);
        int safeSourceWidth = Math.max(1, sourceWidth);
        int safeSourceHeight = Math.max(1, sourceHeight);
        double scale = Math.min(1.0D, Math.min(
                safeCurrentWidth / (double) safeSourceWidth,
                safeCurrentHeight / (double) safeSourceHeight));
        int width = evenAtLeastTwo((int) Math.round(safeSourceWidth * scale));
        int height = evenAtLeastTwo((int) Math.round(safeSourceHeight * scale));
        return new DecodeSize(width, height);
    }

    private static int evenAtLeastTwo(int value) {
        int safe = Math.max(2, value);
        return (safe & 1) == 0 ? safe : safe - 1;
    }

    static List<VideoCandidate> operationalCandidates(List<VideoCandidate> candidates,
            int maxSourceWidth, int maxSourceHeight) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int safeMaxWidth = Math.max(1, maxSourceWidth);
        int safeMaxHeight = Math.max(1, maxSourceHeight);
        List<VideoCandidate> withinLimit = candidates.stream()
                .filter(candidate -> candidate.sourceWidth() <= safeMaxWidth
                        && candidate.sourceHeight() <= safeMaxHeight)
                .toList();
        // 没有任何低档候选时保留原列表，避免非常规视频被运行上限彻底禁播。
        if (withinLimit.isEmpty()) {
            return List.copyOf(candidates);
        }
        if (withinLimit.size() == candidates.size()) {
            return withinLimit;
        }
        // 已出现超限 AV1 时，优先切到兼容性更稳定的 H.264；合规 AV1 仍保留为后备。
        return java.util.stream.Stream.concat(
                withinLimit.stream().filter(candidate -> candidate.codecId() == 7),
                withinLimit.stream().filter(candidate -> candidate.codecId() != 7))
                .toList();
    }

    record DecodeSize(int width, int height) {
    }
}