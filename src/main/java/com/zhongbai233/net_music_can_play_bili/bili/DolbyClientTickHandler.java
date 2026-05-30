package com.zhongbai233.net_music_can_play_bili.bili;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

@EventBusSubscriber(value = Dist.CLIENT)
public final class DolbyClientTickHandler {
    private static final long TICK_FALLBACK_AFTER_NANOS = 250_000_000L;

    private static volatile long lastFrameUpdateNanos;

    private DolbyClientTickHandler() {
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (!DolbyAudioRegistry.isActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (updateFromCamera(mc)) {
            lastFrameUpdateNanos = System.nanoTime();
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!DolbyAudioRegistry.isActive()) {
            return;
        }

        long now = System.nanoTime();
        long lastFrame = lastFrameUpdateNanos;
        if (lastFrame != 0L && now - lastFrame < TICK_FALLBACK_AFTER_NANOS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (updateFromCamera(mc)) {
            return;
        }
        if (mc.player == null) {
            return;
        }

        updateListener(mc.player.getEyePosition());
    }

    private static boolean updateFromCamera(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) {
            return false;
        }
        updateListener(camera.position());
        return true;
    }

    private static void updateListener(Vec3 eye) {
        float[] listenerPos = new float[] {
                (float) eye.x,
                (float) eye.y,
                (float) eye.z
        };

        DolbyAudioRegistry.updatePositions(listenerPos);
    }
}
