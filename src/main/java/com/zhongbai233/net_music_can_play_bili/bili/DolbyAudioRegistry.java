package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.List;

/**
 * 活跃 Dolby Atmos 音频处理器注册表
 */
public class DolbyAudioRegistry {

    private static volatile DolbyAudioHandler activeHandler;
    private static volatile float[] machinePos;
    private static volatile float[] listenerPos;
    private static volatile boolean hasPositions;

    public static void register(DolbyAudioHandler handler) {
        DolbyAudioHandler old = activeHandler;
        activeHandler = handler;
        hasPositions = false;
        if (old != null && old != handler) {
            old.cleanup();
        }
    }

    public static void unregister(DolbyAudioHandler handler) {
        if (activeHandler == handler) {
            activeHandler = null;
        }
    }

    /**
     * 每客户端 tick 调用，传入唱片机位置和玩家头部位置
     * 处理器利用这些位置来定位空间声场
     * 同时通过活跃处理器处理排队的 EC-3 帧
     */
    public static void updatePositions(float[] machinePos, float[] listenerPos) {
        DolbyAudioRegistry.machinePos = machinePos;
        DolbyAudioRegistry.listenerPos = listenerPos;
        hasPositions = true;
        DolbyAudioHandler h = activeHandler;
        if (h != null) {
            h.tick(machinePos, listenerPos);
        }
    }

    public static void setMachinePos(double x, double y, double z) {
        machinePos = new float[] { (float) x, (float) y, (float) z };
        hasPositions = machinePos != null;
    }

    public static float[] getMachinePos() {
        return hasPositions ? machinePos : null;
    }

    public static float[] getListenerPos() {
        return hasPositions ? listenerPos : null;
    }

    public static boolean isActive() {
        return activeHandler != null;
    }

    /**
     * 描述当前 Dolby 虚拟声源位置，供客户端调试命令使用
     */
    public static List<String> describeActiveSources() {
        DolbyAudioHandler handler = activeHandler;
        if (handler == null) {
            return List.of("当前没有正在播放的 Dolby 音频");
        }
        return handler.describeSources(machinePos, listenerPos);
    }

    public static void cleanup() {
        if (activeHandler != null) {
            activeHandler.cleanup();
            activeHandler = null;
        }
    }
}
