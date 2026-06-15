package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.bridge.SoundManagerBridge;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SoundManager.class)
public abstract class SoundManagerAccessor implements SoundManagerBridge {
    @Shadow
    private SoundEngine soundEngine;

    @Override
    public SoundEngine net_music_can_play_bili$soundEngine() {
        return soundEngine;
    }
}
