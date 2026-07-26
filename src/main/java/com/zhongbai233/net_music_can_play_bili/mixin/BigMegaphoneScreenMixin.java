package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.client.gui.BigMegaphoneScreen;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 大喇叭 B站直播支持
 */
@Mixin(BigMegaphoneScreen.class)
public abstract class BigMegaphoneScreenMixin {

    @Shadow
    private EditBox urlTextField;

    @Inject(method = "init", at = @At("TAIL"))
    private void net_music_can_play_bili$hookButtons(CallbackInfo ci) {
        for (GuiEventListener child : ((Screen) (Object) this).children()) {
            if (child instanceof Button btn) {
                String msg = btn.getMessage().getString();
                boolean isStart = msg.contains("开始") || msg.equalsIgnoreCase("start");
                boolean isSave = msg.contains("保存") || msg.equalsIgnoreCase("save");
                if (!isStart && !isSave)
                    continue;

                ButtonAccessor bridge = (ButtonAccessor) btn;
                Button.OnPress original = bridge.net_music_can_play_bili$getOnPress();
                Button.OnPress wrapped = btn2 -> {
                    String text = this.urlTextField.getValue().trim();
                    String roomId = BiliLiveRoomInput.parseRoomId(text);
                    if (!roomId.isEmpty()) {
                        // NetMusic 服务端只接受 m3u8 广播地址，因此发送占位地址，
                        // 由客户端的 BiliLiveAudioStreamHandler 解析成真实直播流。
                        this.urlTextField.setValue(BiliLiveRoomInput.placeholderUrl(roomId));
                        original.onPress(btn2);
                        this.urlTextField.setValue(text);
                    } else {
                        original.onPress(btn2);
                    }
                };
                bridge.net_music_can_play_bili$setOnPress(wrapped);
            }
        }
    }

}
