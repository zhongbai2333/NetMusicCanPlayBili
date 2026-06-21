package com.zhongbai233.net_music_can_play_bili.client.sync;

/** 手持媒体表面的渲染和解码开关。 */
public record HandheldMediaRenderState(boolean videoDecodeEnabled, int videoQualityCeiling,
        boolean allowAiSubtitle) {
    public static final HandheldMediaRenderState DISABLED = new HandheldMediaRenderState(false, 0, false);
}
