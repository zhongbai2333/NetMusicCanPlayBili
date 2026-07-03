package com.zhongbai233.net_music_can_play_bili.item.pad;

public record PadMapSettings(String dimension, int centerX, int centerZ, int radiusBlocks, float zoom,
        boolean autoFollowPlayer) {
    public static final PadMapSettings DEFAULT = new PadMapSettings("minecraft:overworld", 0, 0, 64, 1.0F, true);

    public PadMapSettings {
        dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
        radiusBlocks = Math.max(16, Math.min(256, radiusBlocks));
        zoom = Math.max(0.25F, Math.min(8.0F, zoom));
    }
}