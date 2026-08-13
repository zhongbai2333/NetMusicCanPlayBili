package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;

import java.util.Objects;

/** 现代唱片机异步解析结果在修改方块实体状态前的准入策略。 */
final class TurntableResolveAdmissionPolicy {
    enum Decision {
        APPLY,
        DROP_REMOVED,
        DROP_LEVEL_CHANGED,
        DROP_STALE_GENERATION,
        DROP_SOURCE_CHANGED
    }

    private TurntableResolveAdmissionPolicy() {
    }

    static Decision decide(boolean removed, boolean sameLevel, ResolveGeneration currentGeneration,
            ResolveGeneration capturedGeneration, String currentSource, String requestedSource) {
        if (removed) {
            return Decision.DROP_REMOVED;
        }
        if (!sameLevel) {
            return Decision.DROP_LEVEL_CHANGED;
        }
        if (!Objects.requireNonNull(currentGeneration, "currentGeneration")
                .equals(Objects.requireNonNull(capturedGeneration, "capturedGeneration"))) {
            return Decision.DROP_STALE_GENERATION;
        }
        return Objects.equals(currentSource, requestedSource)
                ? Decision.APPLY
                : Decision.DROP_SOURCE_CHANGED;
    }
}
