package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.client.gui.ComputerMenuScreen;
import com.zhongbai233.net_music_can_play_bili.bili.BiliConfig;
import com.zhongbai233.net_music_can_play_bili.gui.BiliQrLoginScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    private Button net_music_can_play_bili$dolbyButton;

    @Inject(method = "init", at = @At("TAIL"))
    private void net_music_can_play_bili$initLoginButton(CallbackInfo ci) {
        this.net_music_can_play_bili$loginButton = Button.builder(
                Component.literal("B站登录"),
                btn -> Minecraft.getInstance().setScreen(new BiliQrLoginScreen()))
                .pos(this.urlTextField.getX(),
                        this.urlTextField.getY() + 80)
                .size(60, 18)
                .build();

        this.net_music_can_play_bili$dolbyButton = Button.builder(
                Component.literal(BiliConfig.dolbyEnabled ? "杜比:开" : "杜比:关"),
                btn -> {
                    BiliConfig.dolbyEnabled = !BiliConfig.dolbyEnabled;
                    BiliConfig.save();
                    btn.setMessage(Component.literal(BiliConfig.dolbyEnabled ? "杜比:开" : "杜比:关"));
                })
                .pos(this.urlTextField.getX() + 64,
                        this.urlTextField.getY() + 80)
                .size(60, 18)
                .build();

        ScreenAccessor screen = (ScreenAccessor) this;
        screen.net_music_can_play_bili$addRenderableWidget(this.net_music_can_play_bili$loginButton);
        screen.net_music_can_play_bili$addRenderableWidget(this.net_music_can_play_bili$dolbyButton);
    }
}
