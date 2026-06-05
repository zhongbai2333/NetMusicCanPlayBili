package com.zhongbai233.net_music_can_play_bili.mixin;

import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Button.class)
public interface ButtonAccessor {
    @Accessor("onPress")
    Button.OnPress net_music_can_play_bili$getOnPress();

    @Mutable
    @Accessor("onPress")
    void net_music_can_play_bili$setOnPress(Button.OnPress onPress);
}