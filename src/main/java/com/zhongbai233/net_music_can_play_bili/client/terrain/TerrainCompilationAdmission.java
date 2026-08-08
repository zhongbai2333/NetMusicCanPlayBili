package com.zhongbai233.net_music_can_play_bili.client.terrain;

/** 判断后台编译结果是否仍可进入渲染线程上传阶段。 */
public final class TerrainCompilationAdmission {
    private TerrainCompilationAdmission() {
    }

    public static boolean isCurrent(TerrainPreviewFrame frame, TerrainBlockSectionSnapshot source,
            long requestEpoch, long currentEpoch) {
        if (requestEpoch != currentEpoch || frame.removedSections().contains(source.section())) {
            return false;
        }
        for (TerrainBlockSectionSnapshot snapshot : frame.fullDetailSections()) {
            if (snapshot == source) {
                return true;
            }
        }
        return false;
    }
}