package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.client.gui.ComputerMenuScreen;
import com.zhongbai233.net_music_can_play_bili.gui.BiliQrLoginScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * 给 NetMusic 电脑加一个 "B站登录" 按钮
 */
@Mixin(ComputerMenuScreen.class)
public abstract class ComputerMenuScreenMixin {

    @Shadow
    private EditBox urlTextField;

    @Unique
    private Button net_music_can_play_bili$loginButton;

    @Unique
    private static final Method ADD_RENDERABLE_WIDGET;

    static {
        try {
            ADD_RENDERABLE_WIDGET = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
            ADD_RENDERABLE_WIDGET.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to find Screen.addRenderableWidget", e);
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void net_music_can_play_bili$initLoginButton(CallbackInfo ci) {
        this.net_music_can_play_bili$loginButton = Button.builder(
                Component.literal("B站登录"),
                btn -> Minecraft.getInstance().setScreen(new BiliQrLoginScreen()))
                .pos(this.urlTextField.getX(),
                     this.urlTextField.getY() + 80)
                .size(60, 18)
                .build();

        try {
            ADD_RENDERABLE_WIDGET.invoke(this, this.net_music_can_play_bili$loginButton);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add B站 login button", e);
        }
    }
}
