package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.client.gui.BigMegaphoneScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * 大喇叭 B站直播支持
 */
@Mixin(BigMegaphoneScreen.class)
public abstract class BigMegaphoneScreenMixin {

    @Shadow
    private EditBox urlTextField;

    private static final Field ON_PRESS_FIELD;

    static {
        try {
            ON_PRESS_FIELD = Button.class.getDeclaredField("onPress");
            ON_PRESS_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void net_music_can_play_bili$hookButtons(CallbackInfo ci) {
        for (GuiEventListener child : ((Screen) (Object) this).children()) {
            if (child instanceof Button btn) {
                String msg = btn.getMessage().getString();
                boolean isStart = msg.contains("开始") || msg.equalsIgnoreCase("start");
                boolean isSave = msg.contains("保存") || msg.equalsIgnoreCase("save");
                if (!isStart && !isSave)
                    continue;

                try {
                    Button.OnPress original = (Button.OnPress) ON_PRESS_FIELD.get(btn);
                    Button.OnPress wrapped = btn2 -> {
                        String text = this.urlTextField.getValue().trim();
                        if (text.startsWith("live:")) {
                            String saved = text;
                            this.urlTextField.setValue("http://live/" + text.substring(5) + ".m3u8");
                            original.onPress(btn2);
                            this.urlTextField.setValue(saved);
                        } else {
                            original.onPress(btn2);
                        }
                    };
                    ON_PRESS_FIELD.set(btn, wrapped);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

}
