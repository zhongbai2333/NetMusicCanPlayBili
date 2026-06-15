package com.zhongbai233.net_music_can_play_bili.mixin;

import com.mojang.blaze3d.audio.Library;
import com.zhongbai233.net_music_can_play_bili.bridge.LibraryBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Library.class)
public abstract class LibraryAccessor implements LibraryBridge {
    @Shadow
    private long context;

    @Shadow
    private long currentDevice;

    @Override
    public long net_music_can_play_bili$context() {
        return context;
    }

    @Override
    public long net_music_can_play_bili$currentDevice() {
        return currentDevice;
    }
}
