package com.zhongbai233.net_music_can_play_bili.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLoginManager;
import com.zhongbai233.net_music_can_play_bili.bili.BiliWbiSigner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * B站二维码登录屏幕——AE2 OreUI 风格深色半透明弹窗。
 */
public class BiliQrLoginScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int QR_SIZE = 140;
    private static final int BOX_WIDTH = 200;
    private static final int BOX_HEIGHT = 220;

    private final BiliLoginManager loginManager;
    private DynamicTexture qrTexture;
    private Identifier qrTextureId;
    private volatile String statusText = "正在生成二维码...";
    private int pollTick;
    private int closeTick = -1;
    private volatile boolean done;
    private volatile int loadGeneration;
    private boolean removed;

    public BiliQrLoginScreen() {
        super(Component.literal("B站登录"));
        this.loginManager = new BiliLoginManager();
    }

    @Override
    protected void init() {
        removed = false;
        int generation = ++loadGeneration;
        loginManager.generate().thenAccept(state -> {
            if (!isCurrent(generation)) {
                return;
            }
            if (state == BiliLoginManager.State.PENDING) {
                statusText = "请用 B站APP 扫描二维码";
                loadQrImage(loginManager.getQrUrl(), generation);
            } else {
                statusText = "二维码生成失败，请重试";
            }
        });
    }

    private void loadQrImage(String qrContentUrl, int generation) {
        String encodedUrl = java.net.URLEncoder.encode(qrContentUrl, java.nio.charset.StandardCharsets.UTF_8);
        String qrImageUrl = "https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=" + encodedUrl;

        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(qrImageUrl))
                        .header("User-Agent", "Mozilla/5.0")
                        .timeout(Duration.ofSeconds(10))
                        .GET().build();
                HttpResponse<InputStream> resp = BiliWbiSigner.HTTP
                        .send(req, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = resp.body()) {
                    NativeImage image = NativeImage.read(in);
                    LOGGER.debug("二维码图片加载成功: {}x{}", image.getWidth(), image.getHeight());
                    return image;
                }
            } catch (Exception e) {
                LOGGER.error("加载二维码图片失败", e);
                return null;
            }
        }).thenAcceptAsync(nativeImage -> {
            if (nativeImage == null) {
                return;
            }
            if (!isCurrent(generation)) {
                nativeImage.close();
                return;
            }
            DynamicTexture texture = null;
            try {
                texture = new DynamicTexture(() -> "bili_qrcode", nativeImage);
                Identifier textureId = Identifier.fromNamespaceAndPath("net_music_can_play_bili", "bili_qrcode");
                cleanupTexture();
                this.qrTexture = texture;
                this.qrTextureId = textureId;
                this.minecraft.getTextureManager().register(textureId, texture);
                texture.upload();
            } catch (RuntimeException | LinkageError error) {
                if (this.qrTexture == texture) {
                    cleanupTexture();
                } else if (texture != null) {
                    texture.close();
                } else {
                    nativeImage.close();
                }
                LOGGER.error("创建二维码纹理失败", error);
            }
        }, Minecraft.getInstance());
    }

    private boolean isCurrent(int generation) {
        return !removed && generation == loadGeneration && this.minecraft != null
                && this.minecraft.screen == this;
    }

    @Override
    public void tick() {
        super.tick();
        if (done) {
            if (closeTick >= 0) {
                closeTick--;
                if (closeTick <= 0) {
                    this.onClose();
                }
            }
            return;
        }

        pollTick++;
        if (pollTick % 40 == 0) {
            loginManager.poll().thenAccept(state -> {
                switch (state) {
                    case PENDING -> statusText = "请用 B站APP 扫描二维码";
                    case SCANNED -> statusText = "已扫描，请在手机上确认登录";
                    case SUCCESS -> {
                        statusText = "登录成功！";
                        done = true;
                        closeTick = 40;
                    }
                    case EXPIRED -> {
                        statusText = "二维码已过期，请关闭重试";
                        done = true;
                    }
                    case FAILED -> {
                        statusText = "登录失败，请重试";
                        done = true;
                    }
                }
            });
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int boxX = (this.width - BOX_WIDTH) / 2;
        int boxY = (this.height - BOX_HEIGHT) / 2;

        graphics.fillGradient(boxX, boxY, boxX + BOX_WIDTH, boxY + BOX_HEIGHT, 0xF0222222, 0xF0333333);
        graphics.fillGradient(boxX + 1, boxY + 1, boxX + BOX_WIDTH - 1, boxY + 30, 0xFF333333, 0xFF2A2A2A);

        graphics.centeredText(this.font, "B站账号登录", boxX + BOX_WIDTH / 2, boxY + 10, 0xFFFFFFFF);

        // 二维码区域
        int qrX = boxX + (BOX_WIDTH - QR_SIZE) / 2;
        int qrY = boxY + 32;
        if (qrTextureId != null) {
            graphics.fillGradient(qrX - 2, qrY - 2, qrX + QR_SIZE + 2, qrY + QR_SIZE + 2, 0xFFFFFFFF, 0xFFFFFFFF);
            graphics.blit(qrTextureId, qrX, qrY, qrX + QR_SIZE, qrY + QR_SIZE, 0.0f, 1.0f, 0.0f, 1.0f);
        } else {
            graphics.fillGradient(qrX, qrY, qrX + QR_SIZE, qrY + QR_SIZE, 0xFF444444, 0xFF444444);
        }

        int statusColor = done ? 0xFF55FF55 : 0xFFAAAAAA;
        graphics.centeredText(this.font, statusText, boxX + BOX_WIDTH / 2, boxY + BOX_HEIGHT - 16, statusColor);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent evt, boolean cancelled) {
        if (done) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(evt, cancelled);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        cleanupTexture();
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public void removed() {
        removed = true;
        loadGeneration++;
        cleanupTexture();
    }

    private void cleanupTexture() {
        if (qrTexture != null) {
            if (this.minecraft != null && qrTextureId != null) {
                this.minecraft.getTextureManager().release(qrTextureId);
            } else {
                qrTexture.close();
            }
            qrTexture = null;
        }
        qrTextureId = null;
    }
}
