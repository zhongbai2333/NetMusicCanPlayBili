package com.zhongbai233.net_music_can_play_bili.client.renderer;

/** 与 NetMusic 唱片机一致的 40 tick/圈旋转策略，不依赖客户端模型类。 */
final class DiscRotationPolicy {
    private static final float RADIANS_PER_TICK = (float) (Math.PI / 20.0D);

    private DiscRotationPolicy() {
    }

    static float rotationAt(long gameTime, float partialTick) {
        double ticks = Math.floorMod(gameTime, 40L) + Math.clamp(partialTick, 0.0F, 1.0F);
        return (float) (ticks * RADIANS_PER_TICK);
    }
}