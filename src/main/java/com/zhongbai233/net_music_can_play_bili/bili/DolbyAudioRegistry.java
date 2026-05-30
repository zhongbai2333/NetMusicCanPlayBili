package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.List;

public class DolbyAudioRegistry {

    private static volatile DolbyAudioHandler activeHandler;
    private static volatile StereoOpenALHandler activeStereo;
    private static volatile float[] machinePos;
    private static volatile float[] listenerPos;
    private static volatile boolean hasPositions;

    public static void register(DolbyAudioHandler handler) {
        DolbyAudioHandler old = activeHandler;
        activeHandler = handler;
        if (old != null && old != handler) {
            old.cleanup();
        }
    }

    public static void unregister(DolbyAudioHandler handler) {
        if (activeHandler == handler) {
            activeHandler = null;
        }
    }

    public static void registerStereo(StereoOpenALHandler handler) {
        StereoOpenALHandler old = activeStereo;
        activeStereo = handler;
        if (old != null && old != handler) {
            old.cleanup();
        }
    }

    public static void unregisterStereo(StereoOpenALHandler handler) {
        if (activeStereo == handler) {
            activeStereo = null;
        }
    }

    public static void updatePositions(float[] machinePos, float[] listenerPos) {
        DolbyAudioRegistry.machinePos = machinePos;
        DolbyAudioRegistry.listenerPos = listenerPos;
        hasPositions = true;
        DolbyAudioHandler h = activeHandler;
        if (h != null) {
            h.tick(machinePos, listenerPos);
        }
        StereoOpenALHandler s = activeStereo;
        if (s != null) {
            s.tick(machinePos, listenerPos);
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
        return activeHandler != null || activeStereo != null;
    }

    public static List<String> describeActiveSources() {
        DolbyAudioHandler handler = activeHandler;
        if (handler != null) {
            return handler.describeSources(machinePos, listenerPos);
        }
        StereoOpenALHandler stereo = activeStereo;
        if (stereo != null) {
            return stereo.describeState();
        }
        return List.of("No active Dolby/OpenAL audio");
    }

    public static void cleanup() {
        if (activeHandler != null) {
            activeHandler.cleanup();
            activeHandler = null;
        }
        if (activeStereo != null) {
            activeStereo.cleanup();
            activeStereo = null;
        }
    }
}
