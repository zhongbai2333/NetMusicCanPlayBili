package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.client.gui.CDBurnerMenuScreen;
import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.network.message.SetMusicIDMessage;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliAudioResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

/**
 * 绕过 NetMusic 刻录 GUI 的网易云 ID 校验
 */
@Mixin(CDBurnerMenuScreen.class)
public abstract class CDBurnerMenuScreenMixin {

    @Shadow
    private EditBox textField;

    @Shadow
    private Checkbox readOnlyButton;

    @Shadow
    private Component tips;

    @Unique
    private EditBox net_music_can_play_bili$pageField;

    @Unique
    private AbstractWidget net_music_can_play_bili$pageLabel;

    @Inject(method = "init", at = @At("TAIL"))
    private void net_music_can_play_bili$initPageField(CallbackInfo ci) {
        String pageValue = "1";
        boolean focused = false;
        if (this.net_music_can_play_bili$pageField != null) {
            pageValue = this.net_music_can_play_bili$pageField.getValue();
            focused = this.net_music_can_play_bili$pageField.isFocused();
        }

        this.net_music_can_play_bili$pageField = new EditBox(
                Minecraft.getInstance().font,
                this.textField.getX() + 100,
                this.textField.getY() + 30,
                28,
                16,
                Component.literal("P"));
        this.net_music_can_play_bili$pageField.setValue(pageValue == null || pageValue.isBlank() ? "1" : pageValue);
        this.net_music_can_play_bili$pageField.setBordered(true);
        this.net_music_can_play_bili$pageField.setMaxLength(4);
        this.net_music_can_play_bili$pageField.setTextColor(-790560);
        this.net_music_can_play_bili$pageField.setFocused(focused);

        final int labelX = this.net_music_can_play_bili$pageField.getX();
        final int labelY = this.net_music_can_play_bili$pageField.getY() - 10;
        this.net_music_can_play_bili$pageLabel = new AbstractWidget(labelX, labelY, 24, 12, Component.literal("分P")) {
            @Override
            protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                    float partialTick) {
                graphics.text(Minecraft.getInstance().font, "B站分P", this.getX(), this.getY(), 0xFFFFFFFF);
            }

            @Override
            public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            }
        };

        MixinReflectionHelper.addWidget((Screen) (Object) this, this.net_music_can_play_bili$pageLabel);
        MixinReflectionHelper.addWidget((Screen) (Object) this, this.net_music_can_play_bili$pageField);
    }

    @Inject(method = "handleCraftButton", at = @At("HEAD"), cancellable = true)
    private void onHandleCraftButton(CallbackInfo ci) {
        String text = this.textField.getValue().trim();
        if (!BiliApiClient.isBiliVideoId(text)) {
            return;
        }

        int page = 1;
        if (this.net_music_can_play_bili$pageField != null) {
            String pageText = this.net_music_can_play_bili$pageField.getValue().trim();
            if (!pageText.isEmpty()) {
                try {
                    page = Integer.parseInt(pageText);
                } catch (NumberFormatException e) {
                    this.tips = Component.literal("分P 请输入正整数");
                    ci.cancel();
                    return;
                }
            }
        }
        if (page < 1) {
            this.tips = Component.literal("分P 请输入正整数");
            ci.cancel();
            return;
        }
        final int selectedPage = page;

        this.tips = Component.literal("正在解析 B站 音频，请稍候...");

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return BiliAudioResolver.resolveBiliSongInfo(text, selectedPage);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .whenComplete((info, error) -> Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        this.tips = Component.literal("B站解析失败: " + cause.getMessage());
                        return;
                    }

                    info.readOnly = this.readOnlyButton.selected();

                    NetworkHandler.sendToServer(new SetMusicIDMessage(info));
                }));

        ci.cancel();
    }
}
