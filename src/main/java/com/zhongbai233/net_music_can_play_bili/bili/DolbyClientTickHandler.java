package com.zhongbai233.net_music_can_play_bili.bili;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(value = Dist.CLIENT)
public final class DolbyClientTickHandler {

    private DolbyClientTickHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!DolbyAudioRegistry.isActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        Vec3 eye = mc.player.getEyePosition();
        float[] listenerPos = new float[] {
                (float) eye.x,
                (float) eye.y,
                (float) eye.z
        };

        DolbyAudioRegistry.updatePositions(listenerPos);
    }
}
