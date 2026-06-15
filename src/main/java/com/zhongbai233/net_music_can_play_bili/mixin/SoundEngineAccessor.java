package com.zhongbai233.net_music_can_play_bili.mixin;

import com.mojang.blaze3d.audio.Library;
import com.zhongbai233.net_music_can_play_bili.bridge.SoundEngineBridge;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SoundEngine.class)
public abstract class SoundEngineAccessor implements SoundEngineBridge {
    @Shadow
    private boolean loaded;

    @Shadow
    private Library library;

    @Override
    public boolean net_music_can_play_bili$loaded() {
        return loaded;
    }

    @Override
    public Library net_music_can_play_bili$library() {
        return library;
    }
}
