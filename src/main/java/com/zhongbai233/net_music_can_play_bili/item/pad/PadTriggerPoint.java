package com.zhongbai233.net_music_can_play_bili.item.pad;

import java.util.UUID;

public record PadTriggerPoint(UUID pointId, String name, double x, double y, double z, int radiusBlocks,
        int mediaId, PadTriggerMode triggerMode, boolean loop, int volumePerMille, boolean visible) {
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 128;

    public PadTriggerPoint {
        pointId = pointId == null ? UUID.randomUUID() : pointId;
        name = name == null ? "" : name;
        radiusBlocks = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radiusBlocks));
        mediaId = Math.max(0, mediaId);
        triggerMode = triggerMode == null ? PadTriggerMode.MANUAL : triggerMode;
        volumePerMille = Math.max(0, Math.min(1000, volumePerMille));
    }

    public static PadTriggerPoint createManual(String name, double x, double y, double z, int mediaId) {
        return new PadTriggerPoint(UUID.randomUUID(), name, x, y, z, 8, mediaId, PadTriggerMode.MANUAL, false, 1000,
                true);
    }
}