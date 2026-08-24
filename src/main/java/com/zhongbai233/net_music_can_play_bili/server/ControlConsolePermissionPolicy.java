package com.zhongbai233.net_music_can_play_bili.server;

/** 纯策略：汇总中控台管理员的原版与外部权限来源。 */
public final class ControlConsolePermissionPolicy {
    private ControlConsolePermissionPolicy() {
    }

    static boolean grantsAdministrator(boolean singleplayerOwner, boolean opLevelTwoOrHigher,
            boolean permissionNodeGranted) {
        return singleplayerOwner || opLevelTwoOrHigher || permissionNodeGranted;
    }

    public static boolean passesBuildGate(boolean mayBuild, boolean administrator) {
        return mayBuild || administrator;
    }
}
