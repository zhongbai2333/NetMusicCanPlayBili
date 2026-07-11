package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaDeviceProfile;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaRenderState;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaScreenSpec;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/** Pad implementation of the reusable handheld media video hooks. */
public final class PadHandheldMediaProfile implements HandheldMediaDeviceProfile {
    public static final PadHandheldMediaProfile INSTANCE = new PadHandheldMediaProfile();
    public static final HandheldMediaScreenSpec SCREEN = new HandheldMediaScreenSpec(448, 256,
            Integer.getInteger("ncpb.pad.offscreen_scale", Integer.getInteger("ncpb.mp4.offscreen_scale", 2)));

    private PadHandheldMediaProfile() {
    }

    @Override
    public HandheldMediaScreenSpec screenSpec() {
        return SCREEN;
    }

    @Override
    public HandheldMediaPlayback playback(UUID deviceId) {
        return ClientMediaPlayback.videoPlayback(deviceId, PadFocusState.subtitleAiEnabled());
    }

    @Override
    public HandheldMediaRenderState renderState(UUID deviceId) {
        boolean active = deviceId != null && ClientMediaPlayback.hasPlayback(deviceId);
        return new HandheldMediaRenderState(active, PadFocusState.videoQualityCeiling(),
            PadFocusState.subtitleAiEnabled());
    }

    @Override
    public boolean hasStartedSound(UUID deviceId, String sessionId) {
        return ClientMediaPlayback.hasAudioStarted(deviceId, sessionId);
    }

    @Override
    public boolean isDeviceAvailable(UUID deviceId) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.player != null && deviceId != null
                && !PadItem.findByDeviceId(minecraft.player, deviceId).isEmpty();
    }

    @Override
    public String subtitleMode(UUID deviceId) {
        return PadFocusState.subtitleModeName();
    }
}